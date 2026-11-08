package io.agentscope.examples.payroll.domain;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** 流程连线；条件节点的出边用 sourceHandle 区分 TRUE / FALSE。 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record FlowEdge(String id, String source, String target, String sourceHandle) {

    public static final String HANDLE_TRUE = "TRUE";
    public static final String HANDLE_FALSE = "FALSE";

    public boolean trueBranch() {
        return HANDLE_TRUE.equalsIgnoreCase(sourceHandle);
    }
}
