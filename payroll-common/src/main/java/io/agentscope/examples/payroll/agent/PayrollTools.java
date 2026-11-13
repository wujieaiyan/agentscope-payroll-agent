package io.agentscope.examples.payroll.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.examples.payroll.domain.AttendanceSummary;
import io.agentscope.examples.payroll.domain.BatchPayrollResult;
import io.agentscope.examples.payroll.domain.Employee;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollResult;
import io.agentscope.examples.payroll.domain.PayrollValidation;
import io.agentscope.examples.payroll.service.PayrollEngine;
import io.agentscope.examples.payroll.service.PayslipFormatter;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 暴露给智能体的薪酬工具。
 *
 * <p>设计原则：
 * <ul>
 *   <li>每个工具只做一件事，参数少且语义明确，降低模型误调用概率；</li>
 *   <li>工具内部复用 {@link PayrollEngine}，智能体无法“绕过公式”自己算；</li>
 *   <li>业务异常转换为结构化错误返回，让模型能继续追问而不是崩溃。</li>
 * </ul>
 */
public final class PayrollTools {

    private static final Logger log = LoggerFactory.getLogger(PayrollTools.class);

    private final PayrollEngine engine;
    private final ToolTraceRecorder recorder;

    public PayrollTools(PayrollEngine engine) {
        this(engine, null);
    }

    public PayrollTools(PayrollEngine engine, ToolTraceRecorder recorder) {
        this.engine = engine;
        this.recorder = recorder;
    }

    @Tool(
            name = "list_employees",
            description =
                    "查询员工清单。返回工号、姓名、部门、岗位与月度固定薪资。"
                            + "需要按部门筛选、确认同名员工或核对工号时使用。",
            readOnly = true)
    public String listEmployees(
            @ToolParam(
                            name = "department",
                            description = "可选，按部门精确过滤，如 研发部、销售部；不传则返回全部员工",
                            required = false)
                    String department,
            RuntimeContext context) {
        return execute(
                "list_employees",
                args("department", department),
                context,
                () -> {
                    String requested = department == null ? "" : department.trim();
                    List<Employee> employees = engine.employees();
                    List<Map<String, Object>> data =
                            employees.stream()
                                    .filter(e -> requested.isEmpty()
                                            || e.department().equals(requested))
                                    .map(this::employeeBrief)
                                    .toList();
                    if (data.isEmpty()) {
                        throw new PayrollException(
                                "未找到部门「"
                                        + requested
                                        + "」的员工，可选部门："
                                        + employees.stream()
                                                .map(Employee::department)
                                                .distinct()
                                                .collect(Collectors.joining("、")));
                    }
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("count", data.size());
                    payload.put("employees", data);
                    return payload;
                });
    }

    @Tool(
            name = "get_employee_profile",
            description =
                    "按工号或姓名查询员工薪酬档案，包括固定薪资、公积金比例、专项附加扣除、入职日期与脱敏银行卡。"
                            + "仅在用户明确指定员工后使用。",
            readOnly = true)
    public String getEmployeeProfile(
            @ToolParam(name = "employee_id", description = "员工工号或姓名，如 EMP001 或 张伟")
                    String employeeId,
            RuntimeContext context) {
        return execute(
                "get_employee_profile",
                args("employee_id", employeeId),
                context,
                () -> {
                    Employee employee = engine.employee(resolveEmployeeId(employeeId));
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("employee_id", employee.employeeId());
                    payload.put("name", employee.name());
                    payload.put("department", employee.department());
                    payload.put("position", employee.position());
                    payload.put("base_salary", employee.baseSalary());
                    payload.put("position_allowance", employee.positionAllowance());
                    payload.put("housing_fund_rate", employee.housingFundRate());
                    payload.put("social_insurance_base", employee.socialInsuranceBase());
                    payload.put(
                            "special_additional_deduction",
                            employee.specialAdditionalDeduction());
                    payload.put("hire_date", employee.hireDate());
                    payload.put("bank_account_masked", employee.bankAccountMasked());
                    payload.put("city", employee.city());
                    return payload;
                });
    }

