package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 概览页的质量评审：与保存门禁同一份规则，但逐条列出而不是只抛第一条。 */
class SkillQualityTest {

    private static final String GOOD_BODY =
            "## 角色\n"
                    + "规则说明。".repeat(60)
                    + "\n## 输出要求\n"
                    + "按模板输出结论。".repeat(20);

    @Test
    @DisplayName("结构化正文零 error，passes 为真")
    void structuredBodyPasses() {
        assertTrue(SkillQuality.review("薪酬审计", GOOD_BODY).isEmpty());
        assertTrue(SkillQuality.passes("薪酬审计", GOOD_BODY));
    }

    @Test
    @DisplayName("过短正文报 length 且文案带下限")
    void shortBodyReportsLength() {
        List<SkillQuality.Finding> findings = SkillQuality.review("薪酬审计", "太短");

        assertEquals("length", findings.get(0).rule());
        assertTrue(findings.get(0).message().contains("200"), findings.get(0).message());
        assertTrue(findings.get(0).error());
        assertFalse(findings.get(0).hint().isBlank());
    }

    @Test
    @DisplayName("空正文只报 required，不再堆叠长度与结构问题")
    void blankBodyReportsRequiredOnly() {
        List<SkillQuality.Finding> findings = SkillQuality.review("薪酬审计", "   ");

        assertEquals(1, findings.size());
        assertEquals("required", findings.get(0).rule());
    }

    @Test
    @DisplayName("缺少 Markdown 标题时只报 heading")
    void missingHeadingReported() {
        String body = "角色说明。".repeat(60);

        List<SkillQuality.Finding> findings = SkillQuality.review("薪酬审计", body);

        assertEquals(List.of("heading"), rules(findings));
    }

    @Test
    @DisplayName("缺少角色关键字时只报 role")
    void missingRoleKeywordReported() {
        String body =
                "## 概述\n"
                        + "本文描述员工档案的读取、核对与整理过程，覆盖数据来源、校验方式与归档位置。".repeat(6);

        List<SkillQuality.Finding> findings = SkillQuality.review("薪酬审计", body);

        assertEquals(List.of("role"), rules(findings));
    }

    @Test
    @DisplayName("描述超长报 description，正文不受影响")
    void longDescriptionReported() {
        String description = "描".repeat(SkillContentRules.MAX_DESCRIPTION_LENGTH + 1);

        List<SkillQuality.Finding> findings = SkillQuality.review(description, GOOD_BODY);

        assertEquals(List.of("description"), rules(findings));
        assertTrue(findings.get(0).message().contains("200"), findings.get(0).message());
    }

    private static List<String> rules(List<SkillQuality.Finding> findings) {
        return findings.stream().map(SkillQuality.Finding::rule).toList();
    }
}
