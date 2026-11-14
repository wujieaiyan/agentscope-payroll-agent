package io.agentscope.examples.payroll.team;

/** 动态团队中的角色槽位；模板与计划校验都以此为准。 */
public enum TeamRole {
    CALCULATOR(TeamIntent.CAP_PAYROLL_CALC, "计算智能体"),
    VALIDATOR(TeamIntent.CAP_PAYROLL_VALIDATE, "验证智能体"),
    ACCOUNTANT(TeamIntent.CAP_ACCOUNTING, "核算智能体"),
    QUERY(TeamIntent.CAP_PAYROLL_QUERY, "查询智能体"),
    KNOWLEDGE(TeamIntent.CAP_POLICY_KNOWLEDGE, "知识智能体"),
    KB_CONFIG(TeamIntent.CAP_KB_CONFIG, "知识库配置智能体");

    private final String capability;
    private final String label;

    TeamRole(String capability, String label) {
        this.capability = capability;
        this.label = label;
    }

    public String capability() {
        return capability;
    }

    public String label() {
        return label;
    }

    public boolean mandatory() {
        return this == CALCULATOR || this == VALIDATOR || this == ACCOUNTANT;
    }

    /** 是否具备该角色能力；允许能力相邻的辅助位，但不越过强制复核。 */
    public boolean matches(java.util.Set<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return false;
        }
        if (capabilities.contains(capability)) {
            return true;
        }
        return switch (this) {
            case CALCULATOR -> capabilities.contains(TeamIntent.CAP_ACCOUNTING);
            case VALIDATOR -> capabilities.contains(TeamIntent.CAP_PAYROLL_CALC);
            case ACCOUNTANT -> capabilities.contains(TeamIntent.CAP_PAYROLL_CALC);
            case QUERY -> capabilities.contains(TeamIntent.CAP_PAYROLL_CALC);
            case KNOWLEDGE -> capabilities.contains(TeamIntent.CAP_PAYROLL_QUERY);
            case KB_CONFIG -> false;
        };
    }

    public static TeamRole parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
