package io.agentscope.examples.payroll.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 从工具参数与结果中提取可检索的追溯要素：金额、员工、期间与部门。 */
public final class ToolTraceExtractor {

    private static final Logger log = LoggerFactory.getLogger(ToolTraceExtractor.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> AMOUNT_FIELDS =
            Set.of(
                    "base_salary",
                    "gross_pay",
                    "attendance_deduction",
                    "taxable_gross",
                    "social_insurance_employee",
                    "housing_fund_employee",
                    "social_insurance_employer",
                    "housing_fund_employer",
                    "total_deductions",
                    "net_pay",
                    "employer_cost",
                    "total_gross",
                    "total_net",
                    "total_employer_cost",
                    "cumulative_tax",
                    "tax_this_month",
                    "tax");
    private static final int MAX_EMPLOYEES = 50;

    private ToolTraceExtractor() {
    }

    public record Evidence(
            List<String> employees,
            String period,
            String department,
            Map<String, BigDecimal> amounts) {

        public Evidence {
            employees = employees == null ? List.of() : List.copyOf(employees);
            amounts = amounts == null ? Map.of() : Map.copyOf(amounts);
            period = blankToNull(period);
            department = blankToNull(department);
        }

        public static final Evidence EMPTY = new Evidence(List.of(), null, null, Map.of());
    }

    public static Evidence extract(String argsJson, String resultJson) {
        JsonNode args = parse(argsJson);
        JsonNode result = parse(resultJson);
        Set<String> employees = new LinkedHashSet<>();
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();

        collectIdentity(args, employees, amounts);
        JsonNode data = result == null ? null : result.path("data");
        if (data != null && !data.isMissingNode() && !data.isNull()) {
            collectIdentity(data, employees, amounts);
        }

        String period = firstText(args, "period");
        if (period == null) {
            period = firstText(data, "period");
        }
        String department = firstText(args, "department");
        if (department == null) {
            department = firstText(data, "department");
        }
        return new Evidence(List.copyOf(employees), period, department, amounts);
    }

    /**
     * 对象节点的金额字段直接收集；数组只收集员工工号。
     *
     * <p>批量结果的 records 是逐人明细，顶层已有合计，展开会写出大量重复金额且无法区分归属。
     */
    private static void collectIdentity(
            JsonNode node, Set<String> employees, Map<String, BigDecimal> amounts) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String name = field.getKey();
                JsonNode value = field.getValue();
                if ("employee_id".equals(name) && value.isTextual()) {
                    addEmployee(employees, value.asText(""));
                } else if (AMOUNT_FIELDS.contains(name) && value.isNumber()) {
                    amounts.putIfAbsent(name, value.decimalValue());
                } else if (value.isObject()) {
                    collectIdentity(value, employees, amounts);
                } else if (value.isArray()) {
                    collectEmployees(value, employees);
                }
            }
        } else if (node.isArray()) {
            collectEmployees(node, employees);
        }
    }

    private static void collectEmployees(JsonNode array, Set<String> employees) {
        for (JsonNode item : array) {
            if (item.isTextual()) {
                addEmployee(employees, item.asText(""));
            } else if (item.isObject()) {
                JsonNode id = item.path("employee_id");
                if (id.isTextual()) {
                    addEmployee(employees, id.asText(""));
                }
            }
        }
    }

    private static void addEmployee(Set<String> employees, String value) {
        String id = value == null ? "" : value.trim();
        if (!id.isEmpty() && employees.size() < MAX_EMPLOYEES) {
            employees.add(id);
        }
    }

    private static String firstText(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            log.debug("无法解析工具追溯 JSON：{}", e.getMessage());
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
