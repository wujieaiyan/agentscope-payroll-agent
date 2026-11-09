package io.agentscope.examples.payroll.team;

/** 动态团队运行配置，对应 {@code payroll.team.*}。 */
public record TeamSettings(
        TeamMode mode, int retentionDays, int statsWindowDays, double shadowSampleRate) {

    public TeamSettings {
        mode = mode == null ? TeamMode.OFF : mode;
        retentionDays = retentionDays <= 0 ? 180 : retentionDays;
        statsWindowDays = statsWindowDays <= 0 ? 30 : statsWindowDays;
        shadowSampleRate = Math.max(0D, Math.min(1D, shadowSampleRate));
    }

    public boolean enabled() {
        return mode.recordsRuns();
    }
}
