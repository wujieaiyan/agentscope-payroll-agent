package io.agentscope.examples.payroll.db;

import io.agentscope.examples.payroll.domain.AttendanceSummary;
import io.agentscope.examples.payroll.domain.CityPolicy;
import io.agentscope.examples.payroll.domain.Employee;
import io.agentscope.examples.payroll.domain.EmployeeView;
import io.agentscope.examples.payroll.domain.MonthlyVariablePay;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollPolicy;
import io.agentscope.examples.payroll.domain.PerformanceReview;
import io.agentscope.examples.payroll.repository.PayrollRepository;
import io.agentscope.examples.payroll.service.PayrollPolicyProvider;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * H2 实现的薪酬主数据仓库，同时承担管理台的员工、考核与城市政策维护。
 *
 * <p>只有 {@code active = TRUE} 的员工参与核算与批量出账；停用员工的历史批次不受影响。
 */
public final class H2PayrollRepository implements PayrollRepository, PayrollPolicyProvider {

    private static final String EMPLOYEE_COLUMNS =
            """
            employee_id, name, department, job_title, base_salary, position_allowance,
            housing_fund_rate, social_insurance_base, special_additional_deduction, hire_date,
            bank_account_masked, email, city, active, updated_at
            """;

    private final PayrollDatabase database;

    public H2PayrollRepository(PayrollDatabase database) {
        this.database = database;
    }

    @Override
    public List<Employee> employees() {
        return queryEmployees(true);
    }

    public List<Employee> allEmployees(boolean includeInactive) {
        return queryEmployees(!includeInactive);
    }

