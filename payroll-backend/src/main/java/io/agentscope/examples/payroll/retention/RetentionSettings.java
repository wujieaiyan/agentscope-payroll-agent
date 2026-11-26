package io.agentscope.examples.payroll.retention;

/**
 * 保留任务配置。保留天数 0 表示关闭对应表；{@code dryRun} 只统计不删除。
 */
public record RetentionSettings(
        boolean dryRun,
        int chatDays,
        int usageDays,
        int kbDays,
        int hour,
        int minute,
        int maxMinutes,
        long batchPauseMillis,
        String attachmentDir) {

    public RetentionSettings {
        chatDays = Math.max(0, chatDays);
        usageDays = Math.max(0, usageDays);
        kbDays = Math.max(0, kbDays);
        hour = Math.min(23, Math.max(0, hour));
        minute = Math.min(59, Math.max(0, minute));
        maxMinutes = Math.max(1, maxMinutes);
        batchPauseMillis = Math.max(0L, batchPauseMillis);
        attachmentDir =
                attachmentDir == null || attachmentDir.isBlank()
                        ? "./data/chat-uploads"
                        : attachmentDir.trim();
    }
}
