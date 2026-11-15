package io.agentscope.examples.payroll.router;

import io.agentscope.examples.payroll.agent.KbRoutingPolicy;
import io.agentscope.examples.payroll.agent.PayrollRouteBucket;
import io.agentscope.examples.payroll.agent.PayrollRouteCache;
import io.agentscope.examples.payroll.agent.PayrollRouteMode;
import io.agentscope.examples.payroll.agent.RouteIntentClassifier;
import io.agentscope.examples.payroll.agent.SemanticRouteClassifier;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 薪酬语义路由（规格 7）：语义层只识别桶，决策仍来自既有 LLM 路由。
 *
 * <p>{@code shadow} 档调用 LLM 且只记录比对结果；{@code cache} 档仅在桶、签名与问句都可复用时
 * 复用缓存，否则照常调用 LLM，并在"连续两次一致"后写入缓存。任何异常都 fail-open。
 */
public final class PayrollRouteAdvisor {

    private static final Logger log = LoggerFactory.getLogger(PayrollRouteAdvisor.class);

    public record Outcome(
            RouteDecision decision,
            boolean cacheHit,
            boolean shadowDiff,
            String bucket,
            SemanticRouteClassifier.Decision semantic) {

        public static Outcome direct(RouteDecision decision) {
            return new Outcome(decision, false, false, "", null);
        }
    }

    private final PayrollRouteMode mode;
    private final RouteIntentClassifier semantic;
    private final PayrollRouteCache cache;
    private final String modelId;
    private final int cacheUnsafeMaxChars;

    public PayrollRouteAdvisor(
            PayrollRouteMode mode,
            RouteIntentClassifier semantic,
            PayrollRouteCache cache,
            String modelId,
            int cacheUnsafeMaxChars) {
        this.mode = mode == null ? PayrollRouteMode.OFF : mode;
        this.semantic = semantic;
        this.cache = cache == null ? PayrollRouteCache.disabled() : cache;
        this.modelId = modelId == null ? "" : modelId;
        this.cacheUnsafeMaxChars = cacheUnsafeMaxChars <= 0 ? 20 : cacheUnsafeMaxChars;
    }

    public static PayrollRouteAdvisor disabled() {
        return new PayrollRouteAdvisor(
                PayrollRouteMode.OFF, null, PayrollRouteCache.disabled(), "", 20);
    }

    public PayrollRouteMode mode() {
        return mode;
    }

    public RouteIntentClassifier semantic() {
        return semantic;
    }

    /**
     * @param flowBinding 会话流程绑定；无绑定传空串
     * @param personaBinding 会话角色绑定（智能体 id）；无绑定传空串
     * @param llm 既有 LLM 路由调用，必须由本方法决定是否触发
     */
    public Outcome decide(
            String question,
            List<AgentDefinition> candidates,
            boolean teamAvailable,
            String flowBinding,
            String personaBinding,
            Supplier<RouteDecision> llm) {
        Objects.requireNonNull(llm, "llm");
        if (!mode.semanticEnabled() || semantic == null || !semantic.ready()) {
            return Outcome.direct(llm.get());
        }
        SemanticRouteClassifier.Decision classified;
        try {
            classified = semantic.classify(question);
        } catch (RuntimeException e) {
            log.warn("[薪酬语义] 分类异常，回退模型路由：{}", e.getMessage());
            return new Outcome(llm.get(), false, false, "", null);
        }
        boolean usableBucket = classified.confident() && PayrollRouteBucket.cacheable(classified.label());
        boolean safeQuestion = !KbRoutingPolicy.routeCacheUnsafe(question, cacheUnsafeMaxChars);
        if (!usableBucket || !safeQuestion) {
            if (mode == PayrollRouteMode.CACHE) {
                log.info(
                        "[薪酬语义] 不复用：bucket={}，confident={}，safe_question={}，question={}",
                        classified.label(), classified.confident(), safeQuestion, preview(question));
            }
            return new Outcome(llm.get(), false, false, classified.label(), classified);
        }
        PayrollRouteCache.DecisionKey key =
                new PayrollRouteCache.DecisionKey(
                        classified.label(),
                        signature(candidates),
                        flowBinding,
                        personaBinding,
                        teamAvailable,
                        modelId);
        if (mode == PayrollRouteMode.SHADOW) {
            return shadow(question, candidates, key, classified, llm);
        }
        return cached(question, candidates, key, classified, llm);
    }

