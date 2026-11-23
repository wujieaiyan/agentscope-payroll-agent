package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import io.agentscope.examples.payroll.skill.JdbcSkillStore.Row;
import io.agentscope.examples.payroll.skill.SkillMigrationCheck.Result;
import io.agentscope.examples.payroll.skill.SkillRuntimeReader.CatalogFingerprint;
import io.agentscope.examples.payroll.skill.SkillRuntimeReader.Runtime;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 切换前对账（§11.3）：先证明「导入完就对得上」，再逐类差异各造一次，证明每一类桶都真的接得住现场。
 *
 * <p>差异都用<b>写入方</b>制造（改 Nacos 正文、管理台删文件、翻执行开关），只有 {@code resource_hash}
 * 漂移和版本行被删用 SQL 直改——那是本类要在切换前抓的事故形态，正常写路径产生不了。最后一组锁
 * fail-closed 与<b>失败归因</b>：两头任一读不出来都不许宣布「一致」，否则门禁会在最该拦的时候放行；
 * 资源整批读不回只许记一条 blob 侧问题，级联成每人一条「版本行未落库」会把运维引向一张没问题的表。
 */
class SkillMigrationCheckTest {

    private static final String CUSTOM = "audit-helper";
    private static final String RULES = "先取员工档案与考勤，再按参保城市政策测算，最后输出可复核的明细与依据。";

    @TempDir Path blobRoot;

    private FakeNacosServer nacos;
    private PayrollDatabase database;
    private JdbcSkillStore store;

    @BeforeEach
    void setUp() throws IOException {
        nacos = new FakeNacosServer();
        database = PayrollDatabase.inMemory("skill-check-" + System.nanoTime());
        store =
                new JdbcSkillStore(
                        database,
                        new LocalDiskSkillBlobStore(blobRoot.toString(), 1024 * 1024),
                        SkillFileSettings.defaults());
        publish(CUSTOM, body("独立审计工资数据"), false);
    }

    @AfterEach
    void stopNacos() {
        if (nacos != null) {
            nacos.close();
        }
    }

    @Test
    @DisplayName("导完之后对账必须全绿：正文、文件、执行开关、资源指纹逐项对上")
    void freshlyImportedDatabaseMatches() {
        importAll();

        Result result = check();

        assertTrue(result.match(), result.asMap().toString());
        assertEquals(4, result.nacosCount());
        assertEquals(4, result.dbCount());
        assertTrue(result.checkedFiles());
        assertEquals(0, result.problemCount());
        for (String bucket :
                List.of(
                        "missingInDb",
                        "bodyMismatch",
                        "fileMissing",
                        "fileExtra",
                        "fileHashMismatch",
                        "executionMismatch",
                        "resourceHashMismatch",
                        "orphanEnvelope",
                        "problems")) {
            assertTrue(((List<?>) result.asMap().get(bucket)).isEmpty(), bucket + " 不该有差异");
        }
    }

    @Test
    @DisplayName("切换后 Nacos 又被改过：正文差异必须报出来")
    void bodyEditedOnNacosIsReported() {
        importAll();
        nacos.configs.put(CUSTOM + ".md", markdown(CUSTOM, body("换了另一套审计口径"), false));

        Result result = check();

        assertFalse(result.match());
        assertEquals(1, result.bodyMismatch().size(), result.bodyMismatch().toString());
        assertTrue(result.bodyMismatch().get(0).startsWith(CUSTOM + "："), result.bodyMismatch().toString());
        assertTrue(result.bodyMismatch().get(0).contains("Nacos="), result.bodyMismatch().toString());
        assertEquals(1, result.problemCount());
    }

    @Test
    @DisplayName("技能在库里被删：报 missingInDb，不能算成 extraInDb 的反面")
    void deletedSkillIsMissingInDb() {
        importAll();
        store.delete(CUSTOM);

        Result result = check();

        assertEquals(List.of(CUSTOM), result.missingInDb());
        assertFalse(result.match());
        assertEquals(3, result.dbCount());
        assertTrue(result.fileMissing().isEmpty(), "缺整条技能不该再报缺文件");
    }

