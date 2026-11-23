package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import io.agentscope.examples.payroll.skill.JdbcSkillStore.Row;
import io.agentscope.examples.payroll.skill.SkillRuntimeReader.Runtime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * db 源运行期读路径（§18 T-03）。
 *
 * <p>打桩对象是 {@link SkillRuntimeReader} 而不是 {@code JdbcSkillStore}：仓储与库都是 {@code final}，
 * 只有隔着端口才能数清「目录查了几次、正文回源几次」。SQL 与事务本身由 {@code JdbcSkillStoreTest} 覆盖。
 */
class DbSkillRepositoryTest {

    private static final String DESCRIPTION = "按最新政策核算工资并解释每一项依据";

    private final Stub reader = new Stub();
    private final AtomicLong clock = new AtomicLong(0L);
    private final DbSkillRepository repository =
            new DbSkillRepository(reader, 60_000L, clock::get);

    @Test
    @DisplayName("整张目录只查一次：不读正文、不碰字节")
    void catalogIsASingleQueryWithoutBodies() {
        seed("payroll-calculator", "## 角色\n核算工资。", Map.of(), 1L);
        seed("payroll-validator", "## 规则\n校验考勤。", Map.of("references/tax.md", "第一条"), 2L);

        List<SkillManifest> manifests = repository.listManifests();

        assertEquals(1, reader.catalogCalls);
        assertEquals(List.of(), reader.runtimeBatches);
        assertEquals(0, reader.findCalls);
        assertEquals(List.of("payroll-calculator", "payroll-validator"), ids(manifests));
        SkillManifest validator = manifests.get(1);
        assertEquals(DESCRIPTION, validator.description());
        assertEquals(List.of("payroll", "audit"), validator.tags());
        assertEquals("1.2.0", validator.version());
        assertEquals("db", validator.source());
    }

    @Test
    @DisplayName("资源指纹冷启动即为真值，与装配结果逐字段一致")
    void resourceHashIsTrueBeforeAnyAssembly() {
        Map<String, String> resources =
                Map.of("references/tax.md", "第一条", "scripts/setup.sh", "pip install -r requirements.txt");
        seed(RoleSkills.VALIDATOR, "## 规则\n校验薪资数据。", resources, 7L);

        SkillManifest cold = repository.manifest(RoleSkills.VALIDATOR).orElseThrow();

        assertEquals(0, reader.runtimeBatches.size(), "读目录不该顺带装配正文");
        assertEquals(SkillManifests.resourceHash(resources), cold.resourceHash());

        AgentSkill skill = repository.getSkill(RoleSkills.VALIDATOR);
        SkillManifest assembled = SkillManifests.fromSkill(skill);
        assertEquals(cold.contentHash(), assembled.contentHash());
        assertEquals(cold.contentLength(), assembled.contentLength());
        assertEquals(cold.resourceHash(), assembled.resourceHash());
        assertEquals(cold.executionEnabled(), assembled.executionEnabled());
        assertEquals(cold.tags(), assembled.tags());
        assertEquals(cold.version(), assembled.version());
        assertEquals(cold.source(), assembled.source());
    }

    @Test
    @DisplayName("没有资源时资源指纹是空串，而不是某个真实哈希")
    void emptyResourceHashMeansNoFiles() {
        seed("payroll-calculator", "## 角色\n核算工资。", Map.of(), 1L);

        assertEquals("", repository.manifest("payroll-calculator").orElseThrow().resourceHash());
    }

    @Test
    @DisplayName("多个技能的正文与资源一次批量回源，顺序跟入参、缺失的跳过")
    void assemblesWholeBatchAtOnce() {
        seed("skill-a", "## 角色\n甲。", Map.of(), 1L);
        seed("skill-b", "## 角色\n乙。", Map.of("references/x.md", "内容"), 2L);
        seed("skill-c", "## 角色\n丙。", Map.of(), 3L);
        repository.listManifests();
        reader.runtimeBatches.clear();

        List<AgentSkill> skills =
                repository.getSkills(List.of("skill-c", "不存在的技能", "skill-a", "skill-b"));

        assertEquals(List.of(3), reader.runtimeBatches);
        assertEquals(List.of("skill-c", "skill-a", "skill-b"), names(skills));
    }

