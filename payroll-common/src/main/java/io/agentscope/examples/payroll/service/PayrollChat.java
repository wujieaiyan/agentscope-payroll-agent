package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.domain.ActionProposal;
import io.agentscope.examples.payroll.domain.ChatRouting;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.domain.Citation;
import io.agentscope.examples.payroll.domain.TraceScope;
import io.agentscope.examples.payroll.support.ModelEnv;
import java.util.List;

/** 管理台对话入口，便于在测试中替换为固定实现。 */
public interface PayrollChat extends AutoCloseable {

    Reply chat(String message);

    /** 注入本轮团队运行坐标；未启用动态团队时为空实现。 */
    default void useRunScope(TraceScope scope) {
    }

    /** 本轮已执行/跳过的阶段摘要，供会话层落团队运行记录。 */
    default List<StageRun> stageRuns() {
        return List.of();
    }

    /**
     * 流式对话。默认实现退化为一次性回答，便于测试替身只实现 {@link #chat(String)}。
     */
    default void stream(String message, ChatStreamListener listener) {
        listener.onStart(modelId());
        Reply reply = chat(message);
        listener.onText(reply.reply());
        listener.onDone(reply);
    }

    default String modelId() {
        return ModelEnv.modelId();
    }

    default void reset() {
    }

    @Override
    default void close() {
    }

    /**
     * 一次对话结果；tools 为本次回答调用过的工具名，traces 为逐次调用的依据快照，
     * routing 为本轮路由快照，proposals 为待用户确认的写操作，
     * citations 为本轮知识库引用，cache 非空表示本轮答复来自缓存。
     */
    record Reply(
            String reply,
            List<String> tools,
            String model,
            List<ChatToolTrace> traces,
            TeamReply team,
            ChatRouting routing,
            List<ActionProposal> proposals,
            CacheInfo cache,
            List<Citation> citations) {

        public Reply {
            tools = tools == null ? List.of() : List.copyOf(tools);
            traces = traces == null ? List.of() : List.copyOf(traces);
            proposals = proposals == null ? List.of() : List.copyOf(proposals);
            citations = citations == null ? List.of() : List.copyOf(citations);
        }

        public Reply(
                String reply,
                List<String> tools,
                String model,
                List<ChatToolTrace> traces,
                TeamReply team,
                ChatRouting routing,
                List<ActionProposal> proposals,
                CacheInfo cache) {
            this(reply, tools, model, traces, team, routing, proposals, cache, List.of());
        }

        public Reply(String reply, List<String> tools, String model) {
            this(reply, tools, model, List.of(), null, null, List.of(), null);
        }

        public Reply(
                String reply, List<String> tools, String model, List<ChatToolTrace> traces) {
            this(reply, tools, model, traces, null, null, List.of(), null);
        }

        public Reply(
                String reply,
                List<String> tools,
                String model,
                List<ChatToolTrace> traces,
                TeamReply team) {
            this(reply, tools, model, traces, team, null, List.of(), null);
        }
    }

    /** 缓存命中信息；type 为 exact 或 semantic，score 为相似度（精确命中为 1.0）。 */
    record CacheInfo(String type, double score) {
        public CacheInfo {
            type = type == null ? "exact" : type;
        }
    }

    /** ACTIVE 模式本轮团队的运行坐标；静态链路回答时为 null。 */
    record TeamReply(
            long runId,
            String planId,
            String mode,
            String status,
            String templateId,
            String fallbackReason,
            List<String> agentIds) {

        public TeamReply {
            planId = planId == null ? "" : planId;
            mode = mode == null ? "" : mode;
            status = status == null ? "" : status;
            templateId = templateId == null ? "" : templateId;
            agentIds = agentIds == null ? List.of() : List.copyOf(agentIds);
        }

        public boolean succeeded() {
            return "SUCCESS".equals(status);
        }
    }

    /** 单阶段运行摘要；由静态流水线或流程节点在结束时写入。 */
    record StageRun(
            String nodeId,
            String stageKey,
            String agentId,
            String role,
            String status,
            int toolCalls,
            int toolErrors,
            Boolean validationPassed,
            int outputChars,
            long durationMs,
            String errorMessage) {

        public StageRun {
            stageKey = stageKey == null ? "" : stageKey;
            nodeId = nodeId == null || nodeId.isBlank() ? stageKey : nodeId;
            status = status == null ? "SUCCESS" : status;
        }
    }
}
