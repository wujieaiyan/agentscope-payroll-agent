package io.agentscope.examples.payroll.repository;

import io.agentscope.examples.payroll.domain.AttendanceSummary;
import io.agentscope.examples.payroll.domain.Employee;
import io.agentscope.examples.payroll.domain.MonthlyVariablePay;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * 薪酬数据访问边界。
 *
 * <p>生产实现应替换为 HR 主数据 + 考勤 + 绩效的服务调用，并在此层完成数据权限控制与脱敏。
 */
public interface PayrollRepository {

    List<Employee> employees();

    Optional<Employee> findEmployee(String employeeId);

    Optional<AttendanceSummary> findAttendance(String employeeId, YearMonth period);

    MonthlyVariablePay findVariablePay(String employeeId, YearMonth period);

    /** 月度绩效系数，默认 1.0；无考核记录时不调整绩效奖金。 */
    default BigDecimal performanceCoefficient(String employeeId, YearMonth period) {
        return BigDecimal.ONE;
    }

    /** 考勤数据就绪的最大月份，超过该月份的核算必须拒绝，避免“缺勤当全勤”式误算。 */
    YearMonth dataThroughPeriod();
}
