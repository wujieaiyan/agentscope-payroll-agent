package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.examples.payroll.skill.DbSkillRepository;
import io.agentscope.examples.payroll.skill.JdbcSkillStore.Row;
import io.agentscope.examples.payroll.skill.RoleSkills;
import io.agentscope.examples.payroll.skill.SkillExecutions;
import io.agentscope.examples.payroll.skill.SkillManifest;
import io.agentscope.examples.payroll.skill.SkillManifests;
import io.agentscope.examples.payroll.skill.SkillRuntimeReader;
import io.agentscope.examples.payroll.skill.SkillRuntimeReader.Runtime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * db 源的智能体缓存指纹契约（规格 §18 T-07、验收 V-12）。
 *
 * <p>Nacos 侧的指纹覆盖已由 {@code AgentCacheTest} 锁住；这里补 db 源那条：指纹取自目录行上的哈希列，
 * 而目录行由写路径现算，因此它必须与「装配出来的技能再算一遍」逐字节相同。两边一旦分叉，
 * 轻则每轮对话重建智能体，重则内容换了指纹没换、模型继续读旧文件包。
 */
class SkillFingerprintContractTest {

    private static final String ID = RoleSkills.VALIDATOR;
    private static final String BODY = "## 规则\n按最新政策校验薪资数据。";
    private static final List<String> IDS = List.of(ID);

    private final FakeStore store = new FakeStore();
    private final DbSkillRepository repository = new DbSkillRepository(store);

    @Test
    @DisplayName("目录指纹与装配后的指纹逐字节相同")
    void catalogFingerprintEqualsAssembledFingerprint() {
        store.publish(BODY, Map.of("references/tax.md", "第一条"), 1L, false);

        AgentSkill skill = repository.getSkill(ID);
        SkillManifest assembled = SkillManifests.fromSkill(skill);
        String expected =
                ID
                        + ':'
                        + assembled.source()
                        + ':'
                        + assembled.contentHash()
                        + ':'
                        + assembled.resourceHash()
                        + ':'
                        + (assembled.executionEnabled() ? "exec" : "")
                        + ';';

        assertEquals(expected, PayrollAgentFactory.skillFingerprint(repository, IDS));
    }

    @Test
    @DisplayName("只改参考文件也换指纹：正文哈希不变时资源指纹独自承担失效")
    void resourceOnlyChangeInvalidatesFingerprint() {
        store.publish(BODY, Map.of("references/tax.md", "第一条"), 1L, false);
        String first = PayrollAgentFactory.skillFingerprint(repository, IDS);

        store.publish(BODY, Map.of("references/tax.md", "第二条"), 2L, false);
        repository.invalidate();
        String second = PayrollAgentFactory.skillFingerprint(repository, IDS);

        assertNotEquals(first, second, "只改参考文件必须重建智能体，否则模型继续读旧文件");
        assertTrue(
                second.contains(SkillManifests.resourceHash(Map.of("references/tax.md", "第二条"))),
                "指纹应直接带上资源哈希，便于排查重建原因");
    }

    @Test
    @DisplayName("执行开关进入指纹：开沙箱执行后工具集必须重建")
    void executionSwitchInvalidatesFingerprint() {
        store.publish(BODY, Map.of("scripts/run.py", "print('ok')"), 1L, false);
        String off = PayrollAgentFactory.skillFingerprint(repository, IDS);

        store.publish(BODY, Map.of("scripts/run.py", "print('ok')"), 2L, true);
        repository.invalidate();
        String on = PayrollAgentFactory.skillFingerprint(repository, IDS);

        assertNotEquals(off, on);
        assertTrue(on.endsWith("exec;"), "开启执行后指纹应带 exec 标记：" + on);
    }

    @Test
    @DisplayName("未发布的技能签成 missing，不影响同一批里其他技能")
    void missingSkillIsSignedAndIsolated() {
        store.publish(BODY, Map.of(), 1L, false);
        List<String> ids = List.of(ID, "not-published");

        String fingerprint = PayrollAgentFactory.skillFingerprint(repository, ids);

        assertEquals(
                fingerprint, PayrollAgentFactory.skillFingerprint(repository, ids), "同一内容不抖");
        assertTrue(fingerprint.contains("not-published:missing"), fingerprint);
        assertTrue(fingerprint.indexOf(ID) < fingerprint.indexOf("not-published"));
    }

    /** 只有一份技能的读端口替身：写一次换一次目录行与正文。 */
    private static final class FakeStore implements SkillRuntimeReader {

        private Row row;
        private Runtime runtime;

        void publish(
                String body, Map<String, String> resources, long versionId, boolean execution) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("description", "校验薪资数据");
            metadata.put("tags", List.of("payroll"));
            metadata.put("version", "1.0.0");
            if (execution) {
                metadata.put(SkillExecutions.META_EXECUTION, Map.of("enabled", true));
            }
            this.runtime = new Runtime(body, metadata, resources);
            this.row =
                    new Row(
                            ID,
                            "校验薪资数据",
                            List.of("payroll"),
                            "1.0.0",
                            false,
                            execution,
                            SkillManifests.contentHash(body),
                            body.length(),
                            resources.size(),
                            SkillManifests.resourceHash(resources),
                            versionId,
                            versionId,
                            versionId);
        }

        @Override
        public List<Row> catalog() {
            return row == null ? List.of() : List.of(row);
        }

        @Override
        public Optional<Row> find(String id) {
            return row != null && row.id().equals(id) ? Optional.of(row) : Optional.empty();
        }

        @Override
        public Map<String, Runtime> runtimeData(Collection<Row> rows) {
            Map<String, Runtime> results = new LinkedHashMap<>();
            for (Row candidate : rows) {
                if (row != null && candidate.id().equals(row.id())) {
                    results.put(candidate.id(), runtime);
                }
            }
            return results;
        }

        @Override
        public String location() {
            return "jdbc:h2:mem:fingerprint-test";
        }

        /** 轮询用的指纹：跟着目录行走即可，本测试只关心「发布一次就变一次」。 */
        @Override
        public SkillRuntimeReader.CatalogFingerprint fingerprint() {
            String raw = row == null ? "" : row.id() + ':' + row.revision() + '\n';
            return new SkillRuntimeReader.CatalogFingerprint(
                    SkillManifests.contentHash(raw), row == null ? 0L : row.updatedAt());
        }
    }
}
