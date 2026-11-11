package io.agentscope.examples.payroll.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.examples.payroll.agent.McpServerConfig;
import io.agentscope.examples.payroll.agent.McpToolRegistrar;
import io.agentscope.examples.payroll.domain.ActionProposal;
import io.agentscope.examples.payroll.support.JsonSupport;
import io.agentscope.examples.payroll.support.Permissions;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认执行器：显式注册的本地动作优先，其余工具按已配置的 MCP 服务派发。
 *
 * <p>MCP 客户端按需创建并在调用后关闭，不依赖智能体缓存的连接；这样即使 Agent 被空闲淘汰，
 * 确认执行仍然可用。匹配不到工具或调用失败都直接抛出异常，由提案服务落 FAILED + 审计，
 * 不做跨服务的静默重试。
 */
public final class DefaultActionExecutor implements ActionExecutor {

    @FunctionalInterface
    public interface LocalAction {
        Object execute(Map<String, Object> params, long userId);
    }

    private static final Logger log = LoggerFactory.getLogger(DefaultActionExecutor.class);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    private final Map<String, LocalAction> localActions;
    private final Map<String, String> toolPermissions;
    private final Supplier<List<McpServerConfig>> mcpServers;

    public DefaultActionExecutor() {
        this(Map.of(), Map.of(), McpServerConfig::fromEnvironment);
    }

    public DefaultActionExecutor(
            Map<String, LocalAction> localActions,
            Map<String, String> toolPermissions,
            Supplier<List<McpServerConfig>> mcpServers) {
        this.localActions = localActions == null ? Map.of() : Map.copyOf(localActions);
        this.toolPermissions = toolPermissions == null ? Map.of() : Map.copyOf(toolPermissions);
        this.mcpServers = mcpServers == null ? List::of : mcpServers;
    }

    @Override
    public String requiredPermission(String toolName) {
        return toolPermissions.getOrDefault(toolName, Permissions.CHAT_USE);
    }

    @Override
    public Object execute(ActionProposal proposal, long userId) {
        Map<String, Object> params = params(proposal.paramsJson());
        LocalAction local = localActions.get(proposal.toolName());
        if (local != null) {
            return local.execute(params, userId);
        }
        return callMcp(proposal.toolName(), params);
    }

    private Object callMcp(String toolName, Map<String, Object> params) {
        List<McpServerConfig> configs = mcpServers.get();
        if (configs.isEmpty()) {
            throw new IllegalStateException("未配置可执行该操作的外部服务：" + toolName);
        }
        List<String> unreachable = new ArrayList<>();
        for (McpServerConfig config : configs) {
            if (!config.allowedTools().isEmpty() && !config.allowedTools().contains(toolName)) {
                continue;
            }
            McpClientWrapper client = null;
            try {
                client = McpToolRegistrar.defaultFactory().create(config);
                if (!exposes(client, config.timeout(), toolName)) {
                    continue;
                }
                McpSchema.CallToolResult result =
                        client.callTool(toolName, params).block(CALL_TIMEOUT);
                return result(toolName, result);
            } catch (RuntimeException e) {
                if (client == null) {
                    unreachable.add(config.name() + "：" + rootMessage(e));
                    log.warn("[写操作] 连接 MCP 服务失败：{}，原因={}", config.name(), rootMessage(e));
                    continue;
                }
                throw e;
            } finally {
                closeQuietly(client);
            }
        }
        throw new IllegalStateException(
                "外部服务未暴露该工具："
                        + toolName
                        + (unreachable.isEmpty() ? "" : "（连接失败：" + String.join("；", unreachable) + "）"));
    }

    private static boolean exposes(McpClientWrapper client, Duration timeout, String toolName) {
        List<McpSchema.Tool> tools = client.listTools().block(timeout);
        return tools != null && tools.stream().anyMatch(tool -> toolName.equals(tool.name()));
    }

    private static Object result(String toolName, McpSchema.CallToolResult result) {
        if (result == null) {
            throw new IllegalStateException("工具 " + toolName + " 未返回结果");
        }
        String text =
                result.content() == null
                        ? ""
                        : result.content().stream()
                                .filter(McpSchema.TextContent.class::isInstance)
                                .map(McpSchema.TextContent.class::cast)
                                .map(McpSchema.TextContent::text)
                                .reduce((left, right) -> left + "\n" + right)
                                .orElse("");
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException(text.isBlank() ? "工具 " + toolName + " 执行失败" : text);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "OK");
        payload.put("tool", toolName);
        if (result.structuredContent() != null) {
            payload.put("data", result.structuredContent());
        } else {
            payload.put("data", text);
        }
        return payload;
    }

    private static Map<String, Object> params(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JsonSupport.fromJson(json, new TypeReference<Map<String, Object>>() {});
        } catch (RuntimeException e) {
            throw new IllegalStateException("提案参数无法解析，已拒绝执行", e);
        }
    }

    private static void closeQuietly(McpClientWrapper client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (RuntimeException e) {
            log.warn("[写操作] 关闭 MCP 客户端失败：{}", rootMessage(e));
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        String message = error.getMessage();
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
        }
        return message == null ? error.getClass().getSimpleName() : message;
    }
}
