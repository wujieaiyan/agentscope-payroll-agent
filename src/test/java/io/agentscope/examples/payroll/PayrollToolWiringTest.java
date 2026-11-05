package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.payroll.agent.PayrollTools;
import io.agentscope.examples.payroll.domain.PayrollPolicy;
import io.agentscope.examples.payroll.repository.JsonPayrollRepository;
import io.agentscope.examples.payroll.service.PayrollEngine;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayrollToolWiringTest {

    @Test
    @DisplayName("Toolkit 能通过反射注册全部工具并生成 schema")
    void registersAllTools() {
        PayrollTools tools =
                new PayrollTools(
                        new PayrollEngine(
                                JsonPayrollRepository.demo(), PayrollPolicy.demoPolicy()));
        Toolkit toolkit = new Toolkit();

        toolkit.registerTool(tools);

        assertEquals(
                Set.of(
                        "list_employees",
                        "get_employee_profile",
                        "get_attendance_summary",
                        "calculate_payroll",
                        "calculate_department_payroll",
                        "validate_payroll",
                        "generate_payslip",
                        "get_payroll_policy"),
                toolkit.getToolNames());
        assertTrue(toolkit.getToolSchemas().stream()
                .allMatch(schema -> schema.getDescription() != null
                        && !schema.getDescription().isBlank()));
    }
}
