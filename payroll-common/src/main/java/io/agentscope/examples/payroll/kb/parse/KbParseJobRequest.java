package io.agentscope.examples.payroll.kb.parse;

/** Web → parser 的入库解析请求体。 */
public record KbParseJobRequest(
        long documentId,
        String storageKey,
        String ext,
        String mimeType,
        int maxPages,
        ParseConfig config) {
}
