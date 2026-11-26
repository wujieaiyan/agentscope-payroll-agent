package io.agentscope.examples.payroll.kb.parse;

/** parser → Web 的入库解析响应体，产物由 artifactKey 指向共享卷。 */
public record KbParseJobResponse(
        String artifactKey,
        long documentId,
        String sourceSha256,
        String profile,
        int pageCount,
        int charCount,
        int ocrPages,
        String engine,
        long ms,
        boolean cached) {
}
