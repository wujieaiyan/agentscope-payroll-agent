package io.agentscope.examples.payroll.team;

import java.util.Set;

/**
 * 智能体能力画像。
 *
 * <p>source=BUILTIN 表示来自内置角色技能映射并可担任强制角色；INFERRED 只用于辅助位，
 * 不参与强制角色选角。
 */
public record AgentCapabilityProfile(
        String agentId,
        String name,
        Set<String> capabilities,
        Set<String> supportedTaskTypes,
        String latencyClass,
        boolean canBeMandatoryRole,
        String source) {

    public static final String SOURCE_BUILTIN = "BUILTIN";
    public static final String SOURCE_DECLARED = "DECLARED";
    public static final String SOURCE_INFERRED = "INFERRED";

    public static final String LATENCY_FAST = "FAST";
    public static final String LATENCY_NORMAL = "NORMAL";
    public static final String LATENCY_SLOW = "SLOW";

    public AgentCapabilityProfile {
        agentId = agentId == null ? "" : agentId;
        name = name == null || name.isBlank() ? agentId : name;
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        supportedTaskTypes =
                supportedTaskTypes == null ? Set.of() : Set.copyOf(supportedTaskTypes);
        latencyClass =
                latencyClass == null || latencyClass.isBlank() ? LATENCY_NORMAL : latencyClass;
        source = source == null || source.isBlank() ? SOURCE_INFERRED : source;
    }

    public boolean supports(String capability) {
        return capabilities.contains(capability);
    }
}
