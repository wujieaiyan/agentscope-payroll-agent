package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 内置模板是代码资源：顺序、frontmatter 与「正文能过质量门」都由单测钉住。 */
class SkillTemplatesTest {

    private final SkillTemplates templates = new SkillTemplates();

    @Test
    @DisplayName("按固定顺序加载四篇模板，frontmatter 字段齐全")
    void loadsFourTemplatesInOrder() {
        List<SkillTemplates.Template> list = templates.list();

        assertEquals(
                List.of("payroll-assistant", "kb-qa-assistant", "policy-note", "data-reconciler"),
                list.stream().map(SkillTemplates.Template::id).toList());
        for (SkillTemplates.Template template : list) {
            assertFalse(template.title().isBlank(), template.id());
            assertFalse(template.description().isBlank(), template.id());
            assertFalse(template.category().isBlank(), template.id());
            assertTrue(template.bodyLength() > 0, template.id());
        }
    }

    @Test
    @DisplayName("每篇模板的正文都能过保存门禁")
    void everyTemplatePassesQualityGate() {
        for (SkillTemplates.Template template : templates.list()) {
            List<SkillQuality.Finding> findings =
                    SkillQuality.review(template.description(), template.body());
            assertTrue(findings.isEmpty(), template.id() + " -> " + findings);
        }
    }

    @Test
    @DisplayName("未知模板报明确错误，查找允许首尾空白")
    void requireRejectsUnknownAndFindTrims() {
        assertEquals("policy-note", templates.require(" policy-note ").id());
        assertNull(templates.find(null));

        PayrollException error =
                assertThrows(PayrollException.class, () -> templates.require("missing-template"));
        assertTrue(error.getMessage().contains("missing-template"), error.getMessage());
    }

    @Test
    @DisplayName("正文以换行结尾，写进技能后与手工创建一致")
    void bodyEndsWithNewline() {
        assertNotNull(templates.require("policy-note").body());
        assertTrue(templates.require("policy-note").body().endsWith("\n"));
    }
}
