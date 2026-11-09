package io.agentscope.examples.payroll.team;

import io.agentscope.examples.payroll.support.JsonSupport;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 团队意图抽取：一次轻量模型调用 + 确定性规则兜底。
 *
 * <p>模型只做分类与槽位抽取；解析失败、超时或低置信度时由规则接管，
 * 低置信度/写操作/OTHER 由 {@link TeamIntent#eligible()} 统一拦截。
 */
public final class TeamIntentExtractor {

    private static final Logger log = LoggerFactory.getLogger(TeamIntentExtractor.class);

    private static final List<String> WRITE_KEYWORDS =
            List.of("发薪", "发放", "审批", "入账", "打款", "提交审批", "确认发薪", "批准");
    private static final List<String> POLICY_KEYWORDS =
            List.of("政策", "怎么算", "为什么", "规则", "税率", "基数", "比例", "解释", "口径");
    private static final List<String> PAYSLIP_KEYWORDS =
            List.of("工资条", "明细", "多少钱", "发多少", "到手", "实发多少", "应发多少");
    private static final List<String> REVIEW_KEYWORDS =
            List.of("复核", "校验", "检查", "验证");
    private static final List<String> BATCH_KEYWORDS =
            List.of("部门", "全员", "批量", "所有人", "员工列表", "全公司");
    private static final List<String> CALC_KEYWORDS =
            List.of(
                    "工资", "薪资", "薪酬", "核算", "个税", "社保", "公积金", "计算", "应发",
                    "实发", "扣款");

    private static final Pattern PERIOD_WITH_YEAR =
            Pattern.compile("(20\\d{2})\\s*[-年/.]\\s*(\\d{1,2})");
    private static final Pattern PERIOD_MONTH_ONLY = Pattern.compile("(\\d{1,2})\\s*月");
    private static final Pattern EMPLOYEE_ID = Pattern.compile("EMP\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEPARTMENT =
            Pattern.compile("([\\u4e00-\\u9fa5A-Za-z0-9]{2,12}部)");

    private static final String INSTRUCTION =
            """
            把用户问题解析为薪酬团队意图。输出字段：
            task_type: CALCULATE | PAYSLIP_QUERY | REVIEW | POLICY_EXPLAIN | BATCH_QUERY | OTHER
            employee_ids: 字符串数组，只填明确的工号，如 EMP001
            employee_names: 字符串数组
            departments: 字符串数组
            period: yyyy-MM，未提及时填当前月
            risk_level: LOW | MEDIUM | HIGH
            needs_validation: 涉及金额时必须为 true
            write_action: 发薪、审批、入账、打款等写操作填 true
            required_capabilities: 从 PAYROLL_CALC / PAYROLL_VALIDATE / ACCOUNTING /
              PAYROLL_QUERY / POLICY_KNOWLEDGE 中选择
            confidence: 0 到 1 的小数

            判断规则：
            1. 询问政策、规则、税率、为什么这样算 → POLICY_EXPLAIN
            2. 询问某人工资金额、明细、工资条 → PAYSLIP_QUERY
            3. 部门/全员/批量查询 → BATCH_QUERY
            4. 要求核算、计算、发薪前检查 → CALCULATE
            5. 只要求复核已有结果 → REVIEW
            6. 无法判断 → OTHER，confidence 低于 0.6
            """;

    private final TeamLlmClient llm;

    public TeamIntentExtractor(TeamLlmClient llm) {
        this.llm = llm == null ? TeamLlmClient.disabled() : llm;
    }

    public static TeamIntentExtractor rulesOnly() {
        return new TeamIntentExtractor(TeamLlmClient.disabled());
    }

    public TeamIntent extract(String question) {
        String text = question == null ? "" : question.trim();
        TeamIntent ruleIntent = rules(text);
        String json = llm.completeJson(INSTRUCTION, text, "intent-" + System.nanoTime());
        if (json == null) {
            return ruleIntent;
        }
        TeamIntent parsed;
        try {
            parsed = JsonSupport.fromJson(json, TeamIntent.class);
        } catch (RuntimeException e) {
            log.warn("[动态团队] 意图 JSON 解析失败，使用规则兜底：{}", e.getMessage());
            return ruleIntent;
        }
        TeamIntent merged = merge(parsed, ruleIntent);
        log.info(
                "[动态团队] 意图抽取：task={}，confidence={}，write={}，caps={}，period={}",
                merged.taskType(),
                merged.confidence(),
                merged.writeAction(),
                merged.requiredCapabilities(),
                merged.period());
        return merged;
    }

    private static TeamIntent merge(TeamIntent parsed, TeamIntent rules) {
        String task = parsed.taskType();
        double confidence = parsed.confidence();
        if (TeamIntent.TASK_OTHER.equals(task) && !TeamIntent.TASK_OTHER.equals(rules.taskType())) {
            task = rules.taskType();
        }
        if (confidence <= 0D) {
            confidence = isOther(task) ? rules.confidence() : Math.max(rules.confidence(), 0.7D);
        }
        List<String> capabilities =
                parsed.requiredCapabilities().isEmpty()
                        ? TeamIntent.capabilitiesFor(task)
                        : parsed.requiredCapabilities();
        boolean writeAction = parsed.writeAction() || rules.writeAction();
        boolean needsValidation =
                parsed.needsValidation()
                        || capabilities.contains(TeamIntent.CAP_PAYROLL_CALC);
        String period =
                parsed.period() == null || parsed.period().isBlank()
                        ? rules.period()
                        : parsed.period();
        return new TeamIntent(
                task,
                parsed.employeeIds().isEmpty() ? rules.employeeIds() : parsed.employeeIds(),
                parsed.employeeNames().isEmpty() ? rules.employeeNames() : parsed.employeeNames(),
                parsed.departments().isEmpty() ? rules.departments() : parsed.departments(),
                period,
                parsed.riskLevel(),
                needsValidation,
                writeAction,
                capabilities,
                confidence,
                rules.rawText());
    }

    private static boolean isOther(String task) {
        return TeamIntent.TASK_OTHER.equals(TeamIntent.normalizeTask(task));
    }

    /** 规则兜底：关键词只用于分类与槽位，不做金额或员工解析。 */
    public static TeamIntent rules(String question) {
        String text = question == null ? "" : question.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        boolean writeAction = containsAny(lower, WRITE_KEYWORDS);
        String task = classify(lower);
        List<String> capabilities = TeamIntent.capabilitiesFor(task);
        String period = period(text);
        List<String> employeeIds = matches(EMPLOYEE_ID, text).stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        List<String> departments = matches(DEPARTMENT, text).stream().distinct().toList();
        double confidence = confidence(task, lower, text);
        String risk =
                writeAction
                        ? TeamIntent.RISK_HIGH
                        : TeamIntent.TASK_CALCULATE.equals(task)
                                        || TeamIntent.TASK_BATCH_QUERY.equals(task)
                                ? TeamIntent.RISK_MEDIUM
                                : TeamIntent.RISK_LOW;
        return new TeamIntent(
                task,
                employeeIds,
                List.of(),
                departments,
                period,
                risk,
                capabilities.contains(TeamIntent.CAP_PAYROLL_CALC),
                writeAction,
                capabilities,
                confidence,
                text);
    }

    private static String classify(String lower) {
        if (containsAny(lower, POLICY_KEYWORDS)) {
            return TeamIntent.TASK_POLICY_EXPLAIN;
        }
        if (containsAny(lower, PAYSLIP_KEYWORDS)) {
            return TeamIntent.TASK_PAYSLIP_QUERY;
        }
        if (containsAny(lower, REVIEW_KEYWORDS)) {
            return TeamIntent.TASK_REVIEW;
        }
        if (containsAny(lower, BATCH_KEYWORDS)) {
            return TeamIntent.TASK_BATCH_QUERY;
        }
        if (containsAny(lower, CALC_KEYWORDS)) {
            return TeamIntent.TASK_CALCULATE;
        }
        return TeamIntent.TASK_OTHER;
    }

    private static double confidence(String task, String lower, String text) {
        if (TeamIntent.TASK_OTHER.equals(task)) {
            return 0.2D;
        }
        if (text.length() < 4) {
            return 0.3D;
        }
        if (TeamIntent.TASK_CALCULATE.equals(task)
                || TeamIntent.TASK_PAYSLIP_QUERY.equals(task)) {
            return 0.8D;
        }
        return containsAny(lower, POLICY_KEYWORDS) ? 0.75D : 0.7D;
    }

    private static String period(String text) {
        Matcher withYear = PERIOD_WITH_YEAR.matcher(text);
        if (withYear.find()) {
            int month = Integer.parseInt(withYear.group(2));
            if (month >= 1 && month <= 12) {
                return withYear.group(1) + "-" + String.format(Locale.ROOT, "%02d", month);
            }
        }
        Matcher monthOnly = PERIOD_MONTH_ONLY.matcher(text);
        if (monthOnly.find()) {
            int month = Integer.parseInt(monthOnly.group(1));
            if (month >= 1 && month <= 12) {
                return YearMonth.now().getYear()
                        + "-"
                        + String.format(Locale.ROOT, "%02d", month);
            }
        }
        return YearMonth.now().toString();
    }

    private static List<String> matches(Pattern pattern, String text) {
        List<String> values = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values;
    }

    private static boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }
}
