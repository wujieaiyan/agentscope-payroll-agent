package io.agentscope.examples.payroll.domain;

import java.time.LocalDateTime;

/**
 * 写操作提案：模型只能提出、不能直接执行，必须由发起人在会话内确认。
 *
 * <p>状态机：{@code PENDING → CONFIRMED → EXECUTED/FAILED}，或
 * {@code PENDING → CANCELED/EXPIRED}。确认与取消均通过条件 UPDATE 完成，重复请求天然幂等。
 */
public record ActionProposal(
        String proposalId,
        long userId,
        long sessionId,
        long messageId,
        String agentPath,
        String toolName,
        String paramsJson,
        String summary,
        String riskLevel,
        String safetySource,
        String status,
        String idempotencyKey,
        String snapshotHash,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        long confirmedBy,
        LocalDateTime confirmedAt,
        LocalDateTime executedAt,
        String resultJson,
        String errorMessage) {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_EXECUTED = "EXECUTED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELED = "CANCELED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    public static final String RISK_NORMAL = "NORMAL";
    public static final String RISK_AMOUNT = "AMOUNT";
    public static final String RISK_IRREVERSIBLE = "IRREVERSIBLE";

    public ActionProposal {
        proposalId = proposalId == null ? "" : proposalId;
        agentPath = agentPath == null ? "" : agentPath;
        toolName = toolName == null ? "" : toolName;
        paramsJson = paramsJson == null ? "" : paramsJson;
        summary = summary == null ? "" : summary;
        riskLevel =
                riskLevel == null || riskLevel.isBlank() ? RISK_NORMAL : riskLevel;
        safetySource = safetySource == null ? "" : safetySource;
        status = status == null || status.isBlank() ? STATUS_PENDING : status;
        idempotencyKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? null
                : idempotencyKey;
        snapshotHash = snapshotHash == null || snapshotHash.isBlank() ? null : snapshotHash;
        resultJson = resultJson == null || resultJson.isBlank() ? null : resultJson;
        errorMessage = errorMessage == null || errorMessage.isBlank() ? null : errorMessage;
    }

    public boolean pending() {
        return STATUS_PENDING.equals(status);
    }

    public boolean terminal() {
        return STATUS_EXECUTED.equals(status)
                || STATUS_FAILED.equals(status)
                || STATUS_CANCELED.equals(status)
                || STATUS_EXPIRED.equals(status);
    }

    public boolean expiredAt(LocalDateTime now) {
        return pending() && expiresAt != null && !now.isBefore(expiresAt);
    }

    public ActionProposal withMessageId(long newMessageId) {
        return new ActionProposal(
                proposalId,
                userId,
                sessionId,
                newMessageId,
                agentPath,
                toolName,
                paramsJson,
                summary,
                riskLevel,
                safetySource,
                status,
                idempotencyKey,
                snapshotHash,
                createdAt,
                expiresAt,
                confirmedBy,
                confirmedAt,
                executedAt,
                resultJson,
                errorMessage);
    }
}
