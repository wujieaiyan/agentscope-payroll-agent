package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry.SkillDetail;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry.SkillSummary;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NacosSkillRegistryTest {

    private FakeNacosServer nacos;

    @AfterEach
    void stopServer() {
        if (nacos != null) {
            nacos.close();
        }
    }

    @Test
    @DisplayName("Nacos 为空时列出三个内置技能，可写且显示存储位置")
    void listsBuiltinsWhenNacosEmpty() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true);

        List<SkillSummary> skills = registry.list();

        assertEquals(RoleSkills.ids(), skills.stream().map(SkillSummary::id).toList());
        assertTrue(skills.stream().allMatch(SkillSummary::builtin));
        assertTrue(skills.stream().allMatch(skill -> "builtin".equals(skill.source())));
        assertTrue(registry.writable());
        assertTrue(registry.location().contains("payroll-agent"), registry.location());
    }

    @Test
    @DisplayName("新增技能写入 Nacos Markdown，列表与详情可重新读取")
    void createsCustomSkill() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true);

        SkillSummary saved =
                registry.create("payroll-auditor", "薪酬审计角色", "## 角色\n独立审计工资数据。");

        assertEquals("payroll-auditor", saved.id());
        assertEquals("nacos", saved.source());
        assertFalse(saved.builtin());
        String raw = nacos.configs.get("payroll-auditor.md");
        assertTrue(raw.startsWith("---"), raw);
        assertTrue(raw.contains("name: payroll-auditor"), raw);
        assertTrue(raw.contains("description: \"薪酬审计角色\""), raw);

        List<SkillSummary> skills = registry.list();
        assertEquals(RoleSkills.ids().size() + 1, skills.size());
        SkillSummary custom =
                skills.stream()
                        .filter(skill -> skill.id().equals("payroll-auditor"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("薪酬审计角色", custom.description());
        assertFalse(custom.builtin());

        SkillDetail detail = registry.detail("payroll-auditor");
        assertEquals("薪酬审计角色", detail.description());
        assertTrue(detail.content().contains("独立审计工资数据"), detail.content());
        assertFalse(detail.content().contains("description:"), detail.content());
        assertEquals("nacos", detail.source());
    }

    @Test
    @DisplayName("编辑器查详情时返回可再次保存的正文")
    void detailForBuiltinOmitsFrontmatter() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true);

        SkillDetail detail = registry.detail(RoleSkills.CALCULATOR);

        assertEquals(RoleSkills.CALCULATOR, detail.id());
        assertEquals("builtin", detail.source());
        assertTrue(detail.builtin());
        assertEquals(RoleSkills.skill(RoleSkills.CALCULATOR).getSkillContent(), detail.content());
    }

    @Test
    @DisplayName("编辑内置技能生成 Nacos 覆盖，删除后回退内置提示词")
    void overridesAndFallsBackToBuiltin() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true);

        registry.update(RoleSkills.CALCULATOR, "覆盖版计算角色", "## 角色\n来自 Nacos 的覆盖版。");
        SkillSummary overridden =
                registry.list().stream()
                        .filter(skill -> skill.id().equals(RoleSkills.CALCULATOR))
                        .findFirst()
                        .orElseThrow();
        assertEquals("nacos", overridden.source());
        assertTrue(overridden.builtin());
        assertTrue(overridden.overridden());

        assertTrue(registry.delete(RoleSkills.CALCULATOR));
        SkillDetail fallback = registry.detail(RoleSkills.CALCULATOR);
        assertEquals("builtin", fallback.source());
        assertTrue(fallback.content().contains("薪酬计算智能体"), fallback.content());

        PayrollException error =
                assertThrows(
                        PayrollException.class, () -> registry.delete(RoleSkills.CALCULATOR));
        assertTrue(error.getMessage().contains("内置技能"), error.getMessage());
    }

    @Test
    @DisplayName("校验技能 ID、重复创建、缺失更新与正文长度")
    void validatesInput() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true);

        PayrollException badId =
                assertThrows(
                        PayrollException.class,
                        () -> registry.create("Bad ID", "", "正文"));
        assertTrue(badId.getMessage().contains("技能 ID"), badId.getMessage());

        PayrollException duplicate =
                assertThrows(
                        PayrollException.class,
                        () -> registry.create(RoleSkills.CALCULATOR, "", "正文"));
        assertTrue(duplicate.getMessage().contains("已存在"), duplicate.getMessage());

        PayrollException missing =
                assertThrows(
                        PayrollException.class,
                        () -> registry.update("no-such-skill", "", "正文"));
        assertTrue(missing.getMessage().contains("不存在"), missing.getMessage());

        PayrollException blank =
                assertThrows(
                        PayrollException.class,
                        () -> registry.create("payroll-auditor", "", "   "));
        assertTrue(blank.getMessage().contains("正文"), blank.getMessage());

        PayrollException longDescription =
                assertThrows(
                        PayrollException.class,
                        () ->
                                registry.create(
                                        "payroll-auditor", "x".repeat(201), "正文"));
        assertTrue(longDescription.getMessage().contains("描述"), longDescription.getMessage());

        PayrollException longContent =
                assertThrows(
                        PayrollException.class,
                        () ->
                                registry.create(
                                        "payroll-auditor", "", "x".repeat(200_001)));
        assertTrue(longContent.getMessage().contains("200000"), longContent.getMessage());

        assertTrue(nacos.configs.isEmpty());
    }

    @Test
    @DisplayName("TTL 内复用技能列表，不重复查询 Nacos")
    void cachesListWithinTtl() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true);

        registry.list();
        registry.list();

        assertEquals(1, nacos.reads.get());
    }

    @Test
    @DisplayName("Nacos 未启用时管理能力只读")
    void disabledIsReadOnly() {
        NacosSkillSettings settings =
                new NacosSkillSettings(
                        false,
                        "http://127.0.0.1:8848",
                        "payroll-agent",
                        "",
                        "",
                        "",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(60));
        NacosSkillRegistry registry = new NacosSkillRegistry(settings);

        assertFalse(registry.writable());
        assertEquals("代码内置", registry.location());
        assertEquals(RoleSkills.ids().size(), registry.list().size());
        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> registry.create("payroll-auditor", "", "正文"));
        assertTrue(error.getMessage().contains("Nacos 未启用"), error.getMessage());
    }

    private NacosSkillRegistry registry(boolean enabled) {
        NacosSkillSettings settings =
                new NacosSkillSettings(
                        enabled,
                        nacos.url(),
                        "payroll-agent",
                        "",
                        "",
                        "",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(60));
        return new NacosSkillRegistry(settings);
    }
}
