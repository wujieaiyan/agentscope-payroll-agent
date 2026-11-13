package io.agentscope.examples.payroll.team;

import java.util.Locale;

/** 动态团队开关：OFF 保持旧链路，STATIC 只记录静态运行，SHADOW/ACTIVE 供 P1/P2 使用。 */
public enum TeamMode {
    OFF,
    STATIC,
    SHADOW,
    ACTIVE;

    public static TeamMode from(String value) {
        if (value == null || value.isBlank()) {
            return OFF;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OFF;
        }
    }

    public boolean recordsRuns() {
        return this != OFF;
    }

    public boolean shadow() {
        return this == SHADOW;
    }

    public boolean active() {
        return this == ACTIVE;
    }
}
