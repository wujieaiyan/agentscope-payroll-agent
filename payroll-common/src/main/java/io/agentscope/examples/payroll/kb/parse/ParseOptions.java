package io.agentscope.examples.payroll.kb.parse;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** 解析阶段的结构化选项。 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public class ParseOptions {
    public boolean tableStructure = true;
    public boolean filterHeaderFooter = true;
    public boolean ocrEmbeddedImages = false;
}
