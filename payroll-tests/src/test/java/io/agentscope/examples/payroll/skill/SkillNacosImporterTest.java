package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import io.agentscope.examples.payroll.skill.JdbcSkillStore.Row;
import io.agentscope.examples.payroll.skill.SkillNacosImporter.Report;
import io.agentscope.examples.payroll.skill.SkillRuntimeReader.CatalogFingerprint;
import io.agentscope.examples.payroll.skill.SkillRuntimeReader.Runtime;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Nacos → 库的一次性导入（§18 T-05）：全量顺序、内置技能落库策略、坏文件包只记问题、
 * 逐技能互不拖累，以及「表非空整表跳过」这条幂等口径。
 *
 * <p>Nacos 侧用 {@link FakeNacosServer} 真发配置、库侧用真的 {@link JdbcSkillStore} 真落盘：
 * 本批的价值在于「两头都是真的」，桩掉任何一头都只剩在验证 {@code List.add}。
 * {@code SkillFileSettings} 一律显式给 {@code defaults()}，结论不随环境变量漂移。
 */
class SkillNacosImporterTest {

    private static final String RULES = "先取员工档案与考勤，再按参保城市政策测算，最后输出可复核的明细与依据。";

    /** 内置在前（{@link RoleSkills#ids()} 顺序），其余按 id 字典序：这条顺序直接进系统提示词。 */
    private static final List<String> ALL_IDS =
            List.of(
                    RoleSkills.CALCULATOR,
                    RoleSkills.VALIDATOR,
                    RoleSkills.ACCOUNTANT,
                    "audit-helper",
                    "billing-helper");

    @TempDir Path blobRoot;

    private FakeNacosServer nacos;
    private PayrollDatabase database;
    private JdbcSkillStore store;

    @BeforeEach
    void setUp() throws IOException {
        nacos = new FakeNacosServer();
        database = PayrollDatabase.inMemory("skill-import-" + System.nanoTime());
        store =
                new JdbcSkillStore(
                        database,
                        new LocalDiskSkillBlobStore(blobRoot.toString(), 1024 * 1024),
                        SkillFileSettings.defaults());
    }

    @AfterEach
    void stopNacos() {
        if (nacos != null) {
            nacos.close();
        }
    }

    @Test
    @DisplayName("空表全量导入：内置技能在前且带 builtin，frontmatter 的执行开关跟着落库")
    void emptyTableImportsEverySkillInOrder() {
        publish("audit-helper", "审计助手", body("独立审计工资数据"), false);
        publish("billing-helper", "账单助手", body("生成参保城市账单"), true);

        Report report = importer(true).importAllIfEmpty();

        assertTrue(report.executed(), report.summary());
        assertTrue(report.clean(), report.summary());
        assertEquals(ALL_IDS, report.imported());
        assertEquals(ALL_IDS, ids(store.catalog()));
        assertTrue(report.note().contains("payroll-agent"), report.note());
        assertTrue(report.summary().contains("新建 5"), report.summary());

        Map<String, Row> rows = byId(store.catalog());
        for (String builtin : RoleSkills.ids()) {
            assertTrue(rows.get(builtin).builtin(), builtin + " 必须按 D10a 落库并置 builtin");
            assertFalse(rows.get(builtin).executionEnabled(), "内置技能在 Nacos 无配置时默认不开执行");
            assertEquals(RoleSkills.description(builtin), rows.get(builtin).description());
        }
        assertFalse(rows.get("audit-helper").builtin());
        assertFalse(rows.get("audit-helper").executionEnabled());
        // §11.2 规则 6：开关在 md 的 frontmatter 里，不带上过去就是切库后静默失去 skill_run。
        assertTrue(rows.get("billing-helper").executionEnabled(), "执行开关必须随导入落库");

        // 正文进库前已去 frontmatter，与 Nacos 源的 detail 口径一致。
        SkillDetail detail = store.detail("audit-helper");
        assertEquals("审计助手", detail.description());
        assertEquals(body("独立审计工资数据"), detail.content());
        assertFalse(detail.content().contains("description:"), detail.content());
        assertEquals("db", detail.source());
    }

