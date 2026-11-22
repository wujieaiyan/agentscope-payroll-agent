package io.agentscope.examples.payroll.domain;

/** 消息上的附件引用：只保留展示、预览与下载需要的元数据。 */
public record ChatAttachmentRef(
        long id,
        String name,
        String ext,
        long sizeBytes,
        String status,
        int textChars,
        String error,
        int progress,
        ExtractionMeta extractionMeta) {
}