    @Test
    @DisplayName("目录按 TTL 过期后重建，未过期不重复查询")
    void catalogExpiresWithTtl() {
        seed("payroll-calculator", "## 角色\n核算工资。", Map.of(), 1L);

        repository.listManifests();
        clock.set(59_999L);
        repository.listManifests();
        assertEquals(1, reader.catalogCalls);

        clock.set(60_001L);
        repository.listManifests();
        assertEquals(2, reader.catalogCalls);
    }

    @Test
    @DisplayName("正文缓存按内容身份命中：目录刷新不重复装配，版本或资源变了才重装")
    void entryIsReusedUntilContentIdentityChanges() {
        seed("payroll-calculator", "## 角色\n核算工资。", Map.of("references/a.md", "第一版"), 1L);

        assertEquals("## 角色\n核算工资。", repository.getSkill("payroll-calculator").getSkillContent());
        clock.set(60_001L);
        repository.getSkill("payroll-calculator");
        assertEquals(List.of(1), reader.runtimeBatches, "内容身份未变，不该再回源");

        reader.runtimes.put(
                "payroll-calculator",
                runtime("## 角色\n核算工资（改文案）。", Map.of("references/a.md", "第一版"), false));
        seed("payroll-calculator", "## 角色\n核算工资（改文案）。", Map.of("references/a.md", "第一版"), 2L);
        clock.set(120_001L);
        assertEquals(
                "## 角色\n核算工资（改文案）。",
                repository.getSkill("payroll-calculator").getSkillContent());
        assertEquals(List.of(1, 1), reader.runtimeBatches);

        reader.runtimes.put(
                "payroll-calculator",
                runtime("## 角色\n核算工资（改文案）。", Map.of("references/a.md", "第二版"), false));
        seed("payroll-calculator", "## 角色\n核算工资（改文案）。", Map.of("references/a.md", "第二版"), 2L);
        clock.set(180_001L);
        AgentSkill refreshed = repository.getSkill("payroll-calculator");
        assertEquals(List.of(1, 1, 1), reader.runtimeBatches);
        assertEquals("第二版", refreshed.getResource("references/a.md"));
    }

    @Test
    @DisplayName("断库时沿用上次成功的目录；冷启动没有快照则原样抛出")
    void catalogSurvivesTransientOutage() {
        seed("payroll-calculator", "## 角色\n核算工资。", Map.of(), 1L);
        assertEquals(1, repository.listManifests().size());

        clock.set(60_001L);
        reader.catalogFailure = new PayrollUnavailableException("连接超时");
        List<SkillManifest> stale = repository.listManifests();
        assertEquals(1, stale.size());
        assertEquals("payroll-calculator", stale.get(0).id());
        assertEquals(2, reader.catalogCalls);

        DbSkillRepository cold = new DbSkillRepository(reader, 60_000L, clock::get);
        assertThrows(PayrollUnavailableException.class, cold::listManifests);
    }

    @Test
    @DisplayName("目录里还没有的技能单行补查，不让调用方等一整个 TTL")
    void unknownIdIsBackfilledWithSingleRowLookup() {
        seed("payroll-calculator", "## 角色\n核算工资。", Map.of(), 1L);
        repository.listManifests();

        seed("payroll-auditor", "## 角色\n审计批次。", Map.of(), 2L);
        assertEquals(0, reader.findCalls);
        SkillManifest manifest = repository.manifest("payroll-auditor").orElseThrow();
        assertEquals(1, reader.findCalls);
        assertEquals("payroll-auditor", manifest.id());
        assertEquals("db", manifest.source());
    }

    @Test
    @DisplayName("脚本与 setup.sh 一并装配：沙箱投送与 skill_run 依赖整包内容")
    void assemblesScriptsAndSetup() {
        Map<String, String> resources =
                Map.of(
                        "scripts/setup.sh", "pip install -r requirements.txt",
                        "scripts/run.py", "print('ok')",
                        "references/note.md", "参考");
        seed("poetry-lyrics", "## 角色\n写歌词。", resources, 1L, true);

        AgentSkill skill = repository.getSkill("poetry-lyrics");

        assertEquals(resources, skill.getResources());
        assertEquals("db", skill.getSource());
        assertTrue(SkillExecutions.executionEnabled(skill));
        assertTrue(repository.manifest("poetry-lyrics").orElseThrow().executionEnabled());
    }

