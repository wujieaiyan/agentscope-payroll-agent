package io.agentscope.examples.payroll.agent;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 业务工具目录：{@code AgentDefinition.tools} 只能声明这里登记的名字。
 *
 * <p>与 {@code PayrollTools} 上 {@code @Tool(name=...)} 一一对应，规则只此一份——控制台校验和
 * 运行期装配各写一套的话，漂移的结果是「保存成功、工具永远不可见」。payroll-tests 用反射用例
 * 钉住两边一致。
 *
 * <p>只覆盖 PayrollTools 的业务工具；kb_search、skill_read、skill_run、确认/追问等按能力或
 * 环境条件注册的工具不进入声明列表。
 */
public final class BuiltinToolCatalog {

    public static final Set<String> TOOL_NAMES =
            Set.of(
                    "list_employees",
                    "get_employee_profile",
                    "get_attendance_summary",
                    "calculate_payroll",
                    "calculate_department_payroll",
                    "validate_payroll",
                    "generate_payslip",
                    "get_payroll_policy");

    private BuiltinToolCatalog() {}

    /** 去空白、去重、保持声明顺序；目录外的名字直接拒绝，空声明返回空列表。 */
    public static List<String> normalize(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String tool : tools) {
            String value = tool == null ? "" : tool.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (!TOOL_NAMES.contains(value)) {
                throw new PayrollException("未知业务工具：" + value);
            }
            unique.add(value);
        }
        return List.copyOf(unique);
    }
}