    @Test
    @DisplayName("带文件包的技能整包落库：清单与字节都能从库里读回")
    void filePackageTravelsWithTheSkill() {
        Map<String, String> pack = new LinkedHashMap<>();
        pack.put("references/faq.md", "个税专项附加扣除口径");
        pack.put("scripts/run.sh", "echo calc");
        publishWithFiles("doc-helper", "文档助手", body("按参考文件作答"), pack);

        Report report = importer(true).importAllIfEmpty();

        assertTrue(report.clean(), report.summary());
        assertTrue(report.fileProblems().isEmpty(), report.fileProblems().toString());
        Row row = store.find("doc-helper").orElseThrow();
        assertEquals(2, row.resourceCount());
        assertEquals("个税专项附加扣除口径", store.fileContent("doc-helper", "references/faq.md"));
        assertEquals("echo calc", store.fileContent("doc-helper", "scripts/run.sh"));
        assertEquals(Map.copyOf(pack), store.contents(row.id(), store.resourcesOf(row)));
    }

    @Test
    @DisplayName("表非空整表跳过：管理台删掉的技能不会被下一次重启灌回来")
    void nonEmptyTableSkipsTheWholeImport() {
        publish("audit-helper", "审计助手", body("独立审计工资数据"), false);
        assertTrue(importer(true).importAllIfEmpty().executed());
        store.delete("audit-helper");

        Report second = importer(true).importAllIfEmpty();

        assertFalse(second.executed());
        assertEquals(0, second.total());
        assertTrue(second.note().contains("已有"), second.note());
        assertTrue(second.summary().contains("未执行"), second.summary());
        assertFalse(store.find("audit-helper").isPresent(), "§11.2 规则 4：宁可残缺，不可静默复活");
        assertEquals(RoleSkills.ids(), ids(store.catalog()));
    }

    @Test
    @DisplayName("坏 envelope 只进报告：正文照旧导入，不算失败")
    void brokenFilePackageReportsButDoesNotBlock() {
        publish("json-broken", "甲", body("甲角色规则"), false);
        nacos.configs.put("json-broken.files.json", "{ this is not json");
        publish("hash-drift", "乙", body("乙角色规则"), false);
        nacos.configs.put(
                "hash-drift.files.json",
                SkillFilePackage.compose(
                        SkillFilePackage.mdHash("另一份正文"),
                        List.of(new SkillFilePackage.SkillFile("references/a.md", "# FAQ"))));

        Report report = importer(true).importAllIfEmpty();

        assertTrue(report.executed(), report.summary());
        assertTrue(report.clean(), "文件包问题按 §11.2 规则 2 不阻断切换");
        assertEquals(2, report.fileProblems().size(), report.fileProblems().toString());
        assertTrue(
                report.fileProblems().stream().anyMatch(item -> item.startsWith("json-broken：")),
                report.fileProblems().toString());
        assertTrue(
                report.fileProblems().stream()
                        .anyMatch(item -> item.contains("mdHash") && item.startsWith("hash-drift：")),
                report.fileProblems().toString());
        assertEquals(0, store.find("json-broken").orElseThrow().resourceCount());
        assertEquals(body("甲角色规则"), store.detail("json-broken").content());
        assertTrue(report.summary().contains("文件包问题 2"), report.summary());
    }

