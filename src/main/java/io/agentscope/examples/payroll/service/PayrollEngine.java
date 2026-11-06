package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.domain.AttendanceSummary;
import io.agentscope.examples.payroll.domain.BatchPayrollResult;
import io.agentscope.examples.payroll.domain.Employee;
import io.agentscope.examples.payroll.domain.Money;
import io.agentscope.examples.payroll.domain.MonthlyVariablePay;
import io.agentscope.examples.payroll.domain.PayrollAdjustment;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollPolicy;
import io.agentscope.examples.payroll.domain.PayrollResult;
import io.agentscope.examples.payroll.domain.PayrollValidation;
import io.agentscope.examples.payroll.domain.TaxComputation;
import io.agentscope.examples.payroll.domain.TaxDetail;
import io.agentscope.examples.payroll.domain.TaxState;
import io.agentscope.examples.payroll.repository.PayrollRepository;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 确定性薪酬核算引擎。
 *
 * <p>这是整个系统唯一可以“产生金额”的地方：智能体只负责理解诉求、选择工具和组织语言，
 * 所有数字都从这里返回，保证结果可复算、可审计。
 */
public final class PayrollEngine {

    private final PayrollRepository repository;
    private final PayrollPolicyProvider policyProvider;
    private final PayrollPolicy defaultPolicy;

    public PayrollEngine(PayrollRepository repository, PayrollPolicy policy) {
        this(repository, city -> policy, policy);
    }

    public PayrollEngine(
            PayrollRepository repository,
            PayrollPolicyProvider policyProvider,
            PayrollPolicy defaultPolicy) {
        this.repository = repository;
        this.policyProvider = policyProvider;
        this.defaultPolicy = defaultPolicy;
    }

    public PayrollResult calculate(String employeeId, YearMonth period) {
        return calculate(employeeId, period, null);
    }

    /** 核算指定月份，并允许对目标月份的绩效奖金/其他补贴做一次性微调。 */
    public PayrollResult calculate(
            String employeeId, YearMonth period, PayrollAdjustment adjustment) {
        Employee employee = requireEmployee(employeeId);
        validatePeriod(employee, period);
        YearMonth start = YearMonth.from(employee.hireDate());
        YearMonth yearStart = YearMonth.of(period.getYear(), 1);
        if (yearStart.isAfter(start)) {
            start = yearStart;
        }

        TaxState state = TaxState.zero();
        PayrollResult current = null;
        for (YearMonth month = start; !month.isAfter(period); month = month.plusMonths(1)) {
            AttendanceSummary attendance = resolveAttendance(employee, month);
            MonthlyVariablePay variablePay = repository.findVariablePay(employee.employeeId(), month);
            PayrollAdjustment monthAdjustment = month.equals(period) ? adjustment : null;
            MonthComputation computation =
                    computeMonth(
                            employee,
                            month,
                            attendance,
                            variablePay,
                            state,
                            monthAdjustment);
            state = computation.state();
            current = computation.result();
        }
        if (current == null) {
            throw new PayrollException(
                    "员工 " + employee.employeeId() + " 在 " + period + " 尚未入职，无法核算");
        }
        return current;
    }

