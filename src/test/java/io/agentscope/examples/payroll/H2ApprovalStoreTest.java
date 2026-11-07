package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2ApprovalStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.ApprovalFlow;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class H2ApprovalStoreTest {

    private H2ApprovalStore store;

    @BeforeEach
    void setUp() {
        store = new H2ApprovalStore(PayrollDatabase.inMemory("flow-" + UUID.randomUUID()));
    }

    @Test
    @DisplayName("启动时预置全局审批链，覆盖任意范围与金额")
    void seedsDefaultGlobalFlow() {
        assertEquals(1, store.flows().size());

        ApprovalFlow selected =
                store.selectFlow("研发部", new BigDecimal("128747.22")).orElseThrow();

        assertEquals("默认审批链", selected.name());
        assertEquals(ApprovalFlow.GLOBAL, selected.scopeType());
    }

    @Test
    @DisplayName("部门审批链优先于优先级更高的全局审批链")
    void prefersDepartmentFlowOverGlobal() {
        store.createFlow(draft("研发部审批链", "DEPARTMENT", "研发部", "0", null, 10));

        assertEquals(
                "研发部审批链",
                store.selectFlow("研发部", new BigDecimal("100")).orElseThrow().name());
        assertEquals(
                "默认审批链",
                store.selectFlow("财务部", new BigDecimal("100")).orElseThrow().name());
    }

    @Test
    @DisplayName("金额不在区间内时跳过该审批链")
    void respectsAmountRange() {
        store.createFlow(
                draft("研发部大额审批链", "DEPARTMENT", "研发部", "500000", null, 10));

        assertEquals(
                "默认审批链",
                store.selectFlow("研发部", new BigDecimal("128747.22"))
                        .orElseThrow()
                        .name());

        ApprovalFlow flow = store.flows().stream()
                .filter(item -> "研发部大额审批链".equals(item.name()))
                .findFirst()
                .orElseThrow();
        store.updateFlow(
                flow.flowId(),
                draft("研发部大额审批链", "DEPARTMENT", "研发部", "0", null, 10));

        assertEquals(
                "研发部大额审批链",
                store.selectFlow("研发部", new BigDecimal("128747.22"))
                        .orElseThrow()
                        .name());
    }

    @Test
    @DisplayName("同一范围只能有一条生效审批链，禁用后释放冲突")
    void enforcesSingleActiveFlowPerScope() {
        PayrollException conflict =
                assertThrows(
                        PayrollException.class,
                        () -> store.createFlow(draft("全局二号", "GLOBAL", null, "0", null, 50)));
        assertTrue(conflict.getMessage().contains("已存在生效审批链"), conflict.getMessage());

        ApprovalFlow disabled =
                store.createFlow(
                        new H2ApprovalStore.FlowDraft(
                                "全局备用",
                                "GLOBAL",
                                null,
                                BigDecimal.ZERO,
                                null,
                                50,
                                false,
                                ""));
        PayrollException updateConflict =
                assertThrows(
                        PayrollException.class,
                        () ->
                                store.updateFlow(
                                        disabled.flowId(),
                                        new H2ApprovalStore.FlowDraft(
                                                "全局备用",
                                                "GLOBAL",
                                                null,
                                                BigDecimal.ZERO,
                                                null,
                                                50,
                                                true,
                                                "")));
        assertTrue(updateConflict.getMessage().contains("已存在生效审批链"));
    }

    @Test
    @DisplayName("审批链配置校验名称、范围、部门与金额区间")
    void validatesFlowDraft() {
        assertTrue(
                assertThrows(
                                PayrollException.class,
                                () -> store.createFlow(draft("", "GLOBAL", null, "0", null, 10)))
                        .getMessage()
                        .contains("名称"));

        assertTrue(
                assertThrows(
                                PayrollException.class,
                                () -> store.createFlow(draft("错误范围", "TEAM", null, "0", null, 10)))
                        .getMessage()
                        .contains("GLOBAL"));

        assertTrue(
                assertThrows(
                                PayrollException.class,
                                () ->
                                        store.createFlow(
                                                draft("不存在部门", "DEPARTMENT", "星际部", "0", null, 10)))
                        .getMessage()
                        .contains("部门不存在"));

        assertTrue(
                assertThrows(
                                PayrollException.class,
                                () ->
                                        store.createFlow(
                                                draft("区间错误", "DEPARTMENT", "研发部", "500", "100", 10)))
                        .getMessage()
                        .contains("金额上限"));
    }

    private static H2ApprovalStore.FlowDraft draft(
            String name,
            String scopeType,
            String scopeValue,
            String minAmount,
            String maxAmount,
            int priority) {
        return new H2ApprovalStore.FlowDraft(
                name,
                scopeType,
                scopeValue,
                minAmount == null ? null : new BigDecimal(minAmount),
                maxAmount == null ? null : new BigDecimal(maxAmount),
                priority,
                true,
                "");
    }
}
