package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.usage.LlmUsageQuery;
import io.agentscope.examples.payroll.usage.LlmUsageRecord;
import io.agentscope.examples.payroll.usage.LlmUsageStore;
import io.agentscope.examples.payroll.web.TooManyRequestsException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每用户每日 token 预算：0 表示不限；超限的新轮次直接 429。
 *
 * <p>事实表异步写入，因此按用户做 30 秒进程内缓存，避免每轮都扫当天用量；
 * 缓存只影响“超出预算后多久开始拒绝”，不影响计费事实。
 */
public final class TokenBudgetService {

    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final LlmUsageStore store;
    private final long dailyBudget;

    private final Map<Long, CachedUsage> cache = new ConcurrentHashMap<>();

    public TokenBudgetService(LlmUsageStore store, long dailyBudget) {
        this.store = store;
        this.dailyBudget = Math.max(0, dailyBudget);
    }

    public boolean enabled() {
        return dailyBudget > 0;
    }

    public long dailyBudget() {
        return dailyBudget;
    }

    /** 预算已用完时抛 429；未知/系统用户与关闭状态直接放行。 */
    public void check(long userId) {
        if (!enabled() || userId <= 0) {
            return;
        }
        long used = usedToday(userId, false);
        if (used >= dailyBudget) {
            throw new TooManyRequestsException(
                    "今日 token 预算已用完（" + used + "/" + dailyBudget + "），请明日再试或联系管理员");
        }
    }

    long usedToday(long userId, boolean refresh) {
        LocalDate today = LocalDate.now();
        CachedUsage cached = cache.get(userId);
        long now = System.currentTimeMillis();
        if (!refresh
                && cached != null
                && cached.day().equals(today)
                && now - cached.atMillis() < CACHE_TTL.toMillis()) {
            return cached.tokens();
        }
        long tokens = sum(userId, today);
        cache.put(userId, new CachedUsage(today, tokens, now));
        return tokens;
    }

    private long sum(long userId, LocalDate day) {
        LocalDateTime from = day.atStartOfDay();
        LocalDateTime to = day.plusDays(1).atStartOfDay();
        LlmUsageQuery query = new LlmUsageQuery(from, to, null, null, null, String.valueOf(userId));
        return store.find(query, LlmUsageService.MAX_ROWS).stream()
                .mapToLong(LlmUsageRecord::totalTokens)
                .sum();
    }

    private record CachedUsage(LocalDate day, long tokens, long atMillis) {
    }
}
