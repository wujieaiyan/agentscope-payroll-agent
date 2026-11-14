package io.agentscope.examples.payroll.domain;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.LocalDateTime;
import java.util.List;

/** 智能体流程定义，整体存储于 Nacos（dataId=payroll-flows.json）。 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record FlowDefinition(
        String id,
        String name,
        String description,
        List<FlowNode> nodes,
        List<FlowEdge> edges,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public FlowDefinition {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }
}
