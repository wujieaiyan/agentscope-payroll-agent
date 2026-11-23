package io.agentscope.examples.payroll.domain;

import java.time.LocalDateTime;

/**
 * 对话附件：原文件落在 data/chat-uploads，提取文本单独保存，数据库只存元数据。
 *
 * <p>messageId 为 0 表示尚未随消息发送；发送后只随会话删除，不单独删除。
 * PARSING 是真实异步中间态，progress 为 0-100；extractionMeta 记录引擎与质量门溯源。
 */
public record ChatAttachment(
        long id,
        long sessionId,
        long userId,
        long messageId,
        String name,
        String ext,
        long sizeBytes,
        String storageKey,
        int textChars,
        String status,
        String error,
        int progress,
        ExtractionMeta extractionMeta,
        LocalDateTime createdAt) {

    public static final String STATUS_PARSING = "PARSING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    public ChatAttachmentRef toRef() {
        return new ChatAttachmentRef(
                id, name, ext, sizeBytes, status, textChars, error, progress, extractionMeta);
    }
}