    public BatchPayrollResult calculateDepartment(String department, YearMonth period) {
        String requested = department == null ? "" : department.trim();
        boolean all = requested.isEmpty() || "全部".equals(requested) || "全员".equals(requested);
        List<Employee> targets =
                repository.employees().stream()
                        .filter(e -> all || e.department().equals(requested))
                        .sorted(Comparator.comparing(Employee::employeeId))
                        .toList();
        if (targets.isEmpty()) {
            String departments =
                    repository.employees().stream()
                            .map(Employee::department)
                            .distinct()
                            .collect(Collectors.joining("、"));
            throw new PayrollException(
                    "未找到部门「" + requested + "」的员工，可选部门：" + departments);
        }

        List<PayrollResult> records = new ArrayList<>();
        List<BatchPayrollResult.BatchFailure> failures = new ArrayList<>();
        for (Employee employee : targets) {
            try {
                records.add(calculate(employee.employeeId(), period));
            } catch (RuntimeException e) {
                failures.add(
                        new BatchPayrollResult.BatchFailure(employee.employeeId(), e.getMessage()));
            }
        }
        BigDecimal totalGross = records.stream()
                .map(PayrollResult::grossPay)
                .reduce(Money.ZERO, BigDecimal::add);
        BigDecimal totalDeductions = records.stream()
                .map(PayrollResult::totalDeductions)
                .reduce(Money.ZERO, BigDecimal::add);
        BigDecimal totalNet = records.stream()
                .map(PayrollResult::netPay)
                .reduce(Money.ZERO, BigDecimal::add);
        BigDecimal totalEmployerCost = records.stream()
                .map(PayrollResult::employerCost)
                .reduce(Money.ZERO, BigDecimal::add);
        return new BatchPayrollResult(
                period.toString(),
                all ? "全部" : requested,
                records.size(),
                Money.round(totalGross),
                Money.round(totalDeductions),
                Money.round(totalNet),
                Money.round(totalEmployerCost),
                List.copyOf(records),
                List.copyOf(failures));
    }

    public PayrollValidation validate(String employeeId, YearMonth period) {
        PayrollResult result = calculate(employeeId, period);
        PayrollPolicy cityPolicy = resolvePolicy(requireEmployee(employeeId));
        List<PayrollValidation.CheckResult> checks = new ArrayList<>();

        BigDecimal componentSum =
                result.baseSalary()
                        .add(result.positionAllowance())
                        .add(result.overtimePay())
                        .add(result.performanceBonus())
                        .add(result.otherAllowance());
        checks.add(check(
                "应发合计等于各收入项之和",
                Money.round(componentSum).compareTo(result.grossPay()) == 0,
                "收入项合计 " + result.grossPay()));

        BigDecimal deductionSum =
                result.attendanceDeduction()
                        .add(result.socialInsuranceEmployee())
                        .add(result.housingFundEmployee())
                        .add(result.tax().taxThisMonth());
        checks.add(check(
                "扣款合计等于各扣款项之和",
                deductionSum.compareTo(result.totalDeductions()) == 0,
                "扣款项合计 " + result.totalDeductions()));

        checks.add(check(
                "实发 = 应发 - 扣款合计",
                result.grossPay().subtract(result.totalDeductions()).compareTo(result.netPay()) == 0,
                "实发 " + result.netPay()));

        checks.add(check(
                "社保基数在政策上下限内",
                result.socialInsuranceBase().compareTo(cityPolicy.socialBaseLower()) >= 0
                        && result.socialInsuranceBase().compareTo(cityPolicy.socialBaseUpper()) <= 0,
                "缴费基数 " + result.socialInsuranceBase()));

        checks.add(check(
                "实发工资不为负",
                result.netPay().signum() >= 0,
                "实发 " + result.netPay()));

        checks.add(check(
                "本月预扣税额不为负",
                result.tax().taxThisMonth().signum() >= 0,
                "本月预扣 " + result.tax().taxThisMonth()));

        boolean valid = checks.stream().allMatch(PayrollValidation.CheckResult::passed);
        return new PayrollValidation(
                result.employeeId(), result.period(), valid, List.copyOf(checks), result);
    }

    public List<Employee> employees() {
        return repository.employees();
    }

    public Employee employee(String employeeId) {
        return requireEmployee(employeeId);
    }

    public AttendanceSummary attendance(String employeeId, YearMonth period) {
        Employee employee = requireEmployee(employeeId);
        validatePeriod(employee, period);
        return resolveAttendance(employee, period);
    }

    public PayrollPolicy policy() {
        return defaultPolicy;
    }

    public PayrollPolicy policyFor(String city) {
        String requested = city == null || city.isBlank() ? "默认" : city.trim();
        PayrollPolicy policy = policyProvider.policyFor(requested);
        if (policy == null) {
            throw new PayrollException("城市「" + requested + "」未维护薪酬政策，请先在管理台维护");
        }
        return policy;
    }

