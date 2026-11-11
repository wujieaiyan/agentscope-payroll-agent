package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class McpToolRegistrarTest {

    private static final McpSchema.JsonSchema EMPTY_SCHEMA =
            new McpSchema.JsonSchema(
                    "object",
                    Map.of("employee_id", Map.of("type", "string")),
                    List.of("employee_id"),
                    false,
                    Map.of(),
                    Map.of());

    @Test
    @DisplayName("注册后的 MCP 工具可被 Toolkit 调用并转发到远端客户端")
    void registersAndForwardsToolCall() {
        Toolkit toolkit = new Toolkit();
        FakeMcpClient client =
                new FakeMcpClient("hr", tool("get_employee_profile", "查询员工档案"));

        List<McpClientWrapper> clients =
                McpToolRegistrar.registerAll(
                        toolkit, List.of(sse("hr", Set.of())), config -> client);

        assertEquals(1, clients.size());
        assertTrue(toolkit.getToolNames().contains("get_employee_profile"));

        Map<String, Object> input = Map.of("employee_id", "EMP001");
        ToolResultBlock result =
                toolkit.callTool(param("get_employee_profile", input))
                        .block(Duration.ofSeconds(2));

        assertNotNull(result);
        assertEquals("get_employee_profile", client.lastToolName);
        assertEquals("EMP001", client.lastArguments.get("employee_id"));
        assertTrue(text(result).contains("张伟"));

        clients.forEach(McpClientWrapper::close);
        assertTrue(client.closed);
    }

    @Test
    @DisplayName("allowedTools 白名单之外的工具不会暴露给模型")
    void filtersToolsByAllowlist() {
        Toolkit toolkit = new Toolkit();
        FakeMcpClient client =
                new FakeMcpClient(
                        "hr",
                        tool("get_employee_profile", "查询员工档案"),
                        tool("delete_employee", "删除员工"));

        McpToolRegistrar.registerAll(
                toolkit,
                List.of(sse("hr", Set.of("get_employee_profile"))),
                config -> client);

        assertTrue(toolkit.getToolNames().contains("get_employee_profile"));
        assertFalse(toolkit.getToolNames().contains("delete_employee"));
    }

    @Test
    @DisplayName("MCP 工具与本地工具同名时保留本地实现")
    void keepsLocalToolOnNameCollision() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new LocalTools());
        FakeMcpClient client =
                new FakeMcpClient(
                        "hr", tool("get_employee_profile", "远端员工档案"));

        McpToolRegistrar.registerAll(
                toolkit, List.of(sse("hr", Set.of())), config -> client);

        assertTrue(toolkit.getToolNames().contains("get_employee_profile"));
        assertFalse(toolkit.isExternalTool("get_employee_profile"));
    }

    @Test
    @DisplayName("单个 MCP 服务不可用时跳过，其余服务继续注册")
    void isolatesFailedServer() {
        Toolkit toolkit = new Toolkit();
        FakeMcpClient healthy =
                new FakeMcpClient("hr", tool("get_attendance_summary", "查询考勤"));

        List<McpClientWrapper> clients =
                McpToolRegistrar.registerAll(
                        toolkit,
                        List.of(sse("broken", Set.of()), sse("hr", Set.of())),
                        config -> {
                            if ("broken".equals(config.name())) {
                                throw new IllegalStateException("连接被拒绝");
                            }
                            return healthy;
                        });

        assertEquals(1, clients.size());
        assertTrue(toolkit.getToolNames().contains("get_attendance_summary"));
        assertEquals("hr", clients.get(0).getName());
    }

    @Test
    @DisplayName("解析环境变量中的 MCP 服务列表并跳过无效配置")
    void parsesServerListAndSkipsInvalidEntries() {
        String json =
                """
                [
                  {"name":"hr","transport":"sse","url":"http://localhost:9000/sse",
                   "allowedTools":["get_employee_profile"],"timeoutSeconds":8},
                  {"name":"files","transport":"stdio","command":"npx",
                   "args":["-y","server-everything"],"headers":{"X-Token":"t"}},
                  {"name":"broken","transport":"http"}
                ]
                """;

        List<McpServerConfig> configs = McpServerConfig.parse(json);

        assertEquals(2, configs.size());
        assertEquals(Duration.ofSeconds(8), configs.get(0).timeout());
        assertEquals(Set.of("get_employee_profile"), configs.get(0).allowedTools());
        assertEquals(List.of("-y", "server-everything"), configs.get(1).args());
        assertEquals(Map.of("X-Token", "t"), configs.get(1).headers());
        assertEquals(Duration.ofSeconds(15), configs.get(1).timeout());
    }

    @Test
    @DisplayName("解析 readOnlyTools，缺省时为空集")
    void parsesReadOnlyTools() {
        String json =
                """
                [
                  {"name":"docs","transport":"sse","url":"http://localhost:9000/sse",
                   "readOnlyTools":["search_doc","read_policy"]},
                  {"name":"hr","transport":"sse","url":"http://localhost:9000/sse"}
                ]
                """;

        List<McpServerConfig> configs = McpServerConfig.parse(json);

        assertEquals(2, configs.size());
        assertEquals(Set.of("search_doc", "read_policy"), configs.get(0).readOnlyTools());
        assertTrue(configs.get(1).readOnlyTools().isEmpty());
    }

    @Test
    @DisplayName("注册时缓存 MCP 注解供确认闸门判定只读与破坏性")
    void recordsToolAnnotationsForGuard() {
        McpSchema.ToolAnnotations readOnlyAnnotations =
                new McpSchema.ToolAnnotations("查询文档", true, false, false, false, null);
        McpSchema.ToolAnnotations destructiveAnnotations =
                new McpSchema.ToolAnnotations("删除文档", false, true, false, false, null);
        FakeMcpClient client =
                new FakeMcpClient(
                        "docs",
                        tool("search_doc", "检索文档", readOnlyAnnotations),
                        tool("delete_doc", "删除文档", destructiveAnnotations));
        Toolkit toolkit = new Toolkit();

        McpToolRegistrar.registerAll(
                toolkit, List.of(sse("docs", Set.of())), config -> client);

        McpToolHints.Hints search = McpToolHints.get("search_doc");
        assertNotNull(search);
        assertTrue(search.readOnly());
        assertFalse(search.destructive());
        McpToolHints.Hints delete = McpToolHints.get("delete_doc");
        assertNotNull(delete);
        assertFalse(delete.readOnly());
        assertTrue(delete.destructive());
    }

    @Test
    @DisplayName("未配置或 JSON 非法时返回空列表，不影响本地工具")
    void returnsEmptyWhenNotConfiguredOrMalformed() {
        assertTrue(McpServerConfig.parse(null).isEmpty());
        assertTrue(McpServerConfig.parse("  ").isEmpty());
        assertTrue(McpServerConfig.parse("not-json").isEmpty());
        assertTrue(McpServerConfig.parse("{\"name\":\"hr\"}").isEmpty());
    }

    private static McpServerConfig sse(String name, Set<String> allowedTools) {
        return new McpServerConfig(
                name,
                "sse",
                "http://localhost:9000/sse",
                null,
                List.of(),
                Map.of(),
                allowedTools,
                Duration.ofSeconds(2));
    }

    private static McpSchema.Tool tool(String name, String description) {
        return tool(name, description, null);
    }

    private static McpSchema.Tool tool(
            String name, String description, McpSchema.ToolAnnotations annotations) {
        return McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(EMPTY_SCHEMA)
                .annotations(annotations)
                .build();
    }

    private static ToolCallParam param(String toolName, Map<String, Object> input) {
        ToolUseBlock use =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name(toolName)
                        .input(input)
                        .content("{\"employee_id\":\"EMP001\"}")
                        .build();
        return ToolCallParam.builder().toolUseBlock(use).input(input).build();
    }

    private static String text(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(Collectors.joining("\n"));
    }

    static final class LocalTools {

        @Tool(name = "get_employee_profile", description = "本地员工档案")
        public String profile() {
            return "local";
        }
    }

    private static final class FakeMcpClient extends McpClientWrapper {

        private final List<McpSchema.Tool> tools;
        private String lastToolName;
        private Map<String, Object> lastArguments;
        private boolean closed;

        private FakeMcpClient(String name, McpSchema.Tool... tools) {
            super(name);
            this.tools = List.of(tools);
        }

        @Override
        public Mono<Void> initialize() {
            initialized = true;
            return Mono.empty();
        }

        @Override
        public Mono<List<McpSchema.Tool>> listTools() {
            return Mono.just(tools);
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(
                String toolName, Map<String, Object> arguments) {
            lastToolName = toolName;
            lastArguments = arguments;
            return Mono.just(
                    McpSchema.CallToolResult.builder().addTextContent("EMP001 张伟").build());
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(
                String toolName,
                Map<String, Object> arguments,
                Map<String, Object> meta) {
            return callTool(toolName, arguments);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
