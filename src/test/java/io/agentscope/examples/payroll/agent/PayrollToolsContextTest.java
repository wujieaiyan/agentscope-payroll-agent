package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.examples.payroll.domain.PayrollPolicy;
import io.agentscope.examples.payroll.repository.JsonPayrollRepository;
import io.agentscope.examples.payroll.service.PayrollEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayrollToolsContextTest {

    private PayrollTools tools;
    private ToolTraceRecorder fallback;

    @BeforeEach
    void setUp() {
        fallback = new ToolTraceRecorder();
        tools =
                new PayrollTools(
                        new PayrollEngine(
                                JsonPayrollRepository.demo(), PayrollPolicy.demoPolicy()),
                        fallback);
    }

    @Test
    @DisplayName("工具追溯写入调用上下文，而不是构造时的共享实例")
    void recordsIntoCalleeContext() {
        ToolTraceRecorder scoped = new ToolTraceRecorder();
        RuntimeContext context = context("u1", "s1", scoped);

        String json = tools.calculatePayroll("EMP001", "2026-10", context);

        assertTrue(json.contains("\"status\":\"OK\""));
        assertEquals(1, scoped.pendingCount());
        assertEquals(0, fallback.pendingCount());
        ToolTraceRecorder.Entry entry = scoped.take("calculate_payroll");
        assertNotNull(entry);
        assertTrue(entry.argsJson().contains("EMP001"));
        assertTrue(entry.amounts().containsKey("net_pay"));
        assertNull(scoped.take("calculate_payroll"));
    }

    @Test
    @DisplayName("不同会话的调用记录互不可见")
    void keepsContextsIsolated() {
        ToolTraceRecorder first = new ToolTraceRecorder();
        ToolTraceRecorder second = new ToolTraceRecorder();

        tools.calculatePayroll("EMP001", "2026-10", context("u1", "s1", first));
        tools.generatePayslip("EMP002", "2026-10", context("u2", "s2", second));

        assertEquals(1, first.pendingCount());
        assertEquals(1, second.pendingCount());
        assertNotNull(first.take("calculate_payroll"));
        assertNull(first.take("generate_payslip"));
        assertNotNull(second.take("generate_payslip"));
        assertEquals(0, fallback.pendingCount());
    }

    @Test
    @DisplayName("没有上下文时退回构造时传入的记录器")
    void fallsBackWhenContextMissing() {
        tools.generatePayslip("EMP001", "2026-10", null);

        assertEquals(1, fallback.pendingCount());
        assertNotNull(fallback.take("generate_payslip"));
    }

    @Test
    @DisplayName("上下文未携带记录器时同样退回构造时的记录器")
    void fallsBackWhenContextHasNoRecorder() {
        tools.listEmployees(null, RuntimeContext.builder().userId("u1").sessionId("s1").build());

        assertEquals(1, fallback.pendingCount());
        assertNotNull(fallback.take("list_employees"));
    }

    private static RuntimeContext context(
            String userId, String sessionId, ToolTraceRecorder recorder) {
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .put(ToolTraceRecorder.class, recorder)
                .build();
    }
}
