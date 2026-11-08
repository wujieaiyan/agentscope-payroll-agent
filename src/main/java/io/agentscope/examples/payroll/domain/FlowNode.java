package io.agentscope.examples.payroll.domain;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** 流程节点：开始、智能体、条件或结束。坐标为画布位置，仅前端使用。 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record FlowNode(
        String id,
        String type,
        String name,
        String agentId,
        String condition,
        String conditionValue,
        Double x,
        Double y) {

    public static final String TYPE_START = "START";
    public static final String TYPE_AGENT = "AGENT";
    public static final String TYPE_CONDITION = "CONDITION";
    public static final String TYPE_END = "END";

    public static final String CONDITION_HAS_TARGETS = "HAS_CALCULATION_TARGETS";
    public static final String CONDITION_HAS_TOOL_ERROR = "HAS_TOOL_ERROR";
    public static final String CONDITION_OUTPUT_CONTAINS = "OUTPUT_CONTAINS";

    public String label() {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return agentId == null || agentId.isBlank() ? id : agentId;
    }
}
