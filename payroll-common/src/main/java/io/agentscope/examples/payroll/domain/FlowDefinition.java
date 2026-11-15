package io.agentscope.examples.payroll.domain;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

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
        LocalDateTime updatedAt,
        List<String> capabilities) {

    public FlowDefinition {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }

    /** 兼容未声明 capabilities 的存量配置与调用方。 */
    public FlowDefinition(
            String id,
            String name,
            String description,
            List<FlowNode> nodes,
            List<FlowEdge> edges,
            String createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
        this(id, name, description, nodes, edges, createdBy, createdAt, updatedAt, List.of());
    }

    /** 显式声明优先；未声明时调用方按节点推断（见 6.2）。 */
    public boolean declares(String capability) {
        if (capability == null) {
            return false;
        }
        String wanted = capability.trim().toLowerCase(Locale.ROOT);
        return capabilities.stream()
                .anyMatch(item -> item != null && item.trim().equalsIgnoreCase(wanted));
    }
}
