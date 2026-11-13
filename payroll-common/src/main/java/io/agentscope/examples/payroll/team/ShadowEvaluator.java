package io.agentscope.examples.payroll.team;

/**
 * 影子团队的旁路评估入口。
 *
 * <p>会话层只依赖该接口；真实实现位于智能体运行期，Web 节点使用 {@link #disabled()}。
 */
public interface ShadowEvaluator {

    default boolean enabled() {
        return false;
    }

    void trigger(
            long userId,
            long sessionId,
            long messageId,
            String question,
            Long baselineRunId);

    static ShadowEvaluator disabled() {
        return (userId, sessionId, messageId, question, baselineRunId) -> {};
    }
}