    @Tool(
            name = "get_attendance_summary",
            description =
                    "查询员工指定月份的考勤汇总：事假、病假、旷工天数与三类加班小时数。"
                            + "核算工资前如需核对考勤，使用本工具；不要自行估算。",
            readOnly = true)
    public String getAttendanceSummary(
            @ToolParam(name = "employee_id", description = "员工工号或姓名，如 EMP001 或 张伟")
                    String employeeId,
            @ToolParam(name = "period", description = "工资期间，格式 YYYY-MM，如 2026-10")
                    String period,
            RuntimeContext context) {
        return execute(
                "get_attendance_summary",
                args("employee_id", employeeId, "period", period),
                context,
                () -> {
                    AttendanceSummary attendance =
                            engine.attendance(resolveEmployeeId(employeeId), parsePeriod(period));
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("employee_id", attendance.employeeId());
                    payload.put("period", attendance.period());
                    payload.put("personal_leave_days", attendance.personalLeaveDays());
                    payload.put("sick_leave_days", attendance.sickLeaveDays());
                    payload.put("absent_days", attendance.absentDays());
                    payload.put("weekday_overtime_hours", attendance.weekdayOvertimeHours());
                    payload.put("rest_day_overtime_hours", attendance.restDayOvertimeHours());
                    payload.put("holiday_overtime_hours", attendance.holidayOvertimeHours());
                    return payload;
                });
    }

    @Tool(
            name = "calculate_payroll",
            description =
                    "核算单个员工某月的完整工资：应发、考勤扣款、个人社保公积金、累计预扣个税、实发与企业成本，"
                            + "并返回个税计算过程和风险提示。所有金额以此工具结果为准，禁止自行计算。"
                            + "员工参数可传工号或唯一姓名。",
            readOnly = true)
    public String calculatePayroll(
            @ToolParam(name = "employee_id", description = "员工工号或姓名，如 EMP001 或 张伟")
                    String employeeId,
            @ToolParam(name = "period", description = "工资期间，格式 YYYY-MM，如 2026-10")
                    String period,
            RuntimeContext context) {
        return execute(
                "calculate_payroll",
                args("employee_id", employeeId, "period", period),
                context,
                () -> engine.calculate(resolveEmployeeId(employeeId), parsePeriod(period)));
    }

    @Tool(
            name = "calculate_department_payroll",
            description =
                    "批量核算某部门或全员的月度工资，返回人数、应发/实发/企业成本合计、每人摘要与失败名单。"
                            + "用户要求“全员工资”“部门工资表”时使用；个人明细核算请用 calculate_payroll。",
            readOnly = true)
    public String calculateDepartmentPayroll(
            @ToolParam(
                            name = "department",
                            description = "部门名称，如 研发部；不传或传“全部”表示全员",
                            required = false)
                    String department,
            @ToolParam(name = "period", description = "工资期间，格式 YYYY-MM，如 2026-10")
                    String period,
            @ToolParam(
                            name = "include_details",
                            description = "是否返回每名员工的完整明细，默认 false 仅返回摘要",
                            required = false)
                    Boolean includeDetails,
            RuntimeContext context) {
        return execute(
                "calculate_department_payroll",
                args(
                        "department", department,
                        "period", period,
                        "include_details", includeDetails),
                context,
                () -> {
                    BatchPayrollResult batch =
                            engine.calculateDepartment(department, parsePeriod(period));
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("period", batch.period());
                    payload.put("department", batch.department());
                    payload.put("employee_count", batch.employeeCount());
                    payload.put("total_gross", batch.totalGross());
                    payload.put("total_deductions", batch.totalDeductions());
                    payload.put("total_net", batch.totalNet());
                    payload.put("total_employer_cost", batch.totalEmployerCost());
                    payload.put(
                            "records",
                            Boolean.TRUE.equals(includeDetails)
                                    ? batch.records()
                                    : batch.records().stream()
                                            .map(this::payrollBrief)
                                            .toList());
                    payload.put("failures", batch.failures());
                    return payload;
                });
    }

