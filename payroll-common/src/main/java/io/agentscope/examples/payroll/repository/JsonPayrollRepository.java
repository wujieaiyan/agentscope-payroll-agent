package io.agentscope.examples.payroll.repository;

import io.agentscope.examples.payroll.domain.AttendanceSummary;
import io.agentscope.examples.payroll.domain.Employee;
import io.agentscope.examples.payroll.domain.MonthlyVariablePay;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 classpath JSON 的只读实现，用于演示与测试。
 *
 * <p>员工档案、当月考勤、浮动薪酬分开存放；历史月份若无显式考勤记录，由核算引擎按全勤处理。
 */
public final class JsonPayrollRepository implements PayrollRepository {

    private final List<Employee> employees;
    private final Map<String, Employee> employeeById;
    private final Map<String, AttendanceSummary> attendanceByKey;
    private final Map<String, MonthlyVariablePay> variablePayByKey;
    private final YearMonth dataThroughPeriod;

    public JsonPayrollRepository(
            List<Employee> employees,
            List<AttendanceSummary> attendance,
            List<MonthlyVariablePay> variablePay) {
        this.employees =
                employees.stream()
                        .sorted(Comparator.comparing(Employee::employeeId))
                        .toList();
        this.employeeById = new LinkedHashMap<>();
        for (Employee employee : this.employees) {
            employeeById.put(employee.employeeId(), employee);
        }
        this.attendanceByKey = new LinkedHashMap<>();
        for (AttendanceSummary item : attendance) {
            if (!employeeById.containsKey(item.employeeId())) {
                throw new IllegalArgumentException(
                        "attendance references unknown employee: " + item.employeeId());
            }
            attendanceByKey.put(key(item.employeeId(), item.period()), item);
        }
        this.variablePayByKey = new LinkedHashMap<>();
        for (MonthlyVariablePay item : variablePay) {
            if (!employeeById.containsKey(item.employeeId())) {
                throw new IllegalArgumentException(
                        "variable pay references unknown employee: " + item.employeeId());
            }
            variablePayByKey.put(key(item.employeeId(), item.period()), item);
        }
        this.dataThroughPeriod =
                attendance.stream()
                        .map(item -> YearMonth.parse(item.period()))
                        .max(Comparator.naturalOrder())
                        .orElseThrow(() -> new IllegalArgumentException("attendance data is empty"));
    }

    public static JsonPayrollRepository demo() {
        List<Employee> employees = JsonSupport.readList("data/employees.json", Employee.class);
        List<AttendanceSummary> explicitAttendance =
                JsonSupport.readList("data/attendance-2026-10.json", AttendanceSummary.class);
        return new JsonPayrollRepository(
                employees,
                withFullMonthDefaults(employees, explicitAttendance),
                JsonSupport.readList("data/variable-pay.json", MonthlyVariablePay.class));
    }

    /**
     * 演示数据只显式维护当月考勤，历史月份按全勤补齐。
     *
     * <p>这是演示数据构造，不是引擎行为：真实环境任一月考勤缺失都必须拒绝核算。
     */
    private static List<AttendanceSummary> withFullMonthDefaults(
            List<Employee> employees, List<AttendanceSummary> explicitAttendance) {
        YearMonth through =
                explicitAttendance.stream()
                        .map(item -> YearMonth.parse(item.period()))
                        .max(Comparator.naturalOrder())
                        .orElseThrow(() -> new IllegalArgumentException("attendance data is empty"));
        Map<String, AttendanceSummary> byKey = new LinkedHashMap<>();
        for (AttendanceSummary item : explicitAttendance) {
            byKey.put(key(item.employeeId(), item.period()), item);
        }
        YearMonth yearStart = YearMonth.of(through.getYear(), 1);
        for (Employee employee : employees) {
            YearMonth start = YearMonth.from(employee.hireDate());
            if (yearStart.isAfter(start)) {
                start = yearStart;
            }
            for (YearMonth month = start; !month.isAfter(through); month = month.plusMonths(1)) {
                byKey.putIfAbsent(
                        key(employee.employeeId(), month.toString()),
                        AttendanceSummary.fullMonth(employee.employeeId(), month));
            }
        }
        return List.copyOf(byKey.values());
    }

    @Override
    public List<Employee> employees() {
        return employees;
    }

    @Override
    public Optional<Employee> findEmployee(String employeeId) {
        if (employeeId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(employeeById.get(employeeId.trim().toUpperCase()));
    }

    @Override
    public Optional<AttendanceSummary> findAttendance(String employeeId, YearMonth period) {
        return Optional.ofNullable(attendanceByKey.get(key(employeeId, period.toString())));
    }

    @Override
    public MonthlyVariablePay findVariablePay(String employeeId, YearMonth period) {
        return variablePayByKey.getOrDefault(
                key(employeeId, period.toString()),
                MonthlyVariablePay.zero(employeeId, period.toString()));
    }

    @Override
    public YearMonth dataThroughPeriod() {
        return dataThroughPeriod;
    }

    private static String key(String employeeId, String period) {
        return employeeId + "|" + period;
    }
}
