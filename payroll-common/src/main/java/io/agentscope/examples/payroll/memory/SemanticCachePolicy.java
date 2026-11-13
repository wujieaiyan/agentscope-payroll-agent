package io.agentscope.examples.payroll.memory;

import io.agentscope.examples.payroll.domain.ChatRouting;
import java.util.Collection;

/**
 * 可缓存条件：稳定、只读、无工具调用的单智能体问答。
 *
 * <p>核算流水线、流程编排、动态团队与任何写操作/提案一律不进入缓存。
 */
public final class SemanticCachePolicy {

    private static final int MAX_ANSWER_CHARS = 4000;

    private SemanticCachePolicy() {
    }

    public static boolean cacheable(
            String path, Collection<String> tools, boolean hasProposal, String reply) {
        return ChatRouting.AGENT.equals(path)
                && (tools == null || tools.isEmpty())
                && !hasProposal
                && reply != null
                && !reply.isBlank()
                && reply.length() <= MAX_ANSWER_CHARS;
    }
}
