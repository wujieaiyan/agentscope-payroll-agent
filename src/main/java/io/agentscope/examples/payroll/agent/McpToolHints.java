package io.agentscope.examples.payroll.agent;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具注解快照。框架的 {@code AgentTool} 只暴露 readOnly，destructiveHint 只能在
 * {@code listTools()} 阶段读取；这里按工具名缓存注册时的注解，供确认闸门判定风险级别。
 */
final class McpToolHints {

    record Hints(boolean readOnly, boolean destructive) {
    }

    private static final Map<String, Hints> HINTS = new ConcurrentHashMap<>();

    private McpToolHints() {
    }

    static void record(McpSchema.Tool tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) {
            return;
        }
        McpSchema.ToolAnnotations annotations = tool.annotations();
        boolean readOnly =
                annotations != null && Boolean.TRUE.equals(annotations.readOnlyHint());
        boolean destructive =
                annotations != null && Boolean.TRUE.equals(annotations.destructiveHint());
        HINTS.put(tool.name(), new Hints(readOnly, destructive));
    }

    static Hints get(String toolName) {
        return toolName == null ? null : HINTS.get(toolName);
    }
}