    public List<EmployeeView> employeeViews(boolean includeInactive) {
        String sql =
                "SELECT "
                        + EMPLOYEE_COLUMNS
                        + " FROM employees"
                        + (includeInactive ? "" : " WHERE active = TRUE")
                        + " ORDER BY employee_id";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<EmployeeView> views = new ArrayList<>();
            while (rs.next()) {
                views.add(mapEmployeeView(rs));
            }
            return List.copyOf(views);
        } catch (SQLException e) {
            throw new IllegalStateException("查询员工失败：" + e.getMessage(), e);
        }
    }

    public Optional<EmployeeView> findEmployeeView(String employeeId) {
        String sql = "SELECT " + EMPLOYEE_COLUMNS + " FROM employees WHERE employee_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, employeeId.trim().toUpperCase());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapEmployeeView(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询员工失败：" + e.getMessage(), e);
        }
    }

    private List<Employee> queryEmployees(boolean activeOnly) {
        String sql =
                "SELECT " + EMPLOYEE_COLUMNS + " FROM employees"
                        + (activeOnly ? " WHERE active = TRUE" : "")
                        + " ORDER BY employee_id";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<Employee> employees = new ArrayList<>();
            while (rs.next()) {
                employees.add(mapEmployee(rs));
            }
            return List.copyOf(employees);
        } catch (SQLException e) {
            throw new IllegalStateException("查询员工失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Employee> findEmployee(String employeeId) {
        if (employeeId == null || employeeId.isBlank()) {
            return Optional.empty();
        }
        String sql =
                "SELECT "
                        + EMPLOYEE_COLUMNS
                        + " FROM employees WHERE employee_id = ? AND active = TRUE";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, employeeId.trim().toUpperCase());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapEmployee(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询员工失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<AttendanceSummary> findAttendance(String employeeId, YearMonth period) {
        String sql = "SELECT * FROM attendance WHERE employee_id = ? AND period = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, employeeId);
            statement.setString(2, period.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new AttendanceSummary(
                                rs.getString("employee_id"),
                                rs.getString("period"),
                                rs.getBigDecimal("personal_leave_days"),
                                rs.getBigDecimal("sick_leave_days"),
                                rs.getBigDecimal("absent_days"),
                                rs.getBigDecimal("weekday_overtime_hours"),
                                rs.getBigDecimal("rest_day_overtime_hours"),
                                rs.getBigDecimal("holiday_overtime_hours")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询考勤失败：" + e.getMessage(), e);
        }
    }

    @Override
    public MonthlyVariablePay findVariablePay(String employeeId, YearMonth period) {
        String sql = "SELECT * FROM variable_pay WHERE employee_id = ? AND period = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, employeeId);
            statement.setString(2, period.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return MonthlyVariablePay.zero(employeeId, period.toString());
                }
                return new MonthlyVariablePay(
                        rs.getString("employee_id"),
                        rs.getString("period"),
                        rs.getBigDecimal("performance_bonus"),
                        rs.getBigDecimal("other_allowance"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询浮动薪酬失败：" + e.getMessage(), e);
        }
    }

    @Override
    public BigDecimal performanceCoefficient(String employeeId, YearMonth period) {
        String sql =
                "SELECT coefficient FROM performance_reviews WHERE employee_id = ? AND period = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, employeeId);
            statement.setString(2, period.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ONE;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询考核系数失败：" + e.getMessage(), e);
        }
    }

    @Override
    public YearMonth dataThroughPeriod() {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT MAX(period) FROM attendance");
                ResultSet rs = statement.executeQuery()) {
            if (!rs.next() || rs.getString(1) == null) {
                throw new PayrollException("考勤数据为空，无法核算工资");
            }
            return YearMonth.parse(rs.getString(1));
        } catch (SQLException e) {
            throw new IllegalStateException("查询考勤就绪月份失败：" + e.getMessage(), e);
        }
    }

    /** 城市政策不存在时返回 null，由引擎给出可读错误；未指定城市回落到“默认”。 */
    @Override
    public PayrollPolicy policyFor(String city) {
        String requested = city == null || city.isBlank() ? "默认" : city.trim();
        PayrollPolicy policy = readPolicy(requested);
        if (policy == null && !"默认".equals(requested)) {
            policy = readPolicy("默认");
        }
        return policy;
    }

    private PayrollPolicy readPolicy(String city) {
        String sql = "SELECT policy_json FROM city_policies WHERE city = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, city);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next()
                        ? JsonSupport.fromJson(rs.getString(1), PayrollPolicy.class)
                        : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询城市政策失败：" + e.getMessage(), e);
        }
    }

    public List<CityPolicy> policies() {
        String sql = "SELECT city, policy_json, updated_at FROM city_policies ORDER BY city";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<CityPolicy> policies = new ArrayList<>();
            while (rs.next()) {
                policies.add(
                        new CityPolicy(
                                rs.getString("city"),
                                JsonSupport.fromJson(rs.getString("policy_json"), PayrollPolicy.class),
                                format(rs.getObject("updated_at", LocalDateTime.class))));
            }
            return List.copyOf(policies);
        } catch (SQLException e) {
            throw new IllegalStateException("查询城市政策失败：" + e.getMessage(), e);
        }
    }

    public void savePolicy(CityPolicy cityPolicy) {
        String sql =
                database.dialect(
                        """
                        MERGE INTO city_policies (city, policy_json, updated_at) KEY(city)
                        VALUES (?, ?, ?)
                        """,
                        """
                        INSERT INTO city_policies (city, policy_json, updated_at)
                        VALUES (?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            policy_json = VALUES(policy_json),
                            updated_at = VALUES(updated_at)
                        """);
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cityPolicy.city());
            statement.setString(2, JsonSupport.toJson(cityPolicy.policy()));
            statement.setObject(3, LocalDateTime.now());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存城市政策失败：" + e.getMessage(), e);
        }
    }

    public Employee saveEmployee(Employee employee) {
        String sql =
                database.dialect(
                        """
                        MERGE INTO employees (employee_id, name, department, job_title, base_salary,
                            position_allowance, housing_fund_rate, social_insurance_base,
                            special_additional_deduction, hire_date, bank_account_masked, email, city,
                            active, updated_at)
                        KEY(employee_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
                        """,
                        """
                        INSERT INTO employees (employee_id, name, department, job_title, base_salary,
                            position_allowance, housing_fund_rate, social_insurance_base,
                            special_additional_deduction, hire_date, bank_account_masked, email, city,
                            active, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
                        ON DUPLICATE KEY UPDATE
                            name = VALUES(name),
                            department = VALUES(department),
                            job_title = VALUES(job_title),
                            base_salary = VALUES(base_salary),
                            position_allowance = VALUES(position_allowance),
                            housing_fund_rate = VALUES(housing_fund_rate),
                            social_insurance_base = VALUES(social_insurance_base),
                            special_additional_deduction = VALUES(special_additional_deduction),
                            hire_date = VALUES(hire_date),
                            bank_account_masked = VALUES(bank_account_masked),
                            email = VALUES(email),
                            city = VALUES(city),
                            active = TRUE,
                            updated_at = VALUES(updated_at)
                        """);
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, employee.employeeId());
            statement.setString(2, employee.name());
            statement.setString(3, employee.department());
            statement.setString(4, employee.position());
            statement.setBigDecimal(5, employee.baseSalary());
            statement.setBigDecimal(6, employee.positionAllowance());
            statement.setBigDecimal(7, employee.housingFundRate());
            if (employee.socialInsuranceBase() == null) {
                statement.setNull(8, Types.DECIMAL);
            } else {
                statement.setBigDecimal(8, employee.socialInsuranceBase());
            }
            statement.setBigDecimal(9, employee.specialAdditionalDeduction());
            statement.setObject(10, employee.hireDate());
            statement.setString(11, employee.bankAccountMasked());
            statement.setString(12, employee.email());
            statement.setString(13, employee.city());
            statement.setObject(14, LocalDateTime.now());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存员工失败：" + e.getMessage(), e);
        }
        return employee;
    }

    public void deactivateEmployee(String employeeId) {
        String sql =
                "UPDATE employees SET active = FALSE, updated_at = ? WHERE employee_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, LocalDateTime.now());
            statement.setString(2, employeeId.trim().toUpperCase());
            if (statement.executeUpdate() == 0) {
                throw new PayrollException("员工不存在：" + employeeId);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("停用员工失败：" + e.getMessage(), e);
        }
    }

    public List<PerformanceReview> reviews(String period) {
        String sql =
                """
                SELECT employee_id, period, score, coefficient, review_comment
                FROM performance_reviews
                """
                        + (period == null || period.isBlank() ? "" : " WHERE period = ?")
                        + " ORDER BY period DESC, employee_id";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            if (period != null && !period.isBlank()) {
                statement.setString(1, period.trim());
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<PerformanceReview> reviews = new ArrayList<>();
                while (rs.next()) {
                    reviews.add(mapReview(rs));
                }
                return List.copyOf(reviews);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询考核失败：" + e.getMessage(), e);
        }
    }

    public PerformanceReview saveReview(PerformanceReview review) {
        String sql =
                database.dialect(
                        """
                        MERGE INTO performance_reviews
                            (employee_id, period, score, coefficient, review_comment, updated_at)
                        KEY(employee_id, period) VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        """
                        INSERT INTO performance_reviews
                            (employee_id, period, score, coefficient, review_comment, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            score = VALUES(score),
                            coefficient = VALUES(coefficient),
                            review_comment = VALUES(review_comment),
                            updated_at = VALUES(updated_at)
                        """);
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, review.employeeId());
            statement.setString(2, review.period());
            statement.setBigDecimal(3, review.score());
            statement.setBigDecimal(4, review.coefficient());
            statement.setString(5, review.comment());
            statement.setObject(6, LocalDateTime.now());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存考核失败：" + e.getMessage(), e);
        }
        return review;
    }

    public void deleteReview(String employeeId, String period) {
        String sql = "DELETE FROM performance_reviews WHERE employee_id = ? AND period = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, employeeId.trim().toUpperCase());
            statement.setString(2, period.trim());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除考核失败：" + e.getMessage(), e);
        }
    }

    public List<AttendanceSummary> attendanceForPeriod(String period) {
        String sql = "SELECT * FROM attendance WHERE period = ? ORDER BY employee_id";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, period);
            try (ResultSet rs = statement.executeQuery()) {
                List<AttendanceSummary> items = new ArrayList<>();
                while (rs.next()) {
                    items.add(
                            new AttendanceSummary(
                                    rs.getString("employee_id"),
                                    rs.getString("period"),
                                    rs.getBigDecimal("personal_leave_days"),
                                    rs.getBigDecimal("sick_leave_days"),
                                    rs.getBigDecimal("absent_days"),
                                    rs.getBigDecimal("weekday_overtime_hours"),
                                    rs.getBigDecimal("rest_day_overtime_hours"),
                                    rs.getBigDecimal("holiday_overtime_hours")));
                }
                return List.copyOf(items);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询考勤失败：" + e.getMessage(), e);
        }
    }

    public AttendanceSummary saveAttendance(AttendanceSummary attendance) {
        String sql =
                database.dialect(
                        """
                        MERGE INTO attendance (employee_id, period, personal_leave_days, sick_leave_days,
                            absent_days, weekday_overtime_hours, rest_day_overtime_hours,
                            holiday_overtime_hours)
                        KEY(employee_id, period) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        """
                        INSERT INTO attendance (employee_id, period, personal_leave_days, sick_leave_days,
                            absent_days, weekday_overtime_hours, rest_day_overtime_hours,
                            holiday_overtime_hours)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            personal_leave_days = VALUES(personal_leave_days),
                            sick_leave_days = VALUES(sick_leave_days),
                            absent_days = VALUES(absent_days),
                            weekday_overtime_hours = VALUES(weekday_overtime_hours),
                            rest_day_overtime_hours = VALUES(rest_day_overtime_hours),
                            holiday_overtime_hours = VALUES(holiday_overtime_hours)
                        """);
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, attendance.employeeId());
            statement.setString(2, attendance.period());
            statement.setBigDecimal(3, attendance.personalLeaveDays());
            statement.setBigDecimal(4, attendance.sickLeaveDays());
            statement.setBigDecimal(5, attendance.absentDays());
            statement.setBigDecimal(6, attendance.weekdayOvertimeHours());
            statement.setBigDecimal(7, attendance.restDayOvertimeHours());
            statement.setBigDecimal(8, attendance.holidayOvertimeHours());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存考勤失败：" + e.getMessage(), e);
        }
        return attendance;
    }

    private static Employee mapEmployee(ResultSet rs) throws SQLException {
        return new Employee(
                rs.getString("employee_id"),
                rs.getString("name"),
                rs.getString("department"),
                rs.getString("job_title"),
                rs.getBigDecimal("base_salary"),
                rs.getBigDecimal("position_allowance"),
                rs.getBigDecimal("housing_fund_rate"),
                rs.getBigDecimal("social_insurance_base"),
                rs.getBigDecimal("special_additional_deduction"),
                rs.getObject("hire_date", LocalDate.class),
                rs.getString("bank_account_masked"),
                rs.getString("email"),
                rs.getString("city"));
    }

    private static EmployeeView mapEmployeeView(ResultSet rs) throws SQLException {
        Employee employee = mapEmployee(rs);
        return new EmployeeView(
                employee.employeeId(),
                employee.name(),
                employee.department(),
                employee.position(),
                employee.baseSalary(),
                employee.positionAllowance(),
                employee.housingFundRate(),
                employee.socialInsuranceBase(),
                employee.specialAdditionalDeduction(),
                employee.hireDate(),
                employee.bankAccountMasked(),
                employee.email(),
                employee.city(),
                rs.getBoolean("active"),
                format(rs.getObject("updated_at", LocalDateTime.class)));
    }

    private static PerformanceReview mapReview(ResultSet rs) throws SQLException {
        return new PerformanceReview(
                rs.getString("employee_id"),
                rs.getString("period"),
                rs.getBigDecimal("score"),
                rs.getBigDecimal("coefficient"),
                rs.getString("review_comment"));
    }

    private static String format(LocalDateTime value) {
        return value == null
                ? ""
                : value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
