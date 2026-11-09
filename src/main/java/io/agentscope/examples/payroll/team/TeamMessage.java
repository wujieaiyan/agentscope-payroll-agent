package io.agentscope.examples.payroll.team;

import io.agentscope.examples.payroll.service.PayrollChatService.CalcTarget;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点间结构化消息：沿选中边传递，下游只读。
 *
 * <p>text 是面向模型的可读摘要；targets/traceIds/artifacts 供代码侧审计与回查，
 * 金额只能来自工具追溯，禁止由模型写入。
 */
public record TeamMessage(
        String schemaVersion,
        String runId,
        String planId,
        String fromNodeId,
        String fromAgentId,
        String fromRole,
        String toNodeId,
        String text,
        List<CalcTarget> targets,
        boolean toolError,
        List<Long> traceIds,
        Map<String, Object> artifacts,
        long createdAt) {

    public static final String SCHEMA_VERSION = "1";
    public static final int MAX_TEXT = 8000;
    public static final int MAX_TRACE_IDS = 50;

    public TeamMessage {
        schemaVersion =
                schemaVersion == null || schemaVersion.isBlank()
                        ? SCHEMA_VERSION
                        : schemaVersion.trim();
        runId = runId == null ? "" : runId;
        planId = planId == null ? "" : planId;
        fromNodeId = fromNodeId == null ? "" : fromNodeId;
        fromAgentId = fromAgentId == null ? "" : fromAgentId;
        fromRole = fromRole == null ? "" : fromRole;
        toNodeId = toNodeId == null ? "" : toNodeId;
        text = text == null ? "" : text;
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT) + "…";
        }
        targets = targets == null ? List.of() : List.copyOf(targets);
        traceIds =
                traceIds == null
                        ? List.of()
                        : traceIds.stream()
                                .filter(id -> id != null && id > 0)
                                .limit(MAX_TRACE_IDS)
                                .toList();
        artifacts = artifacts == null ? Map.of() : Map.copyOf(artifacts);
    }

    public boolean versionSupported() {
        return SCHEMA_VERSION.equals(schemaVersion);
    }

    /** 同一份上游产出发给另一个节点：内容不可变，只替换接收方。 */
    public TeamMessage to(String targetNodeId) {
        return new TeamMessage(
                schemaVersion,
                runId,
                planId,
                fromNodeId,
                fromAgentId,
                fromRole,
                targetNodeId,
                text,
                targets,
                toolError,
                traceIds,
                artifacts,
                createdAt);
    }

    /** 从工具追溯汇总 artifacts 摘要；金额由工具结果提取，不经过模型。 */
    public static Map<String, Object> artifactsOf(
            List<io.agentscope.examples.payroll.domain.ChatToolTrace> traces, boolean toolError) {
        Map<String, Object> artifacts = new LinkedHashMap<>();
        Map<String, Object> amounts = new LinkedHashMap<>();
        if (traces != null) {
            for (io.agentscope.examples.payroll.domain.ChatToolTrace trace : traces) {
                trace.amounts().forEach((key, value) -> amounts.put(key, value.toPlainString()));
            }
        }
        artifacts.put("amounts", amounts);
        artifacts.put("tool_calls", traces == null ? 0 : traces.size());
        artifacts.put("tool_error", toolError);
        return artifacts;
    }
}