    @Test
    @DisplayName("缺描述时用内置角色说明兜底，提示词不会出现空行")
    void blankDescriptionFallsBackToRoleSkills() {
        Row row =
                row(RoleSkills.CALCULATOR, "## 角色\n核算工资。", Map.of(), "", List.of(), "1.0", false, 1L);
        reader.rows.put(row.id(), row);
        reader.runtimes.put(
                row.id(),
                new Runtime(
                        "## 角色\n核算工资。", Map.of("name", RoleSkills.CALCULATOR), Map.of()));

        assertEquals(
                RoleSkills.description(RoleSkills.CALCULATOR),
                repository.manifest(RoleSkills.CALCULATOR).orElseThrow().description());
        assertEquals(
                RoleSkills.description(RoleSkills.CALCULATOR),
                repository.getSkill(RoleSkills.CALCULATOR).getDescription());
    }

    @Test
    @DisplayName("存在性判定不装配正文；版本行丢失的技能读成不存在但目录仍列出")
    void existsIsCheapAndMissingVersionRowReadsAsAbsent() {
        seed("payroll-calculator", "## 角色\n核算工资。", Map.of(), 1L);
        seed("payroll-validator", "## 规则\n校验。", Map.of(), 2L);
        reader.runtimes.remove("payroll-validator");

        assertTrue(repository.skillExists("payroll-calculator"));
        assertTrue(repository.skillExists("payroll-validator"), "目录行在即视为存在");
        assertEquals(List.of(), reader.runtimeBatches, "存在性判定只查目录");

        assertNull(repository.getSkill("payroll-validator"));
        assertEquals(List.of(), repository.getSkills(List.of("payroll-validator")));
        assertEquals(List.of("payroll-calculator"), names(repository.getAllSkills()));
    }

    @Test
    @DisplayName("回源失败不沿用上一版正文：宁可抛出让上层计降级，也不拿新指纹装旧内容")
    void assemblyFailurePropagatesInsteadOfServingStaleBody() {
        seed("payroll-calculator", "## 角色\n核算工资。", Map.of(), 1L);
        repository.getSkill("payroll-calculator");

        seed("payroll-calculator", "## 角色\n核算工资。", Map.of(), 2L);
        clock.set(60_001L);
        reader.runtimeFailure = new PayrollUnavailableException("字节丢失");

        assertThrows(PayrollUnavailableException.class, () -> repository.getSkill("payroll-calculator"));
    }

    @Test
    @DisplayName("失效后目录与正文一起作废，管理台写入下一轮就能读到")
    void invalidateDropsCatalogAndBodies() {
        seed("payroll-calculator", "## 角色\n核算工资。", Map.of(), 1L);
        repository.getSkill("payroll-calculator");
        assertEquals(1, reader.catalogCalls);
        assertEquals(List.of(1), reader.runtimeBatches);

        repository.invalidate();
        repository.getSkill("payroll-calculator");

        assertEquals(2, reader.catalogCalls);
        assertEquals(List.of(1, 1), reader.runtimeBatches);
    }

    @Test
    @DisplayName("只刷目录不刷正文：别的技能被保存，未变更的技能不重新装配")
    void refreshDropsCatalogOnly() {
        seed("payroll-calculator", "## 角色\n核算工资。", Map.of(), 1L);
        seed("payroll-validator", "## 规则\n校验考勤。", Map.of(), 2L);
        repository.getSkills(List.of("payroll-calculator", "payroll-validator"));
        assertEquals(1, reader.catalogCalls);
        reader.runtimeBatches.clear();

        repository.refresh();
        repository.getSkills(List.of("payroll-calculator", "payroll-validator"));

        assertEquals(2, reader.catalogCalls, "目录要重查，否则看不见别的节点的写入");
        assertEquals(List.of(), reader.runtimeBatches, "内容身份没变，正文不该回源");
    }

    @Test
    @DisplayName("运行期只读：写接口一律拒绝，来源与位置用于排错展示")
    void readOnlyRepository() {
        assertEquals("db", repository.getSource());
        assertEquals("db", repository.getRepositoryInfo().getType());
        assertEquals("jdbc:h2:mem:skill-test", repository.getRepositoryInfo().getLocation());
        assertFalse(repository.isWriteable());
        assertFalse(repository.save(List.of(), true));
        assertFalse(repository.delete("payroll-calculator"));
        repository.setWriteable(true);
        assertFalse(repository.isWriteable());
        repository.close();
    }

