package io.agentscope.examples.payroll.agent;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 把外部 MCP 服务注册进指定 Toolkit。单个服务连接或初始化失败只跳过该服务，
 * 不影响本地工具与其他 MCP 服务。
 */
public final class McpToolRegistrar {

    @FunctionalInterface
    public interface ClientFactory {
        McpClientWrapper create(McpServerConfig config);
    }

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistrar.class);
    private static final Duration BLOCK_GRACE = Duration.ofSeconds(5);

    private McpToolRegistrar() {
    }

    public static ClientFactory defaultFactory() {
        return McpToolRegistrar::buildClient;
    }

    /**
     * 按配置顺序逐个注册；返回成功的客户端列表，由调用方负责关闭。
     *
     * <p>注册前会取当前 Toolkit 的工具名做快照，MCP 工具与本地工具同名时保留本地实现；
     * {@code allowedTools} 为空表示放行该服务全部工具。
     */
    public static List<McpClientWrapper> registerAll(
            Toolkit toolkit, List<McpServerConfig> configs, ClientFactory factory) {
        if (configs.isEmpty()) {
            return List.of();
        }
        Set<String> before = Set.copyOf(toolkit.getToolNames());
        List<McpClientWrapper> registered = new ArrayList<>();
        for (McpServerConfig config : configs) {
            McpClientWrapper raw = null;
            try {
                raw = factory.create(config);
                McpClientWrapper client =
                        new FilteringClient(config, raw, Set.copyOf(toolkit.getToolNames()));
                toolkit.registerMcpClient(client).block(config.timeout().plus(BLOCK_GRACE));
                registered.add(client);
                log.info(
                        "[MCP] 已注册 {}（{}），允许工具={}",
                        config.name(),
                        config.transport(),
                        config.allowedTools().isEmpty() ? "全部非冲突工具" : config.allowedTools());
            } catch (RuntimeException e) {
                closeQuietly(raw);
                log.warn("[MCP] 注册 {} 失败，已跳过：{}", config.name(), rootMessage(e));
            }
        }
        wrapNewTools(toolkit, before);
        return List.copyOf(registered);
    }

    /** 只包新注册的 MCP 工具；内置工具仍在技能组里，替换会破坏激活语义。 */
    private static void wrapNewTools(Toolkit toolkit, Set<String> before) {
        for (String name : List.copyOf(toolkit.getToolNames())) {
            if (before.contains(name)) {
                continue;
            }
            io.agentscope.core.tool.AgentTool tool = toolkit.getTool(name);
            if (tool == null || tool instanceof GuardedAgentTool) {
                continue;
            }
            toolkit.removeTool(name);
            toolkit.registerAgentTool(new GuardedAgentTool(tool));
        }
    }

    private static McpClientWrapper buildClient(McpServerConfig config) {
        McpClientBuilder builder = McpClientBuilder.create(config.name());
        switch (config.transport()) {
            case "stdio" -> builder.stdioTransport(config.command(), config.args(), Map.of());
            case "sse" -> builder.sseTransport(config.url());
            case "http" -> builder.streamableHttpTransport(config.url());
            default ->
                    throw new IllegalArgumentException(
                            "不支持的 MCP 传输方式：" + config.transport());
        }
        if (!config.headers().isEmpty()) {
            builder.headers(config.headers());
        }
        return builder.timeout(config.timeout())
                .initializationTimeout(config.timeout())
                .buildSync();
    }

    private static void closeQuietly(McpClientWrapper client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (RuntimeException e) {
            log.warn("[MCP] 关闭客户端失败：{}", client.getName(), e);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        String message = error.getMessage();
        while (current.getCause() != null) {
            current = current.getCause();
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
        }
        return message == null ? error.getClass().getSimpleName() : message;
    }

    /** 在 listTools 阶段做白名单与同名过滤，避免把不需要的远端能力暴露给模型。 */
    private static final class FilteringClient extends McpClientWrapper {

        private final McpClientWrapper delegate;
        private final Set<String> allowedTools;
        private final Set<String> reservedNames;

        private FilteringClient(
                McpServerConfig config, McpClientWrapper delegate, Set<String> reservedNames) {
            super(config.name());
            this.delegate = delegate;
            this.allowedTools = config.allowedTools();
            this.reservedNames = reservedNames;
        }

        @Override
        public Mono<Void> initialize() {
            return delegate.initialize();
        }

        @Override
        public Mono<List<McpSchema.Tool>> listTools() {
            return delegate.listTools()
                    .map(
                            tools ->
                                    tools.stream()
                                            .filter(this::visible)
                                            .peek(McpToolHints::record)
                                            .toList());
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(
                String toolName, Map<String, Object> arguments) {
            return delegate.callTool(toolName, arguments);
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(
                String toolName,
                Map<String, Object> arguments,
                Map<String, Object> meta) {
            return delegate.callTool(toolName, arguments, meta);
        }

        @Override
        public void close() {
            delegate.close();
        }

        private boolean visible(McpSchema.Tool tool) {
            String name = tool.name();
            if (reservedNames.contains(name)) {
                log.warn("[MCP] 工具 {} 与已有工具同名，保留已有实现", name);
                return false;
            }
            return allowedTools.isEmpty() || allowedTools.contains(name);
        }
    }
}
