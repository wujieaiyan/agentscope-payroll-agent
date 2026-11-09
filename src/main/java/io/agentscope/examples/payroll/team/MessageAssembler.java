package io.agentscope.examples.payroll.team;

import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.domain.TraceScope;
import io.agentscope.examples.payroll.service.AgentFlowExecutor;
import io.agentscope.examples.payroll.service.PayrollChatService.CalcTarget;
import io.agentscope.examples.payroll.service.PayrollChatService.DynamicFlowResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 组装并渲染 {@link TeamMessage}，保持既有【用户问题】/【上游记录】段落格式。
 *
 * <p>P1 只做纯文本兼容：模型看到渲染文本，代码侧仍持有结构化字段用于落库审计。
 */
public final class MessageAssembler {

    private MessageAssembler() {
    }

    public static TeamMessage fromNode(
            TraceScope scope,
            String fromNodeId,
            String fromAgentId,
            String fromRole,
            String toNodeId,
            String text,
            List<CalcTarget> targets,
            boolean toolError,
            List<ChatToolTrace> nodeTraces) {
        List<Long> traceIds =
                nodeTraces == null
                        ? List.of()
                        : nodeTraces.stream().map(ChatToolTrace::id).toList();
        return new TeamMessage(
                TeamMessage.SCHEMA_VERSION,
                scope == null || scope.runId() <= 0 ? "" : String.valueOf(scope.runId()),
                scope == null ? "" : scope.planId(),
                fromNodeId,
                fromAgentId,
                fromRole,
                toNodeId,
                text,
                targets,
                toolError,
                traceIds,
                TeamMessage.artifactsOf(nodeTraces, toolError),
                System.currentTimeMillis());
    }

    /** 一次动态运行的所有节点消息，按计划节点顺序排列。 */
    public static List<TeamMessage> ofRun(
            TeamPlan plan, TraceScope scope, DynamicFlowResult result, Map<String, String> roles) {
        List<TeamMessage> messages = new ArrayList<>();
        if (plan == null || result == null) {
            return messages;
        }
        for (TeamPlan.PlanNode node : plan.agentNodes()) {
            AgentFlowExecutor.NodeResult nodeResult = result.nodeResults().get(node.id());
            if (nodeResult == null) {
                continue;
            }
            List<ChatToolTrace> nodeTraces =
                    result.traces().stream()
                            .filter(
                                    trace ->
                                            node.id().equals(trace.nodeId())
                                                    || node.agentId().equals(trace.agent()))
                            .toList();
            messages.add(
                    fromNode(
                            scope,
                            node.id(),
                            node.agentId(),
                            roles.getOrDefault(node.id(), node.role()),
                            "",
                            nodeResult.text(),
                            nodeResult.targets(),
                            nodeResult.toolError(),
                            nodeTraces));
        }
        return List.copyOf(messages);
    }

    /** 渲染下游节点的完整输入文本；上游顺序即计划执行顺序。 */
    public static String render(String question, List<TeamMessage> upstream) {
        if (upstream == null || upstream.isEmpty()) {
            return question == null ? "" : question;
        }
        StringBuilder text = new StringBuilder("【用户问题】\n");
        text.append(question == null ? "" : question);
        LinkedHashSet<String> targetLines = new LinkedHashSet<>();
        long errorCount = 0;
        LinkedHashSet<Long> traceIds = new LinkedHashSet<>();
        for (TeamMessage message : upstream) {
            text.append("\n\n【").append(title(message)).append("记录】\n");
            text.append(message.text().isBlank() ? "（本节点未返回内容）" : message.text().trim());
            for (CalcTarget target : message.targets()) {
                targetLines.add("- " + target.employeeId() + " | " + target.period());
            }
            if (message.toolError()) {
                errorCount++;
            }
            traceIds.addAll(message.traceIds());
        }
        if (!targetLines.isEmpty()) {
            text.append("\n\n【上游核算对象（最多处理前 3 个）】\n");
            int limit = 0;
            for (String line : targetLines) {
                if (limit++ == 3) {
                    break;
                }
                text.append(line).append('\n');
            }
        }
        text.append("\n\n【上游结构化附件】\n");
        text.append("- targets: ").append(targetLines.size()).append('\n');
        text.append("- toolError: ").append(errorCount > 0).append('\n');
        text.append("- traceIds: ");
        text.append(
                traceIds.isEmpty()
                        ? "无"
                        : traceIds.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b)
                                .orElse(""));
        text.append("\n\n请按你的角色职责处理以上记录。");
        return text.toString();
    }

    /** 段落标题去掉编号，与角色技能中的固定标题保持一致。 */
    public static String title(TeamMessage message) {
        TeamRole role = TeamRole.parse(message.fromRole());
        if (role != null) {
            return role.label();
        }
        if (!message.fromRole().isBlank()) {
            return message.fromRole();
        }
        return message.fromNodeId().isBlank() ? "上游" : message.fromNodeId();
    }
}
