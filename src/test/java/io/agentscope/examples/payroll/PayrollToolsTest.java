package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.agent.PayrollTools;
import io.agentscope.examples.payroll.domain.PayrollPolicy;
import io.agentscope.examples.payroll.repository.JsonPayrollRepository;
import io.agentscope.examples.payroll.service.PayrollEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayrollToolsTest {

    private PayrollTools tools;

    @BeforeEach
    void setUp() {
        tools =
                new PayrollTools(
                        new PayrollEngine(
                                JsonPayrollRepository.demo(), PayrollPolicy.demoPolicy()));
    }

    @Test
    @DisplayName("工具返回结构化成功结果")
    void returnsStructuredSuccess() {
        String json = tools.calculatePayroll("EMP001", "2026-10", null);

        assertTrue(json.contains("\"status\":\"OK\""));
        assertTrue(json.contains("\"net_pay\":27661.39"));
        assertTrue(json.contains("\"employee_name\":\"张伟\""));
    }

    @Test
    @DisplayName("业务异常转成可解释错误而不是抛栈")
    void returnsStructuredError() {
        String json = tools.calculatePayroll("EMP999", "2026-10", null);

        assertTrue(json.contains("\"status\":\"ERROR\""));
        assertTrue(json.contains("未找到员工"));
    }

    @Test
    @DisplayName("唯一姓名可直接核算并解析为工号")
    void resolvesUniqueEmployeeName() {
        String json = tools.calculatePayroll("张伟", "2026-10", null);

        assertTrue(json.contains("\"status\":\"OK\""));
        assertTrue(json.contains("\"employee_id\":\"EMP001\""));
        assertTrue(json.contains("\"net_pay\":27661.39"));
    }

    @Test
    @DisplayName("期间格式错误可被模型理解并追问")
    void validatesPeriodFormat() {
        String json = tools.calculatePayroll("EMP001", "2026/10", null);

        assertTrue(json.contains("\"status\":\"ERROR\""));
        assertTrue(json.contains("YYYY-MM"));
    }

    @Test
    @DisplayName("工资条工具输出实发金额")
    void generatesPayslip() {
        String json = tools.generatePayslip("EMP001", "2026-10", null);

        assertTrue(json.contains("工资条"));
        assertTrue(json.contains("27,661.39"));
    }

    @Test
    @DisplayName("政策工具返回考勤就绪月份与税率表")
    void returnsPolicy() {
        String json = tools.getPayrollPolicy(null, null);

        assertTrue(json.contains("\"data_through_period\":\"2026-10\""));
        assertTrue(json.contains("\"tax_brackets\""));
        assertTrue(json.contains("累计预扣"));
    }

    @Test
    @DisplayName("政策工具可按城市查询")
    void returnsPolicyByCity() {
        String beijing = tools.getPayrollPolicy("北京", null);
        assertTrue(beijing.contains("\"city\":\"北京\""));
        assertTrue(beijing.contains("\"status\":\"OK\""));
    }
}
