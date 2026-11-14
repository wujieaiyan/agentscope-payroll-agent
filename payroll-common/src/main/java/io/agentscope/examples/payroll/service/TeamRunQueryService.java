package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.db.H2AgentTeamStore;
import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.domain.AgentRunRecord;
import io.agentscope.examples.payroll.domain.AgentTeamRun;
import io.agentscope.examples.payroll.domain.ChatFeedback;
import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.domain.MessageTeamView;
import java.util.List;
import java.util.Optional;

/** 按消息读取团队运行与追溯；访问前通过反馈服务完成会话归属与助手消息校验。 */
public final class TeamRunQueryService {

    private final H2AgentTeamStore teamStore;
    private final H2ChatStore chatStore;
    private final ChatFeedbackService feedbackService;

    public TeamRunQueryService(
            H2AgentTeamStore teamStore,
            H2ChatStore chatStore,
            ChatFeedbackService feedbackService) {
        this.teamStore = teamStore;
        this.chatStore = chatStore;
        this.feedbackService = feedbackService;
    }

    public MessageTeamView view(long userId, long sessionId, long messageId) {
        Optional<ChatFeedback> feedback =
                feedbackService.find(userId, sessionId, messageId);
        List<AgentTeamRun> runs = teamStore.runs(sessionId, messageId);
        AgentTeamRun primary =
                teamStore.primaryRun(sessionId, messageId).orElse(null);
        List<AgentRunRecord> nodes =
                primary == null ? List.of() : teamStore.records(primary.id());
        List<ChatToolTrace> traces =
                primary == null
                        ? List.of()
                        : chatStore.traces(sessionId, messageId).stream()
                                .filter(trace -> trace.runId() == primary.id())
                                .toList();
        return new MessageTeamView(
                sessionId,
                messageId,
                primary,
                runs,
                nodes,
                traces,
                feedback.orElse(null));
    }
}
