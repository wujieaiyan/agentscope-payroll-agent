package io.agentscope.examples.payroll.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.support.JsonSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TeamIntentTest {

    @Test
    @DisplayName("模型把数组字段输出成单个字符串时仍可解析")
    void acceptsSingleValueForListFields() {
        TeamIntent parsed =
                JsonSupport.fromJson(
                        """
                        {"task_type":"CALCULATE","employee_ids":"EMP001",
                         "employee_names":"张伟","departments":"研发部",
                         "required_capabilities":"PAYROLL_CALC","confidence":0.9}
                        """,
                        TeamIntent.class);

        assertEquals(List.of("EMP001"), parsed.employeeIds());
        assertEquals(List.of("张伟"), parsed.employeeNames());
        assertEquals(List.of("研发部"), parsed.departments());
        assertEquals(List.of(TeamIntent.CAP_PAYROLL_CALC), parsed.requiredCapabilities());
    }

    @Test
    @DisplayName("规则兜底识别核算任务、期间与工号")
    void rulesExtractCalculationIntent() {
        TeamIntent intent = TeamIntentExtractor.rules("帮我核算 EMP001 2026-10 的工资");

        assertEquals(TeamIntent.TASK_CALCULATE, intent.taskType());
        assertEquals("2026-10", intent.period());
        assertEquals(java.util.List.of("EMP001"), intent.employeeIds());
        assertTrue(intent.confidence() >= 0.6D);
        assertTrue(intent.needsValidation());
        assertFalse(intent.writeAction());
        assertTrue(intent.eligible());
    }

    @Test
    @DisplayName("写操作与无法归类的任务不进入动态组队")
    void writeActionAndOtherAreNotEligible() {
        TeamIntent write = TeamIntentExtractor.rules("确认发薪，帮我核算 EMP001 2026-10 的工资");
        assertTrue(write.writeAction());
        assertFalse(write.eligible());
        assertTrue(write.confidence() >= 0.6D);

        TeamIntent other = TeamIntentExtractor.rules("你好");
        assertEquals(TeamIntent.TASK_OTHER, other.taskType());
        assertFalse(other.eligible());
    }

    @Test
    @DisplayName("政策问题归类为知识问答")
    void policyQuestionIsKnowledgeTask() {
        TeamIntent intent = TeamIntentExtractor.rules("个税税率是怎么算的？");
        assertEquals(TeamIntent.TASK_POLICY_EXPLAIN, intent.taskType());
        assertTrue(intent.requiredCapabilities().contains(TeamIntent.CAP_POLICY_KNOWLEDGE));
    }

    @Test
    @DisplayName("模板按任务类型与所需能力选择")
    void templateSelectionFollowsIntent() {
        assertEquals(
                TeamTemplate.QUERY_THEN_CALC,
                TeamTemplate.select(intent(TeamIntent.TASK_CALCULATE, TeamIntent.CAP_PAYROLL_CALC, TeamIntent.CAP_PAYROLL_QUERY)));
        assertEquals(
                TeamTemplate.CALC_VALIDATE_POST,
                TeamTemplate.select(intent(TeamIntent.TASK_CALCULATE, TeamIntent.CAP_PAYROLL_CALC, TeamIntent.CAP_ACCOUNTING)));
        assertEquals(
                TeamTemplate.CALC_VALIDATE,
                TeamTemplate.select(intent(TeamIntent.TASK_CALCULATE, TeamIntent.CAP_PAYROLL_CALC)));
        assertEquals(
                TeamTemplate.QUERY_ONLY,
                TeamTemplate.select(intent(TeamIntent.TASK_PAYSLIP_QUERY, TeamIntent.CAP_PAYROLL_QUERY)));
        assertEquals(
                TeamTemplate.KNOWLEDGE_ONLY,
                TeamTemplate.select(intent(TeamIntent.TASK_POLICY_EXPLAIN, TeamIntent.CAP_POLICY_KNOWLEDGE)));
    }

    private static TeamIntent intent(String task, String... capabilities) {
        return new TeamIntent(
                task,
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                "2026-10",
                TeamIntent.RISK_MEDIUM,
                true,
                false,
                java.util.List.of(capabilities),
                0.9D,
                "test");
    }
}