    @Test
    @DisplayName("库里删掉一个文件：Nacos 有、库里没有即 fileMissing")
    void fileRemovedInDbIsMissing() {
        publishWithFiles(CUSTOM, body("独立审计工资数据"), "references/faq.md", "# FAQ");
        importAll();
        store.deleteFile(CUSTOM, "references/faq.md");

        Result result = check();

        assertEquals(List.of(CUSTOM + "：references/faq.md"), result.fileMissing());
        assertTrue(result.fileExtra().isEmpty(), result.fileExtra().toString());
        assertFalse(result.match());
    }

    @Test
    @DisplayName("库里多出参考文件：fileExtra 与 fileMissing 各记一头")
    void fileAddedInDbIsExtra() {
        publishWithFiles(CUSTOM, body("独立审计工资数据"), "references/faq.md", "# FAQ");
        importAll();
        store.replaceFiles(
                CUSTOM, List.of(new SkillFilePackage.SkillFile("references/other.md", "# 其它")));

        Result result = check();

        assertEquals(List.of(CUSTOM + "：references/other.md"), result.fileExtra());
        assertEquals(List.of(CUSTOM + "：references/faq.md"), result.fileMissing());
        assertFalse(result.match());
    }

    @Test
    @DisplayName("字节与清单对不上：逐文件哈希差异进 fileHashMismatch")
    void fileContentDriftIsReported() {
        publishWithFiles(CUSTOM, body("独立审计工资数据"), "references/faq.md", "# FAQ");
        importAll();
        store.replaceFiles(
                CUSTOM, List.of(new SkillFilePackage.SkillFile("references/faq.md", "# 改过的 FAQ")));
        // 路径相同、内容不同：只有逐文件哈希能发现，路径集合比对会当成一致。
        nacos.configs.put(
                CUSTOM + ".files.json",
                SkillFilePackage.compose(
                        SkillFilePackage.mdHash(body("独立审计工资数据")),
                        List.of(new SkillFilePackage.SkillFile("references/faq.md", "# 别的 FAQ"))));

        Result result = check();

        assertEquals(List.of(CUSTOM + "：references/faq.md"), result.fileHashMismatch());
        assertTrue(result.fileMissing().isEmpty(), result.fileMissing().toString());
        assertTrue(result.fileExtra().isEmpty(), result.fileExtra().toString());
        assertFalse(result.match());
    }

    @Test
    @DisplayName("行上 resource_hash 与实读字节对不上：D11b 的指纹门禁抓的就是这一类")
    void resourceHashDriftOnTheRowIsCaught() throws Exception {
        publishWithFiles(CUSTOM, body("独立审计工资数据"), "references/faq.md", "# FAQ");
        importAll();
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE skill_definition SET resource_hash = ? WHERE skill_id = ?")) {
            statement.setString(1, "f".repeat(64));
            statement.setString(2, CUSTOM);
            assertEquals(1, statement.executeUpdate());
        }

        Result result = check();

