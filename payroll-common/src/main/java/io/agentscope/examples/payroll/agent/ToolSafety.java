package io.agentscope.examples.payroll.agent;

import io.agentscope.core.tool.Tool;
import io.agentscope.examples.payroll.domain.ActionProposal;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具读写分类。优先级：本地覆盖 → 服务端声明 → 默认值。
 *
 * <p>内置工具读 {@link Tool#readOnly()}；MCP 工具的 {@code readOnlyHint} 已由框架映射到
 * {@code AgentTool.isReadOnly()}，未声明时按「可能修改」处理。覆盖用于服务端谎报或漏报的兜底。
 */
public final class ToolSafety {

    private static final Logger log = LoggerFactory.getLogger(ToolSafety.class);

    public static final String SOURCE_HINT_READ_ONLY = "HINT_READ_ONLY";
    public static final String SOURCE_DEFAULT_MUTATING = "DEFAULT_MUTATING";
    public static final String SOURCE_OVERRIDE = "OVERRIDE";

    private static final Map<String, Boolean> BUILTIN_READ_ONLY = scanBuiltinReadOnly();

    public record Decision(boolean readOnly, String riskLevel, String source) {

        public boolean mutating() {
            return !readOnly;
        }
    }

    private final Map<String, Boolean> overrides;
    private final Map<String, Boolean> mcpReadOnlyTools;
    private final boolean trustReadOnlyHints;

    public ToolSafety(
            Map<String, String> overrides,
            boolean trustReadOnlyHints,
            Map<String, Boolean> mcpReadOnlyTools) {
        this.overrides = normalizeOverrides(overrides);
        this.trustReadOnlyHints = trustReadOnlyHints;
        this.mcpReadOnlyTools =
                mcpReadOnlyTools == null ? Map.of() : Map.copyOf(mcpReadOnlyTools);
    }

    public static ToolSafety defaults() {
        return new ToolSafety(Map.of(), true, Map.of());
    }

    public Decision classify(
            String toolName,
            boolean mcp,
            boolean declaredReadOnly,
            boolean destructiveHint,
            Map<String, Object> args) {
        if (toolName == null || toolName.isBlank()) {
            return new Decision(false, ActionProposal.RISK_NORMAL, SOURCE_DEFAULT_MUTATING);
        }
        Boolean override = overrides.get(toolName);
        if (override != null) {
            return decision(override, SOURCE_OVERRIDE, destructiveHint, args);
        }
        Boolean builtinReadOnly = BUILTIN_READ_ONLY.get(toolName);
        if (builtinReadOnly != null) {
            return decision(builtinReadOnly, SOURCE_HINT_READ_ONLY, false, args);
        }
        if (mcp) {
            if (!trustReadOnlyHints) {
                return decision(false, SOURCE_DEFAULT_MUTATING, destructiveHint, args);
            }
            boolean readOnly =
                    Boolean.TRUE.equals(mcpReadOnlyTools.get(toolName)) || declaredReadOnly;
            return decision(
                    readOnly,
                    readOnly ? SOURCE_HINT_READ_ONLY : SOURCE_DEFAULT_MUTATING,
                    destructiveHint,
                    args);
        }
        return decision(
                declaredReadOnly,
                declaredReadOnly ? SOURCE_HINT_READ_ONLY : SOURCE_DEFAULT_MUTATING,
                false,
                args);
    }

    public boolean overridden(String toolName) {
        return toolName != null && overrides.containsKey(toolName);
    }

    /** 生效的本地覆盖，供启动审计逐条留痕。 */
    public Map<String, Boolean> overrides() {
        return overrides;
    }

    /** 配置层强制只读的 MCP 工具，供启动审计逐条留痕。 */
    public Map<String, Boolean> mcpReadOnlyTools() {
        return mcpReadOnlyTools;
    }

    private static Decision decision(
            boolean readOnly, String source, boolean destructiveHint, Map<String, Object> args) {
        if (readOnly) {
            return new Decision(true, ActionProposal.RISK_NORMAL, source);
        }
        if (destructiveHint) {
            return new Decision(false, ActionProposal.RISK_IRREVERSIBLE, source);
        }
        return new Decision(false, riskOf(args), source);
    }

    private static String riskOf(Map<String, Object> args) {
        return containsAmount(args, 0) ? ActionProposal.RISK_AMOUNT : ActionProposal.RISK_NORMAL;
    }

    private static boolean containsAmount(Object value, int depth) {
        if (value == null || depth > 3) {
            return false;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                if (key.contains("amount")
                        || key.contains("adjust")
                        || key.contains("salary")
                        || key.contains("bonus")) {
                    return true;
                }
                if (containsAmount(entry.getValue(), depth + 1)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (containsAmount(item, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, Boolean> normalizeOverrides(Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return Map.of();
        }
        Map<String, Boolean> result = new LinkedHashMap<>();
        overrides.forEach(
                (name, value) -> {
                    if (name == null || name.isBlank()) {
                        return;
                    }
                    String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
                    switch (normalized) {
                        case "READ_ONLY", "READONLY", "RO" -> result.put(name.trim(), true);
                        case "MUTATING", "WRITE" -> result.put(name.trim(), false);
                        default ->
                                log.warn(
                                        "[工具安全] 忽略无效覆盖：{}={}，只接受 READ_ONLY / MUTATING",
                                        name,
                                        value);
                    }
                });
        return Map.copyOf(result);
    }

    /** 内置工具以注解为唯一来源，新增方法无需改本类。 */
    private static Map<String, Boolean> scanBuiltinReadOnly() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (Class<?> type : new Class<?>[] {PayrollTools.class, KbTools.class}) {
            for (Method method : type.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool != null) {
                    result.put(tool.name(), tool.readOnly());
                }
            }
        }
        return Map.copyOf(result);
    }
}
