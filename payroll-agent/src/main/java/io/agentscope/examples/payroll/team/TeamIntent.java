package io.agentscope.examples.payroll.team;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 动态团队意图：只做任务分类与槽位抽取，不参与选角与金额计算。 */
public record TeamIntent(
        String taskType,
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                List<String> employeeIds,
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                List<String> employeeNames,
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                List<String> departments,
        String period,
        String riskLevel,
        boolean needsValidation,
        boolean writeAction,
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                List<String> requiredCapabilities,
        double confidence,
        String rawText) {

    public static final String TASK_CALCULATE = "CALCULATE";
    public static final String TASK_PAYSLIP_QUERY = "PAYSLIP_QUERY";
    public static final String TASK_REVIEW = "REVIEW";
    public static final String TASK_POLICY_EXPLAIN = "POLICY_EXPLAIN";
    public static final String TASK_BATCH_QUERY = "BATCH_QUERY";
    public static final String TASK_KB_CONFIG = "KB_CONFIG";
    public static final String TASK_OTHER = "OTHER";

    public static final String CAP_PAYROLL_CALC = "PAYROLL_CALC";
    public static final String CAP_PAYROLL_VALIDATE = "PAYROLL_VALIDATE";
    public static final String CAP_ACCOUNTING = "ACCOUNTING";
    public static final String CAP_PAYROLL_QUERY = "PAYROLL_QUERY";
    public static final String CAP_POLICY_KNOWLEDGE = "POLICY_KNOWLEDGE";
    public static final String CAP_KB_CONFIG = "KB_CONFIG";
    public static final Set<String> CAPABILITIES =
            Set.of(
                    CAP_PAYROLL_CALC,
                    CAP_PAYROLL_VALIDATE,
                    CAP_ACCOUNTING,
                    CAP_PAYROLL_QUERY,
                    CAP_POLICY_KNOWLEDGE,
                    CAP_KB_CONFIG);

    public static final Set<String> TASK_TYPES =
            Set.of(
                    TASK_CALCULATE,
                    TASK_PAYSLIP_QUERY,
                    TASK_REVIEW,
                    TASK_POLICY_EXPLAIN,
                    TASK_BATCH_QUERY,
                    TASK_KB_CONFIG,
                    TASK_OTHER);

    public static final String RISK_LOW = "LOW";
    public static final String RISK_MEDIUM = "MEDIUM";
    public static final String RISK_HIGH = "HIGH";
    public static final String DEFAULT_PERIOD = "CURRENT";

    private static final double MIN_CONFIDENCE = 0.6D;

    public TeamIntent {
        taskType = normalizeTask(taskType);
        employeeIds = copy(employeeIds);
        employeeNames = copy(employeeNames);
        departments = copy(departments);
        period = period == null ? "" : period.trim();
        riskLevel = normalizeRisk(riskLevel);
        requiredCapabilities =
                copy(requiredCapabilities).stream()
                        .map(value -> value.trim().toUpperCase(Locale.ROOT))
                        .filter(CAPABILITIES::contains)
                        .distinct()
                        .toList();
        confidence = Math.max(0D, Math.min(1D, confidence));
        rawText = rawText == null ? "" : rawText;
    }

    /** L0 门槛：低置信度、写操作、无法归类都直接走静态链路。 */
    public boolean eligible() {
        return confidence >= MIN_CONFIDENCE
                && !writeAction
                && !TASK_OTHER.equals(taskType)
                && !requiredCapabilities.isEmpty();
    }

    public boolean requires(String capability) {
        return requiredCapabilities.contains(capability);
    }

    public TeamIntent withRawText(String text) {
        return new TeamIntent(
                taskType,
                employeeIds,
                employeeNames,
                departments,
                period,
                riskLevel,
                needsValidation,
                writeAction,
                requiredCapabilities,
                confidence,
                text);
    }

    public static List<String> capabilitiesFor(String taskType) {
        return switch (normalizeTask(taskType)) {
            case TASK_CALCULATE -> List.of(CAP_PAYROLL_CALC, CAP_PAYROLL_VALIDATE);
            case TASK_PAYSLIP_QUERY, TASK_BATCH_QUERY -> List.of(CAP_PAYROLL_QUERY);
            case TASK_REVIEW -> List.of(CAP_PAYROLL_VALIDATE);
            case TASK_POLICY_EXPLAIN -> List.of(CAP_POLICY_KNOWLEDGE);
            case TASK_KB_CONFIG -> List.of(CAP_KB_CONFIG);
            default -> List.of();
        };
    }

    public static String normalizeTask(String taskType) {
        String value = taskType == null ? "" : taskType.trim().toUpperCase(Locale.ROOT);
        return TASK_TYPES.contains(value) ? value : TASK_OTHER;
    }

    private static String normalizeRisk(String riskLevel) {
        String value = riskLevel == null ? "" : riskLevel.trim().toUpperCase(Locale.ROOT);
        return RISK_LOW.equals(value) || RISK_MEDIUM.equals(value) || RISK_HIGH.equals(value)
                ? value
                : RISK_LOW;
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
