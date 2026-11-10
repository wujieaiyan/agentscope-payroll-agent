package io.agentscope.examples.payroll.team;

import java.util.Set;

/** 动态团队运行配置，对应 {@code payroll.team.*}。 */
public record TeamSettings(
        TeamMode mode,
        int retentionDays,
        int statsWindowDays,
        double shadowSampleRate,
        Set<Long> activeUserIds,
        Set<Long> activeSessionIds) {

    public TeamSettings {
        mode = mode == null ? TeamMode.OFF : mode;
        retentionDays = retentionDays <= 0 ? 180 : retentionDays;
        statsWindowDays = statsWindowDays <= 0 ? 30 : statsWindowDays;
        shadowSampleRate = Math.max(0D, Math.min(1D, shadowSampleRate));
        activeUserIds = activeUserIds == null ? Set.of() : Set.copyOf(activeUserIds);
        activeSessionIds = activeSessionIds == null ? Set.of() : Set.copyOf(activeSessionIds);
    }

    public TeamSettings(
            TeamMode mode, int retentionDays, int statsWindowDays, double shadowSampleRate) {
        this(mode, retentionDays, statsWindowDays, shadowSampleRate, Set.of(), Set.of());
    }

    public boolean enabled() {
        return mode.recordsRuns();
    }

    /**
     * ACTIVE 灰度判定：用户与会话两个白名单同时为空表示放量到全部用户；
     * 任一白名单非空时，该维度只允许列出的 ID。
     */
    public boolean activeAllowed(long userId, long sessionId) {
        if (!mode.active()) {
            return false;
        }
        boolean userAllowed = activeUserIds.isEmpty() || activeUserIds.contains(userId);
        boolean sessionAllowed =
                activeSessionIds.isEmpty() || activeSessionIds.contains(sessionId);
        return userAllowed && sessionAllowed;
    }
}
