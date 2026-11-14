package io.agentscope.examples.payroll.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单轮检索额度：相同请求复用首次结果，不同请求合计最多 {@link #MAX_SEARCHES} 次。
 *
 * <p>由对话层每轮写入 {@link io.agentscope.core.agent.RuntimeContext} 并在轮次开始时清空，
 * 同一轮的多个团队节点共享同一实例。只有成功的后端响应进入缓存，失败的调用仍占用额度，
 * 避免模型重试放大下游压力。
 */
public final class KbSearchBudget {

    public static final int MAX_SEARCHES = 3;

    private final int maxSearches;
    private final Map<String, String> cached = new LinkedHashMap<>();
    private int used;

    public KbSearchBudget() {
        this(MAX_SEARCHES);
    }

    public KbSearchBudget(int maxSearches) {
        this.maxSearches = Math.max(1, maxSearches);
    }

    /**
     * 申请一次检索：命中缓存直接回放，超出额度拒绝，否则占用一次额度。
     *
     * @param key 由查询词、库集合与 top_k 归一化后的缓存键
     */
    public synchronized Slot begin(String key) {
        String cachedResult = cached.get(key);
        if (cachedResult != null) {
            return new Slot(true, true, cachedResult);
        }
        if (used >= maxSearches) {
            return new Slot(false, false, null);
        }
        used++;
        return new Slot(true, false, null);
    }

    /** 缓存成功的后端响应（含无命中），同轮再次请求相同条件时直接回放。 */
    public synchronized void succeed(String key, String result) {
        if (result != null && !result.isBlank()) {
            cached.put(key, result);
        }
    }

    public synchronized void clear() {
        cached.clear();
        used = 0;
    }

    public synchronized int used() {
        return used;
    }

    public int limit() {
        return maxSearches;
    }

    public record Slot(boolean allowed, boolean cached, String result) {
    }
}