    public YearMonth dataThroughPeriod() {
        return repository.dataThroughPeriod();
    }

    private MonthComputation computeMonth(
            Employee employee,
            YearMonth period,
            AttendanceSummary attendance,
            MonthlyVariablePay variablePay,
            TaxState previousState,
            PayrollAdjustment adjustment) {
        List<String> warnings = new ArrayList<>();
        PayrollPolicy policy = resolvePolicy(employee);

        BigDecimal baseSalary = employee.baseSalary();
        BigDecimal positionAllowance = employee.positionAllowance();
        YearMonth hireMonth = YearMonth.from(employee.hireDate());
        if (hireMonth.equals(period)) {
            int daysInMonth = period.lengthOfMonth();
            int employedDays = daysInMonth - employee.hireDate().getDayOfMonth() + 1;
            BigDecimal ratio =
                    BigDecimal.valueOf(employedDays)
                            .divide(BigDecimal.valueOf(daysInMonth), 8, Money.ROUNDING);
            baseSalary = Money.round(baseSalary.multiply(ratio));
            positionAllowance = Money.round(positionAllowance.multiply(ratio));
            warnings.add(
                    "入职首月按自然日折算：在职 "
                            + employedDays
                            + "/"
                            + daysInMonth
                            + " 天，固定工资按比例计发");
        }

        if (attendance.totalLeaveDays().compareTo(policy.standardWorkDays()) > 0) {
            throw new PayrollException(
                    "员工 " + employee.employeeId() + " 在 " + period + " 的请假/旷工天数超过标准工作日，请核对考勤");
        }

        BigDecimal dailyRate = policy.dailyRate(employee.baseSalary());
        BigDecimal personalLeaveDeduction = dailyRate.multiply(attendance.personalLeaveDays());
        BigDecimal sickLeaveDeduction =
                dailyRate
                        .multiply(attendance.sickLeaveDays())
                        .multiply(BigDecimal.ONE.subtract(policy.sickLeavePayRate()));
        BigDecimal absentDeduction = dailyRate.multiply(attendance.absentDays());
        BigDecimal attendanceDeduction =
                Money.round(personalLeaveDeduction.add(sickLeaveDeduction).add(absentDeduction));

        if (attendance.personalLeaveDays().signum() > 0) {
            warnings.add(
                    "事假 "
                            + trim(attendance.personalLeaveDays())
                            + " 天，按日薪扣减 "
                            + Money.format(personalLeaveDeduction)
                            + " 元");
        }
        if (attendance.sickLeaveDays().signum() > 0) {
            warnings.add(
                    "病假 "
                            + trim(attendance.sickLeaveDays())
                            + " 天，按 "
                            + percent(policy.sickLeavePayRate())
                            + " 计薪，扣减 "
                            + Money.format(sickLeaveDeduction)
                            + " 元");
        }
        if (attendance.absentDays().signum() > 0) {
            warnings.add(
                    "旷工 "
                            + trim(attendance.absentDays())
                            + " 天，扣减 "
                            + Money.format(absentDeduction)
                            + " 元，请 HR 复核");
        }

        BigDecimal hourlyRate = policy.hourlyRate(employee.baseSalary());
        BigDecimal equivalentOvertimeHours =
                attendance.weekdayOvertimeHours()
                        .multiply(new BigDecimal("1.5"))
                        .add(attendance.restDayOvertimeHours().multiply(new BigDecimal("2")))
                        .add(attendance.holidayOvertimeHours().multiply(new BigDecimal("3")));
        BigDecimal overtimePay = Money.round(hourlyRate.multiply(equivalentOvertimeHours));
        if (equivalentOvertimeHours.signum() > 0) {
            warnings.add(
                    "加班折算 "
                            + trim(equivalentOvertimeHours)
                            + " 小时，支付加班费 "
                            + Money.format(overtimePay)
                            + " 元");
        }

        BigDecimal performanceBonus = variablePay.performanceBonus();
        BigDecimal otherAllowance = variablePay.otherAllowance();
        BigDecimal coefficient = repository.performanceCoefficient(employee.employeeId(), period);
        if (coefficient.compareTo(BigDecimal.ONE) != 0) {
            performanceBonus = Money.round(performanceBonus.multiply(coefficient));
            warnings.add(
                    "绩效系数 "
                            + trim(coefficient)
                            + " 已应用，绩效奖金调整为 "
                            + Money.format(performanceBonus)
                            + " 元");
        }
        if (adjustment != null) {
            if (adjustment.performanceBonus() != null) {
                performanceBonus = adjustment.performanceBonus();
                warnings.add(
                        "绩效奖金已手工微调为 " + Money.format(performanceBonus) + " 元");
            }
            if (adjustment.otherAllowance() != null) {
                otherAllowance = adjustment.otherAllowance();
                warnings.add("其他补贴已手工微调为 " + Money.format(otherAllowance) + " 元");
            }
        }
        BigDecimal grossPay =
                Money.round(
                        baseSalary
                                .add(positionAllowance)
                                .add(overtimePay)
                                .add(performanceBonus)
                                .add(otherAllowance));
        BigDecimal taxableGross = Money.maxZero(grossPay.subtract(attendanceDeduction));

        BigDecimal socialCandidate =
                employee.socialInsuranceBase() != null
                        ? employee.socialInsuranceBase()
                        : employee.baseSalary().add(employee.positionAllowance());
        BigDecimal socialBase = policy.clampSocialBase(socialCandidate);
        if (socialBase.compareTo(socialCandidate) != 0) {
            warnings.add(
                    "社保缴费基数 "
                            + Money.format(socialCandidate)
                            + " 超出政策上下限，已按 "
                            + Money.format(socialBase)
                            + " 执行");
        }

        BigDecimal socialInsuranceEmployee =
                Money.round(
                        Money.rateOf(socialBase, policy.pensionEmployeeRate())
                                .add(
                                        Money.rateOf(socialBase, policy.medicalEmployeeRate())
                                                .add(policy.medicalFixedAmount()))
                                .add(
                                        Money.rateOf(
                                                socialBase, policy.unemploymentEmployeeRate())));
        BigDecimal housingFundEmployee = Money.rateOf(socialBase, employee.housingFundRate());

        BigDecimal socialInsuranceEmployer =
                Money.round(
                        Money.rateOf(socialBase, policy.pensionEmployerRate())
                                .add(Money.rateOf(socialBase, policy.medicalEmployerRate()))
                                .add(
                                        Money.rateOf(
                                                socialBase, policy.unemploymentEmployerRate()))
                                .add(Money.rateOf(socialBase, policy.injuryEmployerRate()))
                                .add(Money.rateOf(socialBase, policy.maternityEmployerRate())));
        BigDecimal housingFundEmployer = Money.rateOf(socialBase, employee.housingFundRate());

        BigDecimal specialDeduction = employee.specialAdditionalDeduction();
        BigDecimal deductionsThisMonth =
                policy.monthlyThreshold()
                        .add(socialInsuranceEmployee)
                        .add(housingFundEmployee)
                        .add(specialDeduction);

        BigDecimal cumulativeGross = previousState.cumulativeGross().add(taxableGross);
        BigDecimal cumulativeDeductions =
                previousState.cumulativeDeductions().add(deductionsThisMonth);
        BigDecimal cumulativeTaxableIncome =
                Money.maxZero(cumulativeGross.subtract(cumulativeDeductions));
        TaxComputation taxComputation = policy.taxFor(cumulativeTaxableIncome);
        BigDecimal taxThisMonth =
                Money.maxZero(
                        taxComputation.tax().subtract(previousState.cumulativeTaxWithheld()));

        TaxState nextState =
                previousState.next(taxableGross, deductionsThisMonth, taxThisMonth);
        TaxDetail taxDetail =
                new TaxDetail(
                        cumulativeGross,
                        cumulativeDeductions,
                        cumulativeTaxableIncome,
                        taxComputation.rate(),
                        taxComputation.quickDeduction(),
                        taxComputation.tax(),
                        taxThisMonth,
                        buildTaxExplanation(
                                cumulativeGross,
                                cumulativeDeductions,
                                cumulativeTaxableIncome,
                                taxComputation,
                                previousState.cumulativeTaxWithheld(),
                                taxThisMonth));

        BigDecimal totalDeductions =
                Money.round(
                        attendanceDeduction
                                .add(socialInsuranceEmployee)
                                .add(housingFundEmployee)
                                .add(taxThisMonth));
        BigDecimal netPay = Money.round(grossPay.subtract(totalDeductions));
        BigDecimal employerCost =
                Money.round(grossPay.add(socialInsuranceEmployer).add(housingFundEmployer));

        if (netPay.signum() < 0) {
            warnings.add("实发工资为负，请核实考勤扣款与社保基数后重新出账");
        }

        PayrollResult result =
                new PayrollResult(
                        employee.employeeId(),
                        employee.name(),
                        employee.department(),
                        period.toString(),
                        baseSalary,
                        positionAllowance,
                        overtimePay,
                        performanceBonus,
                        otherAllowance,
                        grossPay,
                        attendanceDeduction,
                        taxableGross,
                        socialBase,
                        socialInsuranceEmployee,
                        housingFundEmployee,
                        socialInsuranceEmployer,
                        housingFundEmployer,
                        specialDeduction,
                        totalDeductions,
                        netPay,
                        employerCost,
                        taxDetail,
                        warnings);
        return new MonthComputation(result, nextState);
    }

