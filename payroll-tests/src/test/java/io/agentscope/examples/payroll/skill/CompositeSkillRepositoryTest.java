package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CompositeSkillRepositoryTest {

    @Test
    @DisplayName("Nacos 技能覆盖同名内置技能")
    void nacosWins() {
        NacosSkillRepository nacos =
                nacos(Map.of("payroll-calculator.md", "## 角色\nNacos 覆盖版本。"));
        CompositeSkillRepository composite =
                new CompositeSkillRepository(List.of(nacos, new BuiltInSkillRepository()));

        AgentSkill skill = composite.getSkill(RoleSkills.CALCULATOR);

        assertNotNull(skill);
        assertEquals(NacosSkillRepository.SOURCE, skill.getSource());
        assertTrue(skill.getSkillContent().contains("Nacos 覆盖版本"));
    }

    @Test
    @DisplayName("Nacos 缺少的技能回退内置版本，结果按名称去重")
    void fallsBackPerSkill() {
        NacosSkillRepository nacos =
                nacos(Map.of("payroll-calculator.md", "## 角色\nNacos 覆盖版本。"));
        CompositeSkillRepository composite =
                new CompositeSkillRepository(List.of(nacos, new BuiltInSkillRepository()));

        AgentSkill validator = composite.getSkill(RoleSkills.VALIDATOR);

        assertNotNull(validator);
        assertEquals(BuiltInSkillRepository.SOURCE, validator.getSource());
        List<AgentSkill> skills = composite.getAllSkills();
        assertEquals(RoleSkills.ids().size(), skills.size());
        assertEquals(
                RoleSkills.ids(),
                skills.stream().map(AgentSkill::getName).toList());
    }

    @Test
    @DisplayName("组合仓库只读并支持关闭全部来源")
    void readOnlyAndClosable() {
        CompositeSkillRepository composite =
                new CompositeSkillRepository(
                        List.of(nacos(Map.of()), new BuiltInSkillRepository()));

        assertFalse(composite.isWriteable());
        assertFalse(composite.save(List.of(), true));
        assertFalse(composite.delete(RoleSkills.CALCULATOR));
        assertEquals("composite", composite.getRepositoryInfo().getType());
        composite.close();
    }

    @Test
    @DisplayName("首选源不可用时由下一个来源供货，四条读路径都不空")
    void unavailableSourceIsSkippedOnEveryReadPath() {
        CompositeSkillRepository composite =
                new CompositeSkillRepository(
                        List.of(new UnavailableRepository("db"), new BuiltInSkillRepository()));

        assertEquals(
                BuiltInSkillRepository.SOURCE,
                composite.getSkill(RoleSkills.CALCULATOR).getSource());
        assertEquals(
                BuiltInSkillRepository.SOURCE,
                composite.manifest(RoleSkills.VALIDATOR).orElseThrow().source());
        assertEquals(RoleSkills.ids(), ids(composite.listManifests()));
        assertEquals(RoleSkills.ids(), composite.getAllSkillNames());
        // nameById 只供工具组激活中间件查表，契约不含顺序，因此按集合比。
        assertEquals(Set.copyOf(RoleSkills.ids()), composite.nameById().keySet());
        assertEquals(RoleSkills.ids().size(), composite.getSkills(RoleSkills.ids()).size());
        assertTrue(composite.skillExists(RoleSkills.CALCULATOR));
    }

    @Test
    @DisplayName("跳过不可用来源必须计数：单条记供货方，整表记 composite，无人兜住记 none")
    void skippedSourceIncrementsFallbackCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.globalRegistry.add(registry);
        try {
            UnavailableRepository db = new UnavailableRepository("db");
            CompositeSkillRepository composite =
                    new CompositeSkillRepository(List.of(db, new BuiltInSkillRepository()));

            long toBuiltin = fallbackCount(registry, "builtin");
            composite.getSkill(RoleSkills.CALCULATOR);
            assertEquals(
                    toBuiltin + 1,
                    fallbackCount(registry, "builtin"),
                    "单条读取得指名是哪一个来源顶上的");

            long toMerged = fallbackCount(registry, "composite");
            composite.listManifests();
            assertEquals(
                    toMerged + 1,
                    fallbackCount(registry, "composite"),
                    "整表合并说不出供货方，只能记 composite");

            long toNone = fallbackCount(registry, "none");
            assertNull(new CompositeSkillRepository(List.of(db)).getSkill(RoleSkills.CALCULATOR));
            assertEquals(
                    toNone + 1, fallbackCount(registry, "none"), "没有任何来源兜住时不能悄悄返回空");
        } finally {
            Metrics.globalRegistry.remove(registry);
        }
    }

    @Test
    @DisplayName("技能内容坏了不是「源不可用」：原样抛出，不伪装成没这个技能")
    void corruptSkillStillPropagates() {
        CompositeSkillRepository composite =
                new CompositeSkillRepository(List.of(new CorruptRepository(), new BuiltInSkillRepository()));

        assertThrows(PayrollException.class, () -> composite.getSkill(RoleSkills.CALCULATOR));
    }

    private static List<String> ids(List<SkillManifest> manifests) {
        return manifests.stream().map(SkillManifest::id).toList();
    }

    private static long fallbackCount(SimpleMeterRegistry registry, String to) {
        Counter counter = registry.find(SkillMetrics.SOURCE_FALLBACK).tag("to", to).counter();
        return counter == null ? 0L : (long) counter.count();
    }

    private static NacosSkillRepository nacos(Map<String, String> configs) {
        NacosSkillSettings settings =
                new NacosSkillSettings(
                        true,
                        "http://127.0.0.1:8848",
                        "payroll-agent",
                        "",
                        "",
                        "",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(60));
        return new NacosSkillRepository(
                settings,
                dataId -> Optional.ofNullable(configs.get(dataId)),
                () -> 1_000L);
    }

    /**
     * 「连不上」的仓库：每条读路径都抛 {@link PayrollUnavailableException}。
     *
     * <p>同时实现 {@link PayrollSkillCatalog}，这样目录、批量、单条三条路径都会经过降级闸门。
     */
    private static final class UnavailableRepository
            implements AgentSkillRepository, PayrollSkillCatalog {

        private final String source;

        private UnavailableRepository(String source) {
            this.source = source;
        }

        @Override
        public AgentSkill getSkill(String name) {
            throw unavailable();
        }

        @Override
        public List<String> getAllSkillNames() {
            throw unavailable();
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            throw unavailable();
        }

        @Override
        public List<SkillManifest> listManifests() {
            throw unavailable();
        }

        @Override
        public List<AgentSkill> getSkills(Collection<String> ids) {
            throw unavailable();
        }

        @Override
        public Optional<SkillManifest> manifest(String id) {
            throw unavailable();
        }

        @Override
        public Map<String, String> nameById() {
            throw unavailable();
        }

        @Override
        public boolean skillExists(String name) {
            throw unavailable();
        }

        @Override
        public boolean save(List<AgentSkill> skills, boolean overwrite) {
            throw unavailable();
        }

        @Override
        public boolean delete(String name) {
            throw unavailable();
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo(source, "unavailable", false);
        }

        @Override
        public String getSource() {
            return source;
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

        private PayrollUnavailableException unavailable() {
            return new PayrollUnavailableException("连接超时");
        }
    }

    /** 「存坏了」的仓库：抛普通 {@link PayrollException}，必须原样穿透而不是当成连不上。 */
    private static final class CorruptRepository implements AgentSkillRepository {

        @Override
        public AgentSkill getSkill(String name) {
            throw new PayrollException("技能正文缺少角色段");
        }

        @Override
        public List<String> getAllSkillNames() {
            return RoleSkills.ids();
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            throw new PayrollException("技能正文缺少角色段");
        }

        @Override
        public boolean skillExists(String name) {
            return true;
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
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo("corrupt", "corrupt", false);
        }

        @Override
        public String getSource() {
            return "corrupt";
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
}
