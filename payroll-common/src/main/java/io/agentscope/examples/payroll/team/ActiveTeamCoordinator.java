package io.agentscope.examples.payroll.team;

import io.agentscope.examples.payroll.domain.ChatToolTrace;
import io.agentscope.examples.payroll.service.PayrollChat;
import java.util.List;

/**
 * ACTIVE 动态团队的运行结算入口。
 *
 * <p>会话层只依赖该接口；真实实现位于智能体运行期，Web 节点使用 {@link #disabled()}。
 */
public interface ActiveTeamCoordinator {

    default boolean enabled() {
        return false;
    }

    default void completeSuccess(
            PayrollChat.TeamReply team,
            long messageId,
            List<PayrollChat.StageRun> stages,
            List<ChatToolTrace> traces) {}

    default void completeFallback(
            PayrollChat.TeamReply team, long messageId, long staticRunId) {}

    static ActiveTeamCoordinator disabled() {
        return new ActiveTeamCoordinator() {};
    }
}
