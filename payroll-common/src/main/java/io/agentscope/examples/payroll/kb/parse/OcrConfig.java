package io.agentscope.examples.payroll.kb.parse;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.ArrayList;
import java.util.List;

/** 知识库级 OCR 选项（语言、置信度、排版），引擎链本身由服务端配置决定。 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public class OcrConfig {
    public List<String> languages = new ArrayList<>(List.of("zh", "en"));
    public double minConfidence = 0.6;
    public boolean keepLayout = true;
}
