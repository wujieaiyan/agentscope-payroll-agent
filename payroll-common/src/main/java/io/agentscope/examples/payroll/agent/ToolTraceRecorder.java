package io.agentscope.examples.payroll.agent;

import io.agentscope.examples.payroll.support.ToolTraceExtractor;
import io.agentscope.examples.payroll.support.ToolTraceExtractor.Evidence;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * 收集本次对话内工具调用的参数与结果，供阶段结束时与 ToolCall 事件配对。
 *
 * <p>AgentScope 2.0.1 的 {@code ToolResultEndEvent} 不携带结果内容，因此结果只能在工具方法内采集。
 * 工具按调用顺序串行执行，配对时按工具名取最早一条未认领记录。
 */
public final class ToolTraceRecorder {

    public static final int MAX_RESULT_CHARS = 8 * 1024;

    public record Entry(
            String toolName,
            String argsJson,
            String resultJson,
            boolean truncated,
            long durationMs,
            List<String> employees,
            String period,
            String department,
            Map<String, BigDecimal> amounts) {

        public Entry {
            employees = employees == null ? List.of() : List.copyOf(employees);
            amounts = amounts == null ? Map.of() : Map.copyOf(amounts);
        }
    }

    private final Deque<Entry> pending = new ArrayDeque<>();

    /** 工具执行结束后写入；结果超过 8KB 时截断并标记，金额提取始终基于完整结果。 */
    public synchronized void record(
            String toolName, String argsJson, String resultJson, long durationMs) {
        Evidence evidence = ToolTraceExtractor.extract(argsJson, resultJson);
        boolean truncated = resultJson != null && resultJson.length() > MAX_RESULT_CHARS;
        String stored =
                truncated
                        ? resultJson.substring(0, MAX_RESULT_CHARS) + "…（已截断）"
                        : resultJson;
        pending.addLast(
                new Entry(
                        toolName,
                        argsJson,
                        stored,
                        truncated,
                        Math.max(0, durationMs),
                        evidence.employees(),
                        evidence.period(),
                        evidence.department(),
                        evidence.amounts()));
    }

    /** 取该工具最早一条尚未认领的记录；MCP 等外部工具没有记录时返回 null。 */
    public synchronized Entry take(String toolName) {
        if (toolName == null) {
            return null;
        }
        var iterator = pending.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (toolName.equals(entry.toolName())) {
                iterator.remove();
                return entry;
            }
        }
        return null;
    }

    public synchronized void clear() {
        pending.clear();
    }

    public synchronized int pendingCount() {
        return pending.size();
    }
}