    private Outcome shadow(
            String question,
            List<AgentDefinition> candidates,
            PayrollRouteCache.DecisionKey key,
            SemanticRouteClassifier.Decision classified,
            Supplier<RouteDecision> llm) {
        Optional<PayrollRouteCache.Entry> cached = cache.peek(key);
        RouteDecision decision = llm.get();
        boolean diff = cached.map(entry -> !same(entry, decision)).orElse(false);
        log.info(
                "[薪酬语义][影子] bucket={}，score={}，margin={}，cache={}，diff={}，target={}，agent={}，question={}",
                classified.label(),
                String.format(Locale.ROOT, "%.3f", classified.score()),
                String.format(Locale.ROOT, "%.3f", classified.margin()),
                cached.isPresent() ? "hit" : "miss",
                diff,
                decision == null || decision.target() == null ? "-" : decision.target(),
                decision == null || decision.agent() == null ? "-" : decision.agent().id(),
                preview(question));
        return new Outcome(decision, false, diff, classified.label(), classified);
    }

    private Outcome cached(
            String question,
            List<AgentDefinition> candidates,
            PayrollRouteCache.DecisionKey key,
            SemanticRouteClassifier.Decision classified,
            Supplier<RouteDecision> llm) {
        Optional<PayrollRouteCache.Entry> hit = safeLookup(key);
        if (hit.isPresent()) {
            RouteDecision reused = materialize(hit.get(), candidates);
            if (reused != null) {
                log.info(
                        "[薪酬语义] 命中决策缓存：bucket={}，score={}，target={}，agent={}，question={}",
                        classified.label(),
                        String.format(Locale.ROOT, "%.3f", classified.score()),
                        reused.target(),
                        reused.agent() == null ? "-" : reused.agent().id(),
                        preview(question));
                return new Outcome(reused, true, false, classified.label(), classified);
            }
        }
        RouteDecision decision = llm.get();
        if (decision != null && decision.routed()) {
            PayrollRouteCache.Entry entry =
                    new PayrollRouteCache.Entry(
                            decision.target(),
                            decision.agent() == null ? "" : decision.agent().id(),
                            decision.reason(),
                            decision.confidence());
            cache.record(key, entry);
        }
        log.info(
                "[薪酬语义] 未命中，沿用模型决策：bucket={}，target={}，agent={}，question={}",
                classified.label(),
                decision == null || decision.target() == null ? "-" : decision.target(),
                decision == null || decision.agent() == null ? "-" : decision.agent().id(),
                preview(question));
        return new Outcome(decision, false, false, classified.label(), classified);
    }

    private Optional<PayrollRouteCache.Entry> safeLookup(PayrollRouteCache.DecisionKey key) {
        try {
            return cache.lookup(key);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 缓存值必须仍能落到当前目录；智能体已下线时视为失效，回退 LLM。 */
    private static RouteDecision materialize(
            PayrollRouteCache.Entry entry, List<AgentDefinition> candidates) {
        String target = entry.target();
        if (RouteDecision.TARGET_AGENT.equals(target)) {
            AgentDefinition agent =
                    candidates == null
                            ? null
                            : candidates.stream()
                                    .filter(item -> item.id().equals(entry.agentId()))
                                    .findFirst()
                                    .orElse(null);
            if (agent == null) {
                return null;
            }
            return new RouteDecision(
                    target,
                    agent,
                    entry.reason(),
                    entry.confidence(),
                    null,
                    false,
                    RouteDecision.KB_NONE,
                    RouteDecision.KB_SOURCE_CACHE);
        }
        if (RouteDecision.TARGET_TEAM.equals(target)
                || RouteDecision.TARGET_PIPELINE.equals(target)) {
            return new RouteDecision(
                    target,
                    null,
                    entry.reason(),
                    entry.confidence(),
                    null,
                    false,
                    RouteDecision.KB_NONE,
                    RouteDecision.KB_SOURCE_CACHE);
        }
        return null;
    }

    private static boolean same(PayrollRouteCache.Entry entry, RouteDecision decision) {
        if (decision == null || !decision.routed()) {
            return false;
        }
        String agentId = decision.agent() == null ? "" : decision.agent().id();
        return entry.target().equals(decision.target()) && entry.agentId().equals(agentId);
    }

    /** 目录签名：id/name/description/status/tags 排序后散列，任一字段变化都会换键。 */
    static String signature(List<AgentDefinition> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "empty";
        }
        String joined =
                candidates.stream()
                        .sorted(Comparator.comparing(AgentDefinition::id))
                        .map(
                                agent ->
                                        agent.id()
                                                + "|"
                                                + nullSafe(agent.name())
                                                + "|"
                                                + nullSafe(agent.description())
                                                + "|"
                                                + nullSafe(agent.status())
                                                + "|"
                                                + agent.tags())
                        .reduce("", (left, right) -> left + "\n" + right);
        return PayrollRouteCache.sha256(joined);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String preview(String text) {
        String flattened = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return flattened.length() <= 80 ? flattened : flattened.substring(0, 80) + "…";
    }
}
