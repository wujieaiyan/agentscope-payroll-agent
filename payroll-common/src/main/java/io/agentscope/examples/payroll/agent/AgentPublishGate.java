package io.agentscope.examples.payroll.agent;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.util.List;

/**
 * 发布闸门（S4）：把运行期必然失败的组合挡在保存之前。
 *
 * <p>这里只收「不依赖环境、可确定判定」的契约：上线必须绑定技能（运行期
 * {@code boundSkills} 对空绑定直接抛错），声明业务工具必须绑定技能（业务工具组靠技能
 * 激活，没有技能就没有激活入口）。字段格式、枚举与模型连接仍由控制台各自校验。
 */
public final class AgentPublishGate {

    private AgentPublishGate() {}

    public static void check(AgentDefinition definition) {
        if (definition == null) {
            throw new PayrollException("智能体定义为空");
        }
        List<String> skillIds = definition.skillIds() == null ? List.of() : definition.skillIds();
        if (AgentDefinition.STATUS_ONLINE.equals(definition.status()) && skillIds.isEmpty()) {
            throw new PayrollException("上线智能体必须绑定至少一个技能");
        }
        if (!skillIds.isEmpty() || definition.tools() == null || definition.tools().isEmpty()) {
            return;
        }
        throw new PayrollException("声明业务工具必须绑定技能：业务工具由技能激活，未绑定技能时不可用");
    }
}
