package io.agentscope.examples.payroll.kb.parse;

import io.agentscope.examples.payroll.domain.ExtractionMeta;

/** 对话附件解析结果：文本 + 提取溯源，供落库与前端展示引擎信息。 */
public record ChatExtraction(String text, ExtractionMeta meta) {
}
