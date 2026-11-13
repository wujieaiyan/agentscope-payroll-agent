package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.BatchPayrollResult;
import io.agentscope.examples.payroll.domain.Money;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollPolicy;
import io.agentscope.examples.payroll.domain.PayrollResult;
import io.agentscope.examples.payroll.domain.PayrollValidation;
import io.agentscope.examples.payroll.domain.TaxComputation;
import io.agentscope.examples.payroll.repository.JsonPayrollRepository;
import io.agentscope.examples.payroll.service.PayrollEngine;
import io.agentscope.examples.payroll.service.PayslipFormatter;
import java.math.BigDecimal;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayrollEngineTest {

    private static final YearMonth OCT_2026 = YearMonth.of(2026, 10);

    private PayrollEngine engine;
    private PayrollPolicy policy;

    @BeforeEach
    void setUp() {
        policy = PayrollPolicy.demoPolicy();
        engine = new PayrollEngine(JsonPayrollRepository.demo(), policy);
    }

    @Test
    @DisplayName("个税累计预扣率表在档位边界连续")
    void taxBracketsAreContinuousAtBoundaries() {
        assertMoney("0.00", policy.taxFor(new BigDecimal("0")).tax());
        assertMoney("1080.00", policy.taxFor(new BigDecimal("36000")).tax());
        assertMoney("1080.00", policy.taxFor(new BigDecimal("36000.01")).tax());
        assertMoney("11880.00", policy.taxFor(new BigDecimal("144000")).tax());
        assertMoney("43080.00", policy.taxFor(new BigDecimal("300000")).tax());
        assertMoney("250080.00", policy.taxFor(new BigDecimal("960000")).tax());
        assertMoney("250080.45", policy.taxFor(new BigDecimal("960001")).tax());
    }

    @Test
    @DisplayName("社保基数按政策上下限封顶")
    void socialBaseIsClamped() {
        assertMoney("7500.00", policy.clampSocialBase(new BigDecimal("3200")));
        assertMoney("20000.00", policy.clampSocialBase(new BigDecimal("20000")));
        assertMoney("37500.00", policy.clampSocialBase(new BigDecimal("45000")));
    }

    @Test
    @DisplayName("单人全链路核算：事假、加班、社保、累计个税")
    void calculateEmployeeWithLeaveAndOvertime() {
        PayrollResult result = engine.calculate("EMP001", OCT_2026);

        assertMoney("35000.00", result.baseSalary());
        assertMoney("3000.00", result.positionAllowance());
        assertMoney("6235.63", result.overtimePay());
        assertMoney("44235.63", result.grossPay());
        assertMoney("3218.39", result.attendanceDeduction());
        assertMoney("37500.00", result.socialInsuranceBase());
        assertMoney("3940.50", result.socialInsuranceEmployee());
        assertMoney("4500.00", result.housingFundEmployee());
        assertMoney("4915.35", result.tax().taxThisMonth());
        assertMoney("16574.24", result.totalDeductions());
        assertMoney("27661.39", result.netPay());
        assertMoney("58973.13", result.employerCost());
        assertTrue(result.tax().explanation().contains("累计应纳税所得额"));
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    @DisplayName("新员工入职首月按自然日折算固定工资")
    void hireMonthIsProrated() {
        PayrollResult july = engine.calculate("EMP005", YearMonth.of(2026, 7));

        assertMoney("24677.42", july.baseSalary());
        assertMoney("24677.42", july.grossPay());
        assertTrue(
                july.warnings().stream().anyMatch(w -> w.contains("入职首月")),
                "应提示入职首月折算");
    }

    @Test
    @DisplayName("跨月累计预扣：后续月份个税随累计所得进入更高档位")
    void cumulativeTaxProgressesAcrossMonths() {
        BigDecimal previousCumulativeTax = BigDecimal.ZERO;
        for (int month = 1; month <= 10; month++) {
            PayrollResult result = engine.calculate("EMP001", YearMonth.of(2026, month));
            assertTrue(
                    result.tax().taxThisMonth().signum() >= 0,
                    "本月预扣税额不能为负");
            assertTrue(
                    result.tax().cumulativeTax().compareTo(previousCumulativeTax) >= 0,
                    "累计税额不能下降");
            assertTrue(result.netPay().signum() > 0);
            previousCumulativeTax = result.tax().cumulativeTax();
        }
        assertTrue(previousCumulativeTax.compareTo(new BigDecimal("20000")) > 0);
    }

    @Test
    @DisplayName("批量核算覆盖全员且合计等于逐人之和")
    void batchTotalsMatchIndividualResults() {
        BatchPayrollResult batch = engine.calculateDepartment("全部", OCT_2026);

        assertEquals(6, batch.employeeCount());
        assertTrue(batch.failures().isEmpty());

        BigDecimal expectedNet =
                batch.records().stream()
                        .map(PayrollResult::netPay)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertMoney(expectedNet.toPlainString(), batch.totalNet());
        assertEquals(2, engine.calculateDepartment("研发部", OCT_2026).employeeCount());
    }

    @Test
    @DisplayName("发薪前校验通过且勾稽关系成立")
    void validationPassesForAllEmployees() {
        for (var employee : engine.employees()) {
            PayrollValidation validation = engine.validate(employee.employeeId(), OCT_2026);
            assertTrue(validation.valid(), employee.employeeId() + " 校验未通过");
            assertEquals(6, validation.checks().size());
            assertDoesNotThrow(() -> PayslipFormatter.format(validation.payroll()));
        }
    }

    @Test
    @DisplayName("考勤未就绪的月份拒绝核算，避免按全勤误算")
    void refusesToCalculateWhenAttendanceIsNotReady() {
        PayrollException exception =
                assertThrows(
                        PayrollException.class,
                        () -> engine.calculate("EMP001", YearMonth.of(2026, 11)));
        assertTrue(exception.getMessage().contains("考勤数据仅更新至"));
    }

    @Test
    @DisplayName("入职前月份拒绝核算")
    void refusesToCalculateBeforeHireDate() {
        PayrollException exception =
                assertThrows(
                        PayrollException.class,
                        () -> engine.calculate("EMP005", YearMonth.of(2026, 6)));
        assertTrue(exception.getMessage().contains("入职"));
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(
                0,
                new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
    }
}