    @Test
    @DisplayName("非法 id 不查库：空白先归一，未命中的合法 id 只补查一次")
    void invalidAndPaddedIds() {
        seed("payroll-calculator", "## 角色\n核算工资。", Map.of(), 1L);

        assertFalse(repository.skillExists("Payroll Calculator"));
        assertFalse(repository.skillExists(""));
        assertFalse(repository.skillExists(null));
        assertEquals(0, reader.findCalls, "非法 id 不该走到库里");

        assertTrue(repository.skillExists("  payroll-calculator  "));
        assertEquals(0, reader.findCalls, "目录已覆盖，不必补查");

        assertFalse(repository.skillExists("payroll-auditor"));
        assertEquals(1, reader.findCalls, "目录未覆盖，补查一次");
    }

    private void seed(String id, String body, Map<String, String> resources, long versionId) {
        seed(id, body, resources, versionId, false);
    }

    private void seed(
            String id, String body, Map<String, String> resources, long versionId, boolean execution) {
        reader.rows.put(
                id,
                row(
                        id,
                        body,
                        resources,
                        DESCRIPTION,
                        List.of("payroll", "audit"),
                        "1.2.0",
                        execution,
                        versionId));
        reader.runtimes.put(id, runtime(body, resources, execution));
    }

    private static Runtime runtime(String body, Map<String, String> resources, boolean execution) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", "payroll");
        metadata.put("description", DESCRIPTION);
        metadata.put("tags", List.of("payroll", "audit"));
        metadata.put("version", "1.2.0");
        metadata.put("license", "Apache-2.0");
        if (execution) {
            metadata.put(SkillExecutions.META_EXECUTION, Map.of("enabled", true));
        }
        return new Runtime(body, metadata, resources);
    }

    /** 与 {@code JdbcSkillStore} 写路径同规则现算目录行：哈希列对不上，装配前后就是两个指纹。 */
    private static Row row(
            String id,
            String body,
            Map<String, String> resources,
            String description,
            List<String> tags,
            String version,
            boolean execution,
            long versionId) {
        return new Row(
                id,
                description,
                tags,
                version,
                false,
                execution,
                SkillManifests.contentHash(body),
                body.length(),
                resources.size(),
                SkillManifests.resourceHash(resources),
                versionId,
                versionId,
                1_000L);
    }

    private static List<String> ids(List<SkillManifest> manifests) {
        return manifests.stream().map(SkillManifest::id).toList();
    }

    private static List<String> names(List<AgentSkill> skills) {
        return skills.stream().map(AgentSkill::getName).toList();
    }

    /** 计数桩：只记录调用形状，不做任何缓存，缓存语义归被测类。 */
    private static final class Stub implements SkillRuntimeReader {

        private final Map<String, Row> rows = new LinkedHashMap<>();
        private final Map<String, Runtime> runtimes = new LinkedHashMap<>();
        private final List<Integer> runtimeBatches = new ArrayList<>();
        private int catalogCalls;
        private int findCalls;
        private RuntimeException catalogFailure;
        private RuntimeException runtimeFailure;

        @Override
        public List<Row> catalog() {
            catalogCalls++;
            if (catalogFailure != null) {
                throw catalogFailure;
            }
            return List.copyOf(rows.values());
        }

        @Override
        public Optional<Row> find(String id) {
            findCalls++;
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Map<String, Runtime> runtimeData(Collection<Row> batch) {
            runtimeBatches.add(batch.size());
            if (runtimeFailure != null) {
                throw runtimeFailure;
            }
            Map<String, Runtime> results = new LinkedHashMap<>();
            for (Row row : batch) {
                Runtime runtime = runtimes.get(row.id());
                if (runtime != null) {
                    results.put(row.id(), runtime);
                }
            }
            return results;
        }

        @Override
        public String location() {
            return "jdbc:h2:mem:skill-test";
        }

        /** 与 {@code JdbcSkillStore.fingerprint()} 同口径：桩报的 revision 变化必须能被轮询器看见。 */
        @Override
        public CatalogFingerprint fingerprint() {
            StringBuilder raw = new StringBuilder();
            rows.values().forEach(
                    row -> raw.append(row.id()).append(':').append(row.revision()).append('\n'));
            return new CatalogFingerprint(SkillManifests.contentHash(raw.toString()), 1_000L);
        }
    }
}
