package io.agentscope.examples.payroll.kb.parse;

import io.agentscope.examples.payroll.domain.ExtractionMeta;

/** parser → Web 的对话附件解析响应：纯文本与溯源。 */
public record KbChatParseResponse(String text, ExtractionMeta meta) {
}
