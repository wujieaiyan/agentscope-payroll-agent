package io.agentscope.examples.payroll.agent;

/**
 * 单轮技能文件读取额度：默认 5 次，由对话层每轮写入
 * {@link io.agentscope.core.agent.RuntimeContext} 并在轮次开始时清空。
 *
 * <p>与知识库检索预算相互独立：参考文件是短文本，制度原文仍走 {@code kb_search}。
 */
public final class SkillReadBudget {

    public static final int DEFAULT_MAX_READS = 5;

    private final int maxReads;
    private int used;

    public SkillReadBudget() {
        this(fromEnvironment());
    }

    public SkillReadBudget(int maxReads) {
        this.maxReads = Math.max(1, maxReads);
    }

    /** 读取 {@code PAYROLL_SKILL_READ_MAX_PER_TURN}，非法值回退默认 5。 */
    public static int fromEnvironment() {
        String value = System.getenv("PAYROLL_SKILL_READ_MAX_PER_TURN");
        if (value == null || value.isBlank()) {
            return DEFAULT_MAX_READS;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : DEFAULT_MAX_READS;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_READS;
        }
    }

    public synchronized boolean acquire() {
        if (used >= maxReads) {
            return false;
        }
        used++;
        return true;
    }

    public synchronized void clear() {
        used = 0;
    }

    public synchronized int used() {
        return used;
    }

    public int limit() {
        return maxReads;
    }
}
