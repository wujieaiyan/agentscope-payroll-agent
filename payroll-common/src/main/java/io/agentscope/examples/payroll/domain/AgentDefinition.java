package io.agentscope.examples.payroll.domain;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.agentscope.examples.payroll.model.AgentModelSettings;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

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
        LocalDateTime updatedAt,
        List<String> capabilities,
        AgentModelSettings model) {

    public static final String VISIBILITY_PUBLIC = "PUBLIC";
    public static final String VISIBILITY_PRIVATE = "PRIVATE";
    public static final String STATUS_ONLINE = "ONLINE";
    public static final String STATUS_OFFLINE = "OFFLINE";

    public static final String CAPABILITY_KB_READ = "kb:read";
    public static final String CAPABILITY_KB_CONFIG = "kb:config";
    public static final String CAPABILITY_CALC = "calc";
    public static final String CAPABILITY_VIDEO = "video";

    public AgentDefinition {
        tags = tags == null ? List.of() : List.copyOf(tags);
        skillIds = skillIds == null ? List.of() : List.copyOf(skillIds);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        model = model == null ? AgentModelSettings.none() : model;
    }

    /** 兼容未声明 model 的存量配置与调用方。 */
    public AgentDefinition(
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
            LocalDateTime updatedAt,
            List<String> capabilities) {
        this(
                id,
                name,
                avatar,
                description,
                tags,
                skillIds,
                visibility,
                status,
                createdBy,
                createdAt,
                updatedAt,
                capabilities,
                AgentModelSettings.none());
    }

    /** 兼容未声明 capabilities 与 model 的存量配置与调用方。 */
    public AgentDefinition(
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
        this(
                id,
                name,
                avatar,
                description,
                tags,
                skillIds,
                visibility,
                status,
                createdBy,
                createdAt,
                updatedAt,
                List.of(),
                AgentModelSettings.none());
    }

    /** 仅匹配显式声明；未声明时由 {@code AgentCapabilities} 按标签/角色推断。 */
    public boolean declares(String capability) {
        if (capability == null) {
            return false;
        }
        String wanted = capability.trim().toLowerCase(Locale.ROOT);
        return capabilities.stream()
                .anyMatch(item -> item != null && item.trim().equalsIgnoreCase(wanted));
    }
}