    @Test
    @DisplayName("单个技能写失败不中断全量：其余照常落库，失败项进报告")
    void oneFailureDoesNotStopTheRest() {
        publish("good-helper", "丁", body("丁角色规则"), false);
        publish("short-helper", "丙", "## 规则\n\n太短", false);

        Report report = importer(true).importAllIfEmpty();

        assertTrue(report.executed());
        assertFalse(report.clean());
        assertTrue(report.imported().containsAll(RoleSkills.ids()), report.imported().toString());
        assertTrue(report.imported().contains("good-helper"), report.imported().toString());
        assertFalse(report.imported().contains("short-helper"), report.imported().toString());
        assertEquals(1, report.failures().size(), report.failures().toString());
        assertTrue(report.failures().get(0).startsWith("short-helper："), report.failures().toString());
        assertTrue(report.failures().get(0).contains("过于简短"), report.failures().toString());
        assertEquals(
                report.imported().size() + report.failures().size(),
                report.total(),
                "total 是本轮真正处理过的条数，不含未执行");
        assertFalse(store.find("short-helper").isPresent());
        assertTrue(report.summary().contains("失败 1"), report.summary());
    }

    @Test
    @DisplayName("Nacos 读不到就是「未执行」，既不抛异常也不留半截数据")
    void unavailableNacosSkipsWithoutTouchingTheTable() {
        publish("audit-helper", "审计助手", body("独立审计工资数据"), false);

        Report disabled = importer(false).importAllIfEmpty();
        assertFalse(disabled.executed());
        assertTrue(disabled.note().contains("Nacos 未启用"), disabled.note());
        assertTrue(store.catalog().isEmpty());

        nacos.failNextList.set(1);
        Report listFailed = importer(true).importAllIfEmpty();
        assertFalse(listFailed.executed());
        assertTrue(listFailed.note().contains("读取 Nacos 技能失败"), listFailed.note());
        assertTrue(store.catalog().isEmpty(), "读不出来源时不能按「空表」继续往下走");
    }

    @Test
    @DisplayName("判空只读技能表：读不出来就报未执行，而不是当成空表开跑")
    void unavailableTableSkipsInsteadOfImporting() {
        publish("audit-helper", "审计助手", body("独立审计工资数据"), false);
        // 内置技能在 SkillStore#list() 的视图里本来就存在：判空若走那条路，首次导入永远被跳过。
        assertFalse(store.list().isEmpty());

        Report report =
                new SkillNacosImporter(source(true), store, new BrokenReader()).importAllIfEmpty();

        assertFalse(report.executed());
        assertTrue(report.note().contains("读取技能表失败"), report.note());
        assertTrue(store.catalog().isEmpty(), "读不出目录时一条都不该写");
    }

    /** 只用来模拟库不可用；导入器只碰 {@code catalog()}，其余方法一并拒绝。 */
    private static final class BrokenReader implements SkillRuntimeReader {

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
    }

    private SkillNacosImporter importer(boolean nacosEnabled) {
        return new SkillNacosImporter(source(nacosEnabled), store);
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

    private void publish(String id, String description, String body, boolean execution) {
        nacos.configs.put(id + ".md", markdown(id, description, body, execution));
    }

    private void publishWithFiles(
            String id, String description, String body, Map<String, String> pack) {
        publish(id, description, body, false);
        nacos.configs.put(
                id + ".files.json",
                SkillFilePackage.compose(
                        SkillFilePackage.mdHash(body),
                        pack.entrySet().stream()
                                .map(
                                        entry ->
                                                new SkillFilePackage.SkillFile(
                                                        entry.getKey(), entry.getValue()))
                                .toList()));
    }

    private static String markdown(
            String id, String description, String body, boolean execution) {
        StringBuilder text =
                new StringBuilder("---\nname: ")
                        .append(id)
                        .append("\ndescription: \"")
                        .append(description)
                        .append("\"\n");
        if (execution) {
            text.append("execution:\n  enabled: true\n");
        }
        return text.append("---\n").append(body).toString();
    }

    private static String body(String marker) {
        return "## 角色\n\n" + marker + "\n\n## 规则\n\n" + RULES.repeat(8);
    }

    private static List<String> ids(List<Row> rows) {
        return rows.stream().map(Row::id).toList();
    }

    private static Map<String, Row> byId(List<Row> rows) {
        Map<String, Row> byId = new LinkedHashMap<>();
        rows.forEach(row -> byId.put(row.id(), row));
        return byId;
    }
}
