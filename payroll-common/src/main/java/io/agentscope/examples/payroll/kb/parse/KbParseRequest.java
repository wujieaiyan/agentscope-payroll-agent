package io.agentscope.examples.payroll.kb.parse;

import java.nio.file.Path;

/** 一次解析请求：文档标识、源文件位置与本次生效的解析配置。 */
public record KbParseRequest(
        long documentId,
        Path file,
        String storageKey,
        String ext,
        String mimeType,
        ParseConfig config) {
}
