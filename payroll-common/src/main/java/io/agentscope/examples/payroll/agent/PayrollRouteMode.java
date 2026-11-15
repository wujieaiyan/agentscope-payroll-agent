package io.agentscope.examples.payroll.agent;

import java.util.Locale;

/**
 * 薪酬语义路由三档开关（规格 7.1）：off 完全不进语义层，shadow 只观测，
 * cache 在同桶签名一致时复用既有决策。
 */
public enum PayrollRouteMode {
    OFF,
    SHADOW,
    CACHE;

    public static PayrollRouteMode parse(String value) {
        if (value == null || value.isBlank()) {
            return OFF;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "shadow" -> SHADOW;
            case "cache" -> CACHE;
            default -> OFF;
        };
    }

    public boolean semanticEnabled() {
        return this != OFF;
    }

    public boolean cacheEnabled() {
        return this == CACHE;
    }
}
