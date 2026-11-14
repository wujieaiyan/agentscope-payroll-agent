package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2PayrollRepository;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.AttendanceSummary;
import io.agentscope.examples.payroll.domain.CityPolicy;
import io.agentscope.examples.payroll.domain.Employee;
import io.agentscope.examples.payroll.domain.PayrollAdjustment;
import io.agentscope.examples.payroll.domain.PayrollPolicy;
import io.agentscope.examples.payroll.domain.PayrollResult;
import io.agentscope.examples.payroll.domain.PerformanceReview;
import io.agentscope.examples.payroll.service.PayrollEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class H2PayrollRepositoryTest {

    private PayrollDatabase database;
    private H2PayrollRepository repository;

    @BeforeEach
    void setUp() {
        database = PayrollDatabase.inMemory("repo-" + System.nanoTime());
        repository = new H2PayrollRepository(database);
    }

    @Test
    @DisplayName("首次启动自动导入演示员工、考勤与城市政策")
    void seedsDemoData() {
        assertEquals(6, repository.employees().size());
        assertEquals(YearMonth.of(2026, 10), repository.dataThroughPeriod());
        assertEquals(5, repository.policies().size());
        assertTrue(repository.findAttendance("EMP001", YearMonth.of(2026, 9)).isPresent());
    }

    @Test
    @DisplayName("不同城市使用不同的社保基数上下限")
    void cityPoliciesDiffer() {
        PayrollPolicy beijing = repository.policyFor("北京");
        PayrollPolicy shanghai = repository.policyFor("上海");
        assertNotNull(beijing);
        assertNotNull(shanghai);
        assertNotEquals(0, beijing.socialBaseLower().compareTo(shanghai.socialBaseLower()));
        assertEquals(
                0,
                repository.policyFor("不存在的城市").socialBaseLower()
                        .compareTo(repository.policyFor("默认").socialBaseLower()));
    }

    @Test
    @DisplayName("员工保存后可查询，停用后不再参与核算")
    void savesAndDeactivatesEmployee() {
        Employee employee = newEmployee("EMP100", "试验员工", "测试部", "测试城");
        repository.saveEmployee(employee);
        assertTrue(repository.findEmployee("EMP100").isPresent());
        assertEquals(7, repository.employees().size());

        repository.deactivateEmployee("EMP100");
        assertFalse(repository.findEmployee("EMP100").isPresent());
        assertEquals(6, repository.employees().size());
        Optional<io.agentscope.examples.payroll.domain.EmployeeView> view =
                repository.findEmployeeView("EMP100");
        assertTrue(view.isPresent());
        assertFalse(view.get().active());
    }

    @Test
    @DisplayName("考核系数作用于绩效奖金，微调金额覆盖系数结果")
    void appliesPerformanceCoefficientAndAdjustment() {
        repository.saveReview(
                new PerformanceReview(
                        "EMP002",
                        "2026-10",
                        new BigDecimal("95"),
                        new BigDecimal("1.5"),
                        "超额完成"));
        PayrollEngine engine = engine();

        PayrollResult boosted = engine.calculate("EMP002", YearMonth.of(2026, 10));
        assertEquals(0, boosted.performanceBonus().compareTo(new BigDecimal("18000.00")));
        assertTrue(boosted.warnings().stream().anyMatch(w -> w.contains("绩效系数")));

        PayrollResult adjusted =
                engine.calculate(
                        "EMP002",
                        YearMonth.of(2026, 10),
                        new PayrollAdjustment(new BigDecimal("5000"), new BigDecimal("300")));
        assertEquals(0, adjusted.performanceBonus().compareTo(new BigDecimal("5000.00")));
        assertEquals(0, adjusted.otherAllowance().compareTo(new BigDecimal("300.00")));
        assertTrue(adjusted.warnings().stream().anyMatch(w -> w.contains("手工微调")));
    }

    @Test
    @DisplayName("员工城市政策决定社保基数下限")
    void usesEmployeeCityPolicy() {
        repository.savePolicy(
                new CityPolicy(
                        "测试城",
                        PayrollPolicy.demoPolicy()
                                .withSocialBounds(
                                        new BigDecimal("6000.00"),
                                        new BigDecimal("20000.00")),
                        null));
        repository.saveEmployee(newEmployee("EMP100", "试验员工", "测试部", "测试城"));
        repository.saveAttendance(
                new AttendanceSummary(
                        "EMP100",
                        "2026-10",
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO));

        PayrollResult result = engine().calculate("EMP100", YearMonth.of(2026, 10));
        assertEquals(0, result.socialInsuranceBase().compareTo(new BigDecimal("6000.00")));
    }

    @Test
    @DisplayName("考勤缺失时拒绝按全勤推算")
    void rejectsMissingAttendance() {
        repository.saveEmployee(newEmployee("EMP100", "试验员工", "测试部", "北京"));
        assertThrows(
                RuntimeException.class,
                () -> engine().calculate("EMP100", YearMonth.of(2026, 10)));
    }

    private PayrollEngine engine() {
        return new PayrollEngine(repository, repository, repository.policyFor("默认"));
    }

    private static Employee newEmployee(
            String id, String name, String department, String city) {
        return new Employee(
                id,
                name,
                department,
                "测试工程师",
                new BigDecimal("3000.00"),
                BigDecimal.ZERO,
                new BigDecimal("0.07"),
                null,
                BigDecimal.ZERO,
                LocalDate.of(2026, 10, 1),
                "6222 **** **** 0000",
                "test@example.com",
                city);
    }
}
