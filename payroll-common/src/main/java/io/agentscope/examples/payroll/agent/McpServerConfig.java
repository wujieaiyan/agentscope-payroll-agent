package io.agentscope.examples.payroll.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一个外部 MCP 服务的连接配置。通过 {@code PAYROLL_MCP_SERVERS} 提供 JSON 数组，
 * 空值表示不启用 MCP。
 *
 * <pre>{@code
 * [
 *   {"name":"hr","transport":"sse","url":"http://localhost:9000/sse",
 *    "allowedTools":["get_employee_profile"],"timeoutSeconds":15,
 *    "readOnlyTools":["search_employee_docs"]}
 * ]
 * }</pre>
 */
public record McpServerConfig(
        String name,
        String transport,
        String url,
        String command,
        List<String> args,
        Map<String, String> headers,
        Set<String> allowedTools,
        Set<String> readOnlyTools,
        Duration timeout) {

    public static final String ENV = "PAYROLL_MCP_SERVERS";

    private static final Logger log = LoggerFactory.getLogger(McpServerConfig.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TRANSPORTS = Set.of("stdio", "sse", "http");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    public McpServerConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MCP 服务名不能为空");
        }
        transport =
                transport == null || transport.isBlank()
                        ? "sse"
                        : transport.toLowerCase(Locale.ROOT);
        if (!TRANSPORTS.contains(transport)) {
            throw new IllegalArgumentException(
                    "MCP 传输方式只能是 stdio/sse/http，当前值：" + transport);
        }
        if ("stdio".equals(transport) && (command == null || command.isBlank())) {
            throw new IllegalArgumentException("stdio 传输必须提供 command");
        }
        if (!"stdio".equals(transport) && (url == null || url.isBlank())) {
            throw new IllegalArgumentException(transport + " 传输必须提供 url");
        }
        args = args == null ? List.of() : List.copyOf(args);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
        readOnlyTools = readOnlyTools == null ? Set.of() : Set.copyOf(readOnlyTools);
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("MCP 超时时间必须大于 0");
        }
    }

    /** 兼容旧调用方：未显式声明只读工具时为空集。 */
    public McpServerConfig(
            String name,
            String transport,
            String url,
            String command,
            List<String> args,
            Map<String, String> headers,
            Set<String> allowedTools,
            Duration timeout) {
        this(name, transport, url, command, args, headers, allowedTools, Set.of(), timeout);
    }

    public static List<McpServerConfig> fromEnvironment() {
        return parse(System.getenv(ENV));
    }

    static List<McpServerConfig> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (IOException e) {
            log.warn("[MCP] {} 不是合法 JSON，已忽略：{}", ENV, e.getMessage());
            return List.of();
        }
        if (!root.isArray()) {
            log.warn("[MCP] {} 必须是 JSON 数组", ENV);
            return List.of();
        }
        List<McpServerConfig> configs = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        int index = 0;
        for (JsonNode node : root) {
            index++;
            try {
                McpServerConfig config = parseNode(node);
                if (!names.add(config.name())) {
                    log.warn("[MCP] 服务名重复，已忽略：{}", config.name());
                    continue;
                }
                configs.add(config);
            } catch (RuntimeException e) {
                log.warn("[MCP] 忽略第 {} 个服务配置：{}", index, e.getMessage());
            }
        }
        return List.copyOf(configs);
    }

    private static McpServerConfig parseNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("配置项必须是 JSON 对象");
        }
        return new McpServerConfig(
                text(node, "name"),
                text(node, "transport"),
                text(node, "url"),
                text(node, "command"),
                strings(node.path("args")),
                stringMap(node.path("headers")),
                new LinkedHashSet<>(strings(node.path("allowedTools"))),
                new LinkedHashSet<>(strings(node.path("readOnlyTools"))),
                Duration.ofSeconds(
                        node.path("timeoutSeconds").asInt((int) DEFAULT_TIMEOUT.toSeconds())));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText().trim());
            }
        }
        return List.copyOf(values);
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        node.fields()
                .forEachRemaining(
                        entry -> {
                            if (entry.getValue().isTextual()) {
                                values.put(entry.getKey(), entry.getValue().asText());
                            }
                        });
        return Map.copyOf(values);
    }
}
