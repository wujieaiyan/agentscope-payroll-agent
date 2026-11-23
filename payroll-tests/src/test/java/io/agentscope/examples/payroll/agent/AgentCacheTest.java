package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.skill.RoleSkills;
import io.agentscope.examples.payroll.skill.SkillManifests;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentCacheTest {

    @Test
    @DisplayName("相同 key 只构建一次并复用实例")
    void reusesInstanceForSameKey() {
        AgentCache cache = new AgentCache();
        AtomicInteger builds = new AtomicInteger();

        Object first = cache.get("team|a", builds::incrementAndGet);
        Object second = cache.get("team|a", builds::incrementAndGet);

        assertSame(first, second);
        assertEquals(1, builds.get());
        assertEquals(1, cache.size());
    }

    @Test
    @DisplayName("不同 key 各自构建")
    void buildsSeparatelyForDifferentKeys() {
        AgentCache cache = new AgentCache();
        Object first = cache.get("team|a", Object::new);
        Object second = cache.get("team|b", Object::new);

        assertNotSame(first, second);
        assertEquals(2, cache.size());
    }

    @Test
    @DisplayName("并发首次访问同一 key 也只构建一次")
    void buildsOnceUnderConcurrency() throws Exception {
        AgentCache cache = new AgentCache();
        AtomicInteger builds = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 8; i++) {
                pool.submit(
                        () -> {
                            start.await();
                            return cache.get(
                                    "team|a",
                                    () -> {
                                        builds.incrementAndGet();
                                        return new Object();
                                    });
                        });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, builds.get());
    }

    @Test
    @DisplayName("clear 关闭 AutoCloseable 实例并允许重新构建")
    void clearClosesEntries() {
        AgentCache cache = new AgentCache();
        AtomicInteger closed = new AtomicInteger();
        AutoCloseable closeable =
                () -> {
                    closed.incrementAndGet();
                };

        cache.get("team|a", () -> closeable);
        cache.clear();

        assertEquals(0, cache.size());
        assertEquals(1, closed.get());
        assertNotSame(closeable, cache.get("team|a", Object::new));
    }

    @Test
    @DisplayName("空闲超过 TTL 的实例被关闭并允许重新构建")
    void idleEntriesAreEvicted() throws InterruptedException {
        AgentCache cache = new AgentCache(4, Duration.ofMillis(10));
        AtomicInteger closed = new AtomicInteger();
        AutoCloseable closeable =
                () -> {
                    closed.incrementAndGet();
                };

        cache.get("team|a", () -> closeable);
        Thread.sleep(30);
        cache.get("team|b", Object::new);

        assertEquals(1, cache.size());
        assertEquals(1, closed.get());
    }

    @Test
    @DisplayName("定义签名随名称、描述或技能变化")
    void definitionSignatureCoversMutableFields() {
        AgentDefinition base = definition("a1", "薪酬计算", "计算工资", List.of("payroll-calculator"));
        AgentDefinition same = definition("a1", "薪酬计算", "计算工资", List.of("payroll-calculator"));
        AgentDefinition renamed = definition("a1", "薪酬核算", "计算工资", List.of("payroll-calculator"));
        AgentDefinition rechained =
                definition("a1", "薪酬计算", "计算工资", List.of("payroll-validator"));

        assertEquals(PayrollAgentFactory.definitionSignature(base),
                PayrollAgentFactory.definitionSignature(same));
        assertNotSameSignature(base, renamed);
        assertNotSameSignature(base, rechained);
    }

    private static void assertNotSameSignature(AgentDefinition left, AgentDefinition right) {
        String leftSignature = PayrollAgentFactory.definitionSignature(left);
        String rightSignature = PayrollAgentFactory.definitionSignature(right);
        assertTrue(
                !leftSignature.equals(rightSignature),
                "定义签名应随字段变化：" + leftSignature + " vs " + rightSignature);
    }

    @Test
    @DisplayName("技能指纹覆盖资源内容与路径，相同集合不受顺序影响")
    void skillFingerprintCoversResourceContentAndPath() {
        String faq = PayrollAgentFactory.skillFingerprint(
                skillRepository("references/faq.md", "# FAQ"), List.of(RoleSkills.CALCULATOR));

        assertEquals(faq, PayrollAgentFactory.skillFingerprint(
                skillRepository("references/faq.md", "# FAQ"), List.of(RoleSkills.CALCULATOR)));
        assertTrue(
                !faq.equals(
                        PayrollAgentFactory.skillFingerprint(
                                skillRepository("references/faq.md", "# 改了内容"),
                                List.of(RoleSkills.CALCULATOR))),
                "只改参考文件也必须让指纹变化，否则模型继续读旧文件");
        assertTrue(
                !faq.equals(
                        PayrollAgentFactory.skillFingerprint(
                                skillRepository("references/policy.md", "# FAQ"),
                                List.of(RoleSkills.CALCULATOR))),
                "文件改名同样要重建");
        assertTrue(
                !faq.equals(
                        PayrollAgentFactory.skillFingerprint(
                                emptySkillRepository(), List.of(RoleSkills.CALCULATOR))),
                "删掉文件包也要重建");
        assertTrue(
                faq.contains(
                        SkillManifests.resourceHash(Map.of("references/faq.md", "# FAQ"))),
                "指纹应直接带上资源哈希，便于排查重建原因");
    }

    @Test
    @DisplayName("技能缺失时指纹稳定，不随读取次数抖动")
    void skillFingerprintStableForMissingSkill() {
        List<String> ids = List.of(RoleSkills.CALCULATOR, "not-published");

        assertEquals(
                PayrollAgentFactory.skillFingerprint(emptySkillRepository(), ids),
                PayrollAgentFactory.skillFingerprint(emptySkillRepository(), ids));
        assertTrue(
                PayrollAgentFactory.skillFingerprint(emptySkillRepository(), ids)
                        .contains("not-published:missing"));
    }

    /** 只暴露一个技能的最小仓库视图：不是目录仓库，走按技能装配的读取路径。 */
    private static AgentSkillRepository skillRepository(String path, String content) {
        AgentSkill skill =
                AgentSkill.builder()
                        .name(RoleSkills.CALCULATOR)
                        .description("计算角色")
                        .skillContent("## 角色\n按规范执行计算。")
                        .source("test")
                        .addResource(path, content)
                        .build();
        return new SingleSkillRepository(skill);
    }

    private static AgentSkillRepository emptySkillRepository() {
        AgentSkill skill =
                AgentSkill.builder()
                        .name(RoleSkills.CALCULATOR)
                        .description("计算角色")
                        .skillContent("## 角色\n按规范执行计算。")
                        .source("test")
                        .build();
        return new SingleSkillRepository(skill);
    }

    private static final class SingleSkillRepository implements AgentSkillRepository {

        private final AgentSkill skill;

        private SingleSkillRepository(AgentSkill skill) {
            this.skill = skill;
        }

        @Override
        public AgentSkill getSkill(String name) {
            return skill != null && skill.getName().equals(name) ? skill : null;
        }

        @Override
        public List<String> getAllSkillNames() {
            return skill == null ? List.of() : List.of(skill.getName());
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            return skill == null ? List.of() : List.of(skill);
        }

        @Override
        public boolean save(List<AgentSkill> skills, boolean overwrite) {
            return false;
        }

        @Override
        public boolean delete(String name) {
            return false;
        }

        @Override
        public boolean skillExists(String name) {
            return getSkill(name) != null;
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo("test", "内存", false);
        }

        @Override
        public String getSource() {
            return "test";
        }

        @Override
        public void setWriteable(boolean writeable) {
        }

        @Override
        public boolean isWriteable() {
            return false;
        }

        @Override
        public void close() {
        }
    }

    private static AgentDefinition definition(
            String id, String name, String description, List<String> skillIds) {
        return new AgentDefinition(
                id,
                name,
                "avatar",
                description,
                List.of(),
                skillIds,
                AgentDefinition.VISIBILITY_PUBLIC,
                AgentDefinition.STATUS_ONLINE,
                "tester",
                LocalDateTime.now(),
                LocalDateTime.now());
    }
}
