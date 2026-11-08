package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NacosSkillRepositoryTest {

    private static final String CALCULATOR_MD =
            """
            ---
            name: payroll-calculator
            description: Nacos 版计算角色
            ---
            ## 角色
            按 Nacos 规范执行计算。
            """;

    private static final String AUDITOR_MD =
            """
            ---
            name: payroll-auditor
            description: 薪酬审计角色
            ---
            ## 职责
            独立审计工资数据。
            """;

    @Test
    @DisplayName("解析 Nacos Markdown 的 frontmatter 与正文")
    void parsesFrontmatter() {
        NacosSkillRepository repository =
                repository(Map.of("payroll-calculator.md", CALCULATOR_MD), clock());

        AgentSkill skill = repository.getSkill(RoleSkills.CALCULATOR);

        assertNotNull(skill);
        assertEquals(RoleSkills.CALCULATOR, skill.getName());
        assertEquals("Nacos 版计算角色", skill.getDescription());
        assertTrue(skill.getSkillContent().contains("按 Nacos 规范执行计算"));
        assertFalse(skill.getSkillContent().contains("description:"));
        assertEquals(NacosSkillRepository.SOURCE, skill.getSource());
        assertEquals("payroll-calculator_nacos", skill.getSkillId());
    }

    @Test
    @DisplayName("缺少 frontmatter 时按纯文本使用并回退内置描述")
    void fallsBackToBuiltInDescription() {
        NacosSkillRepository repository =
                repository(Map.of("payroll-validator.md", "## 角色\n只做复核。"), clock());

        AgentSkill skill = repository.getSkill(RoleSkills.VALIDATOR);

        assertNotNull(skill);
        assertEquals(RoleSkills.description(RoleSkills.VALIDATOR), skill.getDescription());
        assertTrue(skill.getSkillContent().contains("只做复核"));
    }

    @Test
    @DisplayName("TTL 内复用缓存，不重复读取 Nacos")
    void cachesWithinTtl() {
        Map<String, String> configs = allConfigs();
        AtomicInteger calls = new AtomicInteger();
        NacosSkillRepository repository = repository(configs, clock(), ttl(), calls);

        repository.getAllSkills();
        repository.getAllSkills();
        repository.getSkill(RoleSkills.CALCULATOR);

        assertEquals(RoleSkills.ids().size(), calls.get());
    }

    @Test
    @DisplayName("TTL 过期后重新读取，内容变化可生效")
    void refreshesAfterTtl() {
        Map<String, String> configs = new HashMap<>(allConfigs());
        AtomicLong now = new AtomicLong(1_000);
        AtomicInteger calls = new AtomicInteger();
        NacosSkillRepository repository =
                repository(configs, now::get, Duration.ofSeconds(60), calls);

        assertEquals("Nacos 版计算角色", repository.getSkill(RoleSkills.CALCULATOR).getDescription());

        configs.put("payroll-calculator.md", CALCULATOR_MD.replace("Nacos 版计算角色", "更新后的计算角色"));
        now.addAndGet(61_000);

        assertEquals("更新后的计算角色", repository.getSkill(RoleSkills.CALCULATOR).getDescription());
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("Nacos 缺少的单个技能被跳过，由组合仓库回退")
    void skipsMissingSkill() {
        Map<String, String> configs = new HashMap<>(allConfigs());
        configs.remove("payroll-validator.md");
        AtomicInteger calls = new AtomicInteger();
        NacosSkillRepository repository = repository(configs, clock(), ttl(), calls);

        assertEquals(
                List.of(RoleSkills.CALCULATOR, RoleSkills.ACCOUNTANT), repository.getAllSkillNames());
        assertNull(repository.getSkill(RoleSkills.VALIDATOR));
        assertNull(repository.getSkill("not-a-role"));
        assertEquals(3, calls.get(), "未知技能不应触发 Nacos 请求");
    }

    @Test
    @DisplayName("发现内置之外的 Nacos 技能，供智能体绑定")
    void discoversAdditionalNacosSkills() {
        Map<String, String> configs = new HashMap<>(allConfigs());
        String custom =
                """
                ---
                name: payroll-auditor
                description: 薪酬审计角色
                ---
                ## 职责
                独立审计工资数据。
                """;
        configs.put("payroll-auditor.md", custom);
        NacosSkillSettings settings = settings(ttl());
        NacosSkillRepository repository =
                new NacosSkillRepository(
                        settings,
                        dataId -> Optional.ofNullable(configs.get(dataId)),
                        pattern -> configs.entrySet().stream()
                                .map(entry -> new NacosConfigClient.ConfigEntry(
                                        entry.getKey(), entry.getValue(), 1L))
                                .toList(),
                        clock()::get);

        List<String> names = repository.getAllSkillNames();
        assertTrue(names.contains("payroll-auditor"), names.toString());

        AgentSkill skill = repository.getSkill("payroll-auditor");
        assertNotNull(skill);
        assertEquals("薪酬审计角色", skill.getDescription());
        assertTrue(skill.getSkillContent().contains("独立审计工资数据"));
        assertEquals(NacosSkillRepository.SOURCE, skill.getSource());
    }

    @Test
    @DisplayName("技能列表读取失败时仅保留内置技能")
    void listFailureFallsBackToBuiltins() {
        Map<String, String> configs = allConfigs();
        NacosSkillSettings settings = settings(ttl());
        NacosSkillRepository repository =
                new NacosSkillRepository(
                        settings,
                        dataId -> Optional.ofNullable(configs.get(dataId)),
                        pattern -> {
                            throw new IllegalStateException("nacos down");
                        },
                        clock()::get);

        assertEquals(RoleSkills.ids(), repository.getAllSkillNames());
    }

    @Test
    @DisplayName("列表失败时沿用上次成功快照，Nacos 自定义技能不消失")
    void listFailureKeepsLastKnownGood() {
        Map<String, String> configs = new HashMap<>(allConfigs());
        configs.put("payroll-auditor.md", AUDITOR_MD);
        AtomicBoolean failing = new AtomicBoolean();
        AtomicLong now = new AtomicLong(1_000);
        AtomicInteger listCalls = new AtomicInteger();
        NacosSkillRepository repository =
                new NacosSkillRepository(
                        settings(ttl()),
                        dataId -> Optional.ofNullable(configs.get(dataId)),
                        pattern -> {
                            listCalls.incrementAndGet();
                            if (failing.get()) {
                                throw new IllegalStateException("nacos down");
                            }
                            return entries(configs);
                        },
                        now::get);

        assertTrue(repository.getAllSkillNames().contains("payroll-auditor"));

        failing.set(true);
        now.addAndGet(61_000);

        assertTrue(
                repository.getAllSkillNames().contains("payroll-auditor"),
                "列表失败时应沿用上次成功快照，而不是退回纯内置清单");
        assertNotNull(repository.getSkill("payroll-auditor"));

        repository.getAllSkillNames();
        assertEquals(2, listCalls.get(), "失败后按 TTL 退避，不应每次调用都请求 Nacos");
    }

    @Test
    @DisplayName("单个技能拉取异常时沿用上次成功内容")
    void fetchFailureKeepsLastKnownGoodSkill() {
        Map<String, String> configs = new HashMap<>(allConfigs());
        AtomicBoolean failing = new AtomicBoolean();
        AtomicLong now = new AtomicLong(1_000);
        NacosSkillRepository repository =
                new NacosSkillRepository(
                        settings(ttl()),
                        dataId -> {
                            if (failing.get()) {
                                throw new IllegalStateException("read timeout");
                            }
                            return Optional.ofNullable(configs.get(dataId));
                        },
                        pattern -> entries(configs),
                        now::get);

        AgentSkill first = repository.getSkill(RoleSkills.CALCULATOR);
        assertNotNull(first);

        failing.set(true);
        now.addAndGet(61_000);

        AgentSkill second = repository.getSkill(RoleSkills.CALCULATOR);
        assertNotNull(second, "读取异常不应让已加载技能消失");
        assertEquals(first.getSkillContent(), second.getSkillContent());
    }

    @Test
    @DisplayName("只读仓库不提供写入能力")
    void isReadOnly() {
        NacosSkillRepository repository = repository(allConfigs(), clock());

        assertFalse(repository.isWriteable());
        assertFalse(repository.save(List.of(), true));
        assertFalse(repository.delete(RoleSkills.CALCULATOR));
        assertEquals("nacos", repository.getRepositoryInfo().getType());
        assertSame(NacosSkillRepository.SOURCE, repository.getSource());
    }

    private static NacosSkillRepository repository(Map<String, String> configs) {
        return repository(configs, clock());
    }

    private static NacosSkillRepository repository(Map<String, String> configs, AtomicLong clock) {
        AtomicInteger ignored = new AtomicInteger();
        return repository(configs, clock::get, ttl(), ignored);
    }

    private static NacosSkillRepository repository(
            Map<String, String> configs, AtomicLong clock, Duration ttl, AtomicInteger calls) {
        return repository(configs, clock::get, ttl, calls);
    }

    private static NacosSkillRepository repository(
            Map<String, String> configs,
            java.util.function.LongSupplier clock,
            Duration ttl,
            AtomicInteger calls) {
        NacosSkillSettings settings = settings(ttl);
        NacosSkillRepository.ConfigFetcher fetcher =
                dataId -> {
                    calls.incrementAndGet();
                    return Optional.ofNullable(configs.get(dataId));
                };
        return new NacosSkillRepository(settings, fetcher, clock);
    }

    private static NacosSkillSettings settings(Duration ttl) {
        return new NacosSkillSettings(
                true,
                "http://127.0.0.1:8848",
                "payroll-agent",
                "",
                "",
                "",
                Duration.ofSeconds(1),
                ttl);
    }

    private static Duration ttl() {
        return Duration.ofSeconds(60);
    }

    private static AtomicLong clock() {
        return new AtomicLong(1_000);
    }

    private static Map<String, String> allConfigs() {
        Map<String, String> configs = new HashMap<>();
        configs.put("payroll-calculator.md", CALCULATOR_MD);
        configs.put("payroll-validator.md", "## 角色\n只做复核。");
        configs.put("payroll-accountant.md", "## 角色\n只做汇总。");
        return configs;
    }

    private static List<NacosConfigClient.ConfigEntry> entries(Map<String, String> configs) {
        return configs.entrySet().stream()
                .map(
                        entry ->
                                new NacosConfigClient.ConfigEntry(
                                        entry.getKey(), entry.getValue(), 1L))
                .toList();
    }
}
