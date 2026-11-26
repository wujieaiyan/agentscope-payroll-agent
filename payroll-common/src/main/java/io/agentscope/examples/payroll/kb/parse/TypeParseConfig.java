package io.agentscope.examples.payroll.kb.parse;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** 单一文档类型的解析策略开关。 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public class TypeParseConfig {
    public boolean enabled = true;
    public String strategy = "NATIVE";
}
