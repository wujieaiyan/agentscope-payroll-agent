package io.agentscope.examples.payroll.domain;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.LocalDateTime;
import java.util.List;

/** Agent 广场中的专家智能体定义，整体存储于 Nacos（dataId=payroll-agents.json）。 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record AgentDefinition(
        String id,
        String name,
        String avatar,
        String description,
        List<String> tags,
        List<String> skillIds,
        String visibility,
        String status,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static final String VISIBILITY_PUBLIC = "PUBLIC";
    public static final String VISIBILITY_PRIVATE = "PRIVATE";
    public static final String STATUS_ONLINE = "ONLINE";
    public static final String STATUS_OFFLINE = "OFFLINE";

    public AgentDefinition {
        tags = tags == null ? List.of() : List.copyOf(tags);
        skillIds = skillIds == null ? List.of() : List.copyOf(skillIds);
    }
}
