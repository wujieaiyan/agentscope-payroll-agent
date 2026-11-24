package io.agentscope.examples.payroll.kb.parse;

/** Web → parser 的对话附件解析请求：正文 base64 传输，避免依赖共享目录。 */
public record KbChatParseRequest(String name, String ext, String contentBase64) {
}