    private AttendanceSummary resolveAttendance(Employee employee, YearMonth month) {
        return repository
                .findAttendance(employee.employeeId(), month)
                .orElseThrow(
                        () ->
                                new PayrollException(
                                        "员工 "
                                                + employee.employeeId()
                                                + " 在 "
                                                + month
                                                + " 的考勤数据缺失，拒绝按全勤推算，请先补录考勤"));
    }

    private PayrollPolicy resolvePolicy(Employee employee) {
        return policyFor(employee.city());
    }

    private Employee requireEmployee(String employeeId) {
        if (employeeId == null || employeeId.isBlank()) {
            throw new PayrollException("请提供员工工号，例如 EMP001");
        }
        return repository
                .findEmployee(employeeId)
                .orElseThrow(() -> new PayrollException("员工工号不存在：" + employeeId));
    }

    private void validatePeriod(Employee employee, YearMonth period) {
        if (period.isAfter(repository.dataThroughPeriod())) {
            throw new PayrollException(
                    "考勤数据仅更新至 "
                            + repository.dataThroughPeriod()
                            + "，暂不能核算 "
                            + period);
        }
        if (period.isBefore(YearMonth.from(employee.hireDate()))) {
            throw new PayrollException(
                    "员工 "
                            + employee.employeeId()
                            + " 于 "
                            + employee.hireDate()
                            + " 入职，不能核算 "
                            + period);
        }
    }

    private static PayrollValidation.CheckResult check(
            String name, boolean passed, String detail) {
        return new PayrollValidation.CheckResult(name, passed, detail);
    }

    private static String buildTaxExplanation(
            BigDecimal cumulativeGross,
            BigDecimal cumulativeDeductions,
            BigDecimal cumulativeTaxable,
            TaxComputation computation,
            BigDecimal previousTaxWithheld,
            BigDecimal taxThisMonth) {
        return "累计计税收入 "
                + Money.format(cumulativeGross)
                + " - 累计扣除 "
                + Money.format(cumulativeDeductions)
                + " = 累计应纳税所得额 "
                + Money.format(cumulativeTaxable)
                + "，适用预扣率 "
                + percent(computation.rate())
                + "、速算扣除数 "
                + Money.format(computation.quickDeduction())
                + "，累计应纳税额 "
                + Money.format(computation.tax())
                + "，此前已预扣 "
                + Money.format(previousTaxWithheld)
                + "，本月预扣 "
                + Money.format(taxThisMonth)
                + " 元";
    }

    private static String percent(BigDecimal rate) {
        return rate.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%";
    }

    private static String trim(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private record MonthComputation(PayrollResult result, TaxState state) {
    }
}