    @Tool(
            name = "validate_payroll",
            description =
                    "对单人月度工资做发薪前校验：应发/扣款/实发勾稽关系、社保基数上下限、实发是否为负等。"
                            + "用户询问“有没有问题”“能不能发”或出账前复核时使用。",
            readOnly = true)
    public String validatePayroll(
            @ToolParam(name = "employee_id", description = "员工工号或姓名，如 EMP001 或 张伟")
                    String employeeId,
            @ToolParam(name = "period", description = "工资期间，格式 YYYY-MM，如 2026-10")
                    String period,
            RuntimeContext context) {
        return execute(
                "validate_payroll",
                args("employee_id", employeeId, "period", period),
                context,
                () -> {
                    PayrollValidation validation =
                            engine.validate(resolveEmployeeId(employeeId), parsePeriod(period));
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("employee_id", validation.employeeId());
                    payload.put("period", validation.period());
                    payload.put("valid", validation.valid());
                    payload.put("checks", validation.checks());
                    payload.put("payroll", validation.payroll());
                    return payload;
                });
    }

    @Tool(
            name = "generate_payslip",
            description =
                    "生成员工月度工资条文本，包含应发、扣款、实发、个税说明和风险提示。"
                            + "用于向员工展示或导出，金额来自系统核算结果。",
            readOnly = true)
    public String generatePayslip(
            @ToolParam(name = "employee_id", description = "员工工号或姓名，如 EMP001 或 张伟")
                    String employeeId,
            @ToolParam(name = "period", description = "工资期间，格式 YYYY-MM，如 2026-10")
                    String period,
            RuntimeContext context) {
        return execute(
                "generate_payslip",
                args("employee_id", employeeId, "period", period),
                context,
                () -> {
                    PayrollResult payroll =
                            engine.calculate(resolveEmployeeId(employeeId), parsePeriod(period));
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("employee_id", payroll.employeeId());
                    payload.put("period", payroll.period());
                    payload.put("payslip", PayslipFormatter.format(payroll));
                    return payload;
                });
    }

    @Tool(
            name = "get_payroll_policy",
            description =
                    "查询指定城市当前生效的薪酬政策参数：个税累计预扣率表、社保公积金比例、"
                            + "缴费基数上下限、月减除费用、标准工时与病假计薪比例，以及考勤数据就绪月份。"
                            + "回答制度、税率、比例类问题前必须先调用本工具。",
            readOnly = true)
    public String getPayrollPolicy(
            @ToolParam(
                            name = "city",
                            description = "可选，城市名称，如 北京、上海；不传则返回默认政策",
                            required = false)
                    String city,
            RuntimeContext context) {
        return execute(
                "get_payroll_policy",
                args("city", city),
                context,
                () -> {
                    String requested = city == null || city.isBlank() ? "默认" : city.trim();
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("city", requested);
                    payload.put("data_through_period", engine.dataThroughPeriod());
                    payload.put("policy", engine.policyFor(requested));
                    payload.put(
                            "notes",
                            List.of(
                                    "税率表为累计预扣法年度口径，本月个税 = 累计应纳税额 - 已预扣税额",
                                    "社保基数 = 员工申报基数或（基本工资 + 岗位津贴），并受上下限约束",
                                    "公积金个人与企业比例相同，均以社保基数为基数",
                                    "加班费以基本工资为基数：工作日 1.5 倍、休息日 2 倍、法定节假日 3 倍",
                                    "事假与旷工按日薪全额扣减，病假按病假工资比例计薪",
                                    "演示数据不代表任何地区现行政策，生产环境需替换为当地社保年度参数"));
                    return payload;
                });
    }