        assertEquals(1, result.resourceHashMismatch().size(), result.resourceHashMismatch().toString());
        assertTrue(
                result.resourceHashMismatch().get(0).startsWith(CUSTOM + "："),
                result.resourceHashMismatch().toString());
        assertFalse(result.match());
    }

    @Test
    @DisplayName("执行开关两边不一样：切库后技能会静默失去 skill_run，必须拦")
    void executionFlagDriftIsReported() {
        importAll();
        store.update(CUSTOM, "审计助手", body("独立审计工资数据"), true);

        Result result = check();

        assertEquals(1, result.executionMismatch().size(), result.executionMismatch().toString());
        assertTrue(
                result.executionMismatch().get(0).contains("Nacos=false 库=true"),
                result.executionMismatch().toString());
        assertFalse(result.match());
        assertTrue(result.bodyMismatch().isEmpty(), "开关漂移不该被算成正文漂移");
    }

    @Test
    @DisplayName("只剩文件包没有正文的残留配置：orphanEnvelope")
    void orphanEnvelopeIsReported() {
        importAll();
        nacos.configs.put(
                "ghost-skill.files.json",
                SkillFilePackage.compose(
                        SkillFilePackage.mdHash("正文早已删除"),
                        List.of(new SkillFilePackage.SkillFile("references/a.md", "x"))));

        Result result = check();

        assertEquals(List.of("ghost-skill.files.json"), result.orphanEnvelope());
        assertFalse(result.match());
        assertTrue(result.missingInDb().isEmpty(), "残留不是缺失");
    }

    @Test
    @DisplayName("只在库里的技能属切换后正常增量：不阻断，也不计硬差异")
    void extraSkillInDbDoesNotBlock() {
        importAll();
        store.create(CUSTOM + "-2", "切换后新建", body("新建的技能"), false);

        Result result = check();

        assertEquals(List.of(CUSTOM + "-2"), result.extraInDb());
        assertTrue(result.match(), result.asMap().toString());
        assertEquals(0, result.problemCount());
        assertEquals(4, result.nacosCount());
        assertEquals(5, result.dbCount());
    }

    @Test
    @DisplayName("文件包开关关闭时只比正文：不拿读不到的清单造差异")
    void filesDisabledComparesBodiesOnly() {
        publishWithFiles(CUSTOM, body("独立审计工资数据"), "references/faq.md", "# FAQ");
        importAll();

        Result result = new SkillMigrationCheck(source(true), store, false).check();

        assertFalse(result.checkedFiles());
        assertTrue(result.match(), result.asMap().toString());
        assertTrue(result.fileMissing().isEmpty(), result.fileMissing().toString());
    }

    @Test
    @DisplayName("blob 根指错让整批资源读不回：只记一条 blob 侧问题，不许级联成每人一条误诊")
    void unreadableBlobsReportOnceNotPerSkill() {
        publishWithFiles(CUSTOM, body("独立审计工资数据"), "references/faq.md", "# FAQ");
        importAll();
        // 与现网事故同形：写入实例与 --skill-migration-check 的 PAYROLL_SKILL_BLOB_ROOT 不同值。
        JdbcSkillStore wrongBlobRoot =
                new JdbcSkillStore(
                        database,
                        new LocalDiskSkillBlobStore(
                                blobRoot.resolve("other-root").toString(), 1024 * 1024),
                        SkillFileSettings.defaults());

        Result result = new SkillMigrationCheck(source(true), wrongBlobRoot, true).check();

        assertFalse(result.match());
        assertEquals(1, result.problemCount(), result.asMap().toString());
        assertEquals(1, result.problems().size(), result.problems().toString());
        assertTrue(
                result.problems().get(0).startsWith("读取技能资源失败，本轮未比对文件包"),
                result.problems().toString());
        assertTrue(
                result.problems().get(0).contains("技能资源缺失：" + CUSTOM + "/references/faq.md"),
                "整批读失败抛的是最先撞上的那条技能，文案必须带 id 才查得动：" + result.problems());
        assertFalse(result.checkedFiles(), "没比成的事不许报成比过了");
        for (String bucket :
                List.of("fileMissing", "fileExtra", "fileHashMismatch", "resourceHashMismatch")) {
            assertTrue(((List<?>) result.asMap().get(bucket)).isEmpty(), bucket + " 不该有结论");
        }
        assertTrue(
                result.problems().stream().noneMatch(item -> item.contains("库内运行期数据缺失")),
                "一次批量读失败不能写成每人一条「版本行未落库」");
        // 不依赖字节的两维照旧给结论：正文与执行开关都比对过且一致。
        assertTrue(result.bodyMismatch().isEmpty(), result.bodyMismatch().toString());
        assertTrue(result.executionMismatch().isEmpty(), result.executionMismatch().toString());
    }

    @Test
    @DisplayName("定义行在、版本行被删：整批读成功时仍逐条如实报运行期缺失")
    void missingRuntimeEntryStillReported() throws Exception {
        publishWithFiles(CUSTOM, body("独立审计工资数据"), "references/faq.md", "# FAQ");
        importAll();
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE skill_definition SET current_version_id = ? WHERE skill_id = ?")) {
            statement.setLong(1, 987654321L);
            statement.setString(2, CUSTOM);
            assertEquals(1, statement.executeUpdate());
        }

        Result result = check();

        assertFalse(result.match());
        assertTrue(result.checkedFiles(), "其余技能确实比过了文件包");
        assertEquals(
                List.of(CUSTOM + "：库内运行期数据缺失（版本行未落库或被删）"),
                result.problems(),
                result.asMap().toString());
        assertEquals(1, result.problemCount());
    }

    @Test
    @DisplayName("Nacos 读不出来 fail-closed：不许把「读不到」当成「没有差异」")
    void unreadableNacosFailsClosed() {
        importAll();
        nacos.failReads = true;

        Result result = check();

        assertFalse(result.match());
        assertEquals(0, result.nacosCount());
        assertTrue(
                result.problems().stream().anyMatch(item -> item.startsWith("读取 Nacos 技能失败")),
                result.problems().toString());
        assertTrue(
                result.problems().stream().anyMatch(item -> item.startsWith("列出 Nacos 文件包失败")),
                result.problems().toString());
    }

    @Test
    @DisplayName("技能表读不出来 fail-closed：既不说一致，也不把全表报成缺失")
    void unreadableTableFailsClosed() {
        importAll();
        Result result =
                new SkillMigrationCheck(
                        source(true),
                        new SkillRuntimeReader() {

                            @Override
                            public List<Row> catalog() {
                                throw new PayrollUnavailableException("连接池已耗尽");
                            }

                            @Override
                            public Optional<Row> find(String id) {
                                throw new PayrollUnavailableException("连接池已耗尽");
                            }

                            @Override
                            public Map<String, Runtime> runtimeData(Collection<Row> rows) {
                                throw new PayrollUnavailableException("连接池已耗尽");
                            }

                            @Override
                            public String location() {
                                return "h2:skill_definition";
                            }

                            @Override
                            public CatalogFingerprint fingerprint() {
                                throw new PayrollUnavailableException("连接池已耗尽");
                            }
                        },
                        true)
                        .check();

        assertFalse(result.match());
        assertEquals(0, result.dbCount());
        assertTrue(result.missingInDb().isEmpty(), "读不到表不等于表是空的");
        assertTrue(
                result.problems().stream().anyMatch(item -> item.startsWith("读取技能表失败")),
                result.problems().toString());
    }

    private void importAll() {
        SkillNacosImporter.Report report =
                new SkillNacosImporter(source(true), store).importAllIfEmpty();
        assertTrue(report.clean(), report.summary());
    }

    private Result check() {
        return new SkillMigrationCheck(source(true), store, true).check();
    }

    private SkillNacosSource source(boolean enabled) {
        return new SkillNacosSource(
                new NacosSkillSettings(
                        enabled,
                        nacos.url(),
                        "payroll-agent",
                        "",
                        "",
                        "",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(60)),
                SkillFileSettings.defaults());
    }

    private void publish(String id, String body, boolean execution) {
        nacos.configs.put(id + ".md", markdown(id, body, execution));
    }

    private void publishWithFiles(String id, String body, String path, String content) {
        publish(id, body, false);
        nacos.configs.put(
                id + ".files.json",
                SkillFilePackage.compose(
                        SkillFilePackage.mdHash(body),
                        List.of(new SkillFilePackage.SkillFile(path, content))));
    }

    private static String markdown(String id, String body, boolean execution) {
        return "---\nname: "
                + id
                + "\ndescription: \"审计助手\"\n"
                + (execution ? "execution:\n  enabled: true\n" : "")
                + "---\n"
                + body;
    }

    private static String body(String marker) {
        return "## 角色\n\n" + marker + "\n\n## 规则\n\n" + RULES.repeat(8);
    }
}
