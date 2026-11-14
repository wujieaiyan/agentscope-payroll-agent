package io.agentscope.examples.payroll.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.examples.payroll.domain.Citation;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 从事件流累积冷恢复快照：只保留展示所需的最小状态，避免重放整段历史。 */
final class TurnSnapshotState {

    /** 单轮快照文本上限；超出时保留最近的尾部，最终回答仍以落库消息为准。 */
    private static final int TEXT_LIMIT = 20_000;

    private static final int ITEM_LIMIT = 50;

    private long seq;
    private final StringBuilder text = new StringBuilder();
    private final LinkedHashSet<String> tools = new LinkedHashSet<>();
    private final LinkedHashSet<String> agents = new LinkedHashSet<>();
    private String phase;
    private List<Citation> citations = List.of();

    synchronized void apply(long eventSeq, String event, Map<String, Object> data) {
        if (event == null || eventSeq <= seq) {
            return;
        }
        seq = eventSeq;
        if (data == null) {
            return;
        }
        switch (event) {
            case "text" -> appendText(text(data.get("text")));
            case "phase" -> phase = text(data.get("label"));
            case "tool_start" -> add(tools, text(data.get("name")));
            case "agent_start" -> add(agents, text(data.get("agent")));
            case "citation" -> citations = citations(data.get("citations"));
            case "done" -> {
                String reply = text(data.get("reply"));
                if (!reply.isBlank()) {
                    text.setLength(0);
                    appendText(reply);
                }
            }
            default -> {
            }
        }
    }

    synchronized TurnEventBus.TurnSnapshot toSnapshot() {
        return new TurnEventBus.TurnSnapshot(
                seq,
                text.toString(),
                List.copyOf(tools),
                List.copyOf(agents),
                phase,
                citations);
    }

    private void appendText(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        text.append(chunk);
        int excess = text.length() - TEXT_LIMIT;
        if (excess > 0) {
            text.delete(0, excess);
        }
    }

    private static void add(LinkedHashSet<String> target, String value) {
        if (value == null || value.isBlank() || target.size() >= ITEM_LIMIT) {
            return;
        }
        target.add(value);
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static List<Citation> citations(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        try {
            return JsonSupport.fromJson(
                    JsonSupport.toJson(list), new TypeReference<List<Citation>>() {
                    });
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