    /** 允许模型直接传姓名，但只在唯一命中时放行，避免同名误算。 */
    private String resolveEmployeeId(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new PayrollException("请提供员工工号或姓名");
        }
        String value = identifier.trim();
        List<Employee> matches =
                engine.employees().stream()
                        .filter(
                                e ->
                                        e.employeeId().equalsIgnoreCase(value)
                                                || e.name().equals(value))
                        .toList();
        if (matches.size() == 1) {
            return matches.get(0).employeeId();
        }
        if (matches.size() > 1) {
            throw new PayrollException(
                    "「"
                            + value
                            + "」对应多名员工，请改用工号："
                            + matches.stream()
                                    .map(e -> e.employeeId() + "(" + e.name() + ")")
                                    .collect(Collectors.joining("、")));
        }
        throw new PayrollException("未找到员工「" + value + "」，请先用 list_employees 查询工号");
    }

    private Map<String, Object> employeeBrief(Employee employee) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("employee_id", employee.employeeId());
        item.put("name", employee.name());
        item.put("department", employee.department());
        item.put("position", employee.position());
        item.put("base_salary", employee.baseSalary());
        item.put("position_allowance", employee.positionAllowance());
        return item;
    }

    private Map<String, Object> payrollBrief(PayrollResult result) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("employee_id", result.employeeId());
        item.put("employee_name", result.employeeName());
        item.put("department", result.department());
        item.put("gross_pay", result.grossPay());
        item.put("total_deductions", result.totalDeductions());
        item.put("net_pay", result.netPay());
        item.put("warnings", result.warnings());
        return item;
    }

    private static YearMonth parsePeriod(String period) {
        if (period == null || period.isBlank()) {
            throw new PayrollException("请提供工资期间，格式 YYYY-MM，例如 2026-10");
        }
        try {
            return YearMonth.parse(period.trim());
        } catch (DateTimeParseException e) {
            throw new PayrollException("工资期间格式错误：" + period + "，应为 YYYY-MM");
        }
    }

    private String execute(
            String toolName,
            Map<String, Object> arguments,
            RuntimeContext context,
            Supplier<Object> action) {
        long startedAt = System.nanoTime();
        String result;
        ActionGuard guard = context == null ? null : context.get(ActionGuard.class);
        String pending =
                guard == null
                        ? null
                        : guard.intercept(toolName, arguments, false, false, false);
        if (pending != null) {
            result = pending;
        } else {
            try {
                Object data = action.get();
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("status", "OK");
                response.put("data", data);
                result = JsonSupport.toJson(response);
            } catch (PayrollException e) {
                result = error(e.getMessage());
            } catch (RuntimeException e) {
                log.warn("payroll tool failed", e);
                result = error("核算执行失败：" + e.getMessage());
            }
        }
        ToolTraceRecorder activeRecorder = recorderFor(context);
        if (activeRecorder != null) {
            try {
                activeRecorder.record(
                        toolName,
                        JsonSupport.toJson(arguments),
                        result,
                        (System.nanoTime() - startedAt) / 1_000_000);
            } catch (RuntimeException e) {
                log.warn("记录工具追溯失败：tool={}，原因={}", toolName, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 共享智能体下不能把追溯记录器绑在工具实例上：每个阶段/流程节点把本次调用的记录器
     * 放进 RuntimeContext，工具按调用取用，避免并发会话的金额追溯串线。
     */
    private ToolTraceRecorder recorderFor(RuntimeContext context) {
        if (context != null) {
            ToolTraceRecorder scoped = context.get(ToolTraceRecorder.class);
            if (scoped != null) {
                return scoped;
            }
        }
        return recorder;
    }

    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                arguments.put((String) pairs[i], pairs[i + 1]);
            }
        }
        return arguments;
    }

    private static String error(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ERROR");
        response.put("message", message);
        return JsonSupport.toJson(response);
    }
}
