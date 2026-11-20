package io.agentscope.payroll.runtime;

import io.agentscope.examples.payroll.profile.UserProfileModelClient;
import io.agentscope.examples.payroll.team.TeamLlmClient;

/**
 * 复用团队控制面轻量模型做偏好抽取。提示词要求输出 {@code {"items":[...]}} 对象，
 * 与 {@link TeamLlmClient#completeJson} 的对象截取保持一致。
 */
final class TeamProfileModelClient implements UserProfileModelClient {

    private final TeamLlmClient client;

    TeamProfileModelClient(TeamLlmClient client) {
        this.client = client;
    }

    @Override
    public boolean enabled() {
        return client != null && client.enabled();
    }

    @Override
    public String extract(String instruction, String question, String sessionKey) {
        return client == null ? null : client.completeJson(instruction, question, sessionKey);
    }
}
