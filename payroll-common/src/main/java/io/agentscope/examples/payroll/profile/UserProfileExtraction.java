package io.agentscope.examples.payroll.profile;

import java.time.LocalDateTime;

/** 抽取凭证行的只读视图：用于运维排查与单测断言，不参与注入。 */
public record UserProfileExtraction(
        long extractionId,
        long userId,
        String targetTurnId,
        String status,
        int attempts,
        String detail,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static final String STATUS_CLAIMED = "CLAIMED";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";
}
