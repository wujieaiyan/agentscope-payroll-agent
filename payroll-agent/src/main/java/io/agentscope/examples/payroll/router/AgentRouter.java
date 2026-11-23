package io.agentscope.examples.payroll.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.examples.payroll.agent.PayrollAgentFactory;
import io.agentscope.examples.payroll.agent.PromptLibrary;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.team.TeamLlmClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 纯轻量模型路由：把用户问题分给单个自由智能体、动态团队或内置流水线。
 *
 * <p>不使用规则优先：模型是唯一决策者；模型不可用、输出非法或置信度低于阈值时返回
 * {@link RouteDecision#fallback(String)}，由调用方沿用既有静态链路。调用复用
 * {@link TeamLlmClient}（无工具、单轮、可配置超时），每次只输出一个 JSON 对象。
 */
public final class AgentRouter {

    private static final Logger log = LoggerFactory.getLogger(AgentRouter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int REASON_LIMIT = 200;
    private static final int DESCRIPTION_LIMIT = 120;
    private static final int PRIOR_QUESTION_LIMIT = 200;
    private static final int PRIOR_ANSWER_LIMIT = 300;
    private static final long DEFAULT_CACHE_TTL_SECONDS = 300L;
    private static final int DEFAULT_CACHE_MAX_ENTRIES = 512;

    private static final String INSTRUCTION = PromptLibrary.get("router-instruction");

    /** 仅追加在非薪酬问题上；薪酬问题继续使用 {@link #INSTRUCTION} 原文，保证 prompt 可比。 */
    private static final String KB_SECTION = PromptLibrary.get("router-kb-section");

    /** 非薪酬问题的创作类判定：小模型容易把带“写”字的请求一律当办公文书。 */
    private static final String CREATIVE_SECTION = PromptLibrary.get("router-creative-section");

    /** 单独开关：创作类问题不再顺带检索知识库，避免诗词答复引用制度资料。 */
    private static final String CREATIVE_NO_KB_SECTION =
            PromptLibrary.get("router-creative-no-kb-section");

    /** 上一轮执行记录：只用于多轮延续判定，不作为数据来源。 */
    public record Prior(String agentId, String question, String answer) {

        public Prior {
            agentId = agentId == null ? "" : agentId.trim();
            question = question == null ? "" : question.trim();
            answer = answer == null ? "" : answer.trim();
        }

        public static Prior none() {
            return new Prior("", "", "");
        }

        public boolean present() {
            return !agentId.isBlank() && (!question.isBlank() || !answer.isBlank());
        }
    }

    /** 多轮延续：上一轮由某智能体作答后，追问默认留在该智能体。 */
    private static final String CONTINUATION_SECTION =
            PromptLibrary.get("router-continuation-section");

    /** 模型调用抽象，便于单测注入固定输出。 */
    @FunctionalInterface
    public interface RouteModel {
        String complete(String instruction, String question, String sessionKey);

        static RouteModel disabled() {
            return (instruction, question, sessionKey) -> null;
        }
    }

    private final RouteModel model;
    private final boolean enabled;
    private final double minConfidence;
    private final boolean creativeNoKb;
    private final boolean cacheEnabled;
    private final long cacheTtlMillis;
    private final Map<String, CachedRoute> routeCache;

    public AgentRouter(
            TeamLlmClient llm, boolean enabled, double minConfidence, Duration timeout) {
        this(llm, enabled, minConfidence, timeout, false);
    }

    /**
     * @param creativeNoKb 创作类问题提示词：开启后非薪酬问题追加 {@code need_kb=false} 约束
     */
    public AgentRouter(
            TeamLlmClient llm,
            boolean enabled,
            double minConfidence,
            Duration timeout,
            boolean creativeNoKb) {
        this(llm, enabled, minConfidence, timeout, creativeNoKb, true,
                DEFAULT_CACHE_TTL_SECONDS, DEFAULT_CACHE_MAX_ENTRIES);
    }

    public AgentRouter(
            TeamLlmClient llm,
            boolean enabled,
            double minConfidence,
            Duration timeout,
            boolean creativeNoKb,
            boolean cacheEnabled,
            long cacheTtlSeconds,
            int cacheMaxEntries) {
        this(
                llm == null
                        ? RouteModel.disabled()
                        : (instruction, question, sessionKey) ->
                                llm.completeJson(instruction, question, sessionKey, timeout),
                enabled,
                minConfidence,
                creativeNoKb,
                cacheEnabled,
                cacheTtlSeconds,
                cacheMaxEntries);
    }

    AgentRouter(RouteModel model, boolean enabled, double minConfidence) {
        this(model, enabled, minConfidence, false);
    }

    AgentRouter(RouteModel model, boolean enabled, double minConfidence, boolean creativeNoKb) {
        this(
                model,
                enabled,
                minConfidence,
                creativeNoKb,
                true,
                DEFAULT_CACHE_TTL_SECONDS,
                DEFAULT_CACHE_MAX_ENTRIES);
    }

    AgentRouter(
            RouteModel model,
            boolean enabled,
            double minConfidence,
            boolean creativeNoKb,
            boolean cacheEnabled,
            long cacheTtlSeconds,
            int cacheMaxEntries) {
        this.model = model == null ? RouteModel.disabled() : model;
        this.enabled = enabled;
        this.minConfidence = Math.max(0D, Math.min(1D, minConfidence));
        this.creativeNoKb = creativeNoKb;
        this.cacheEnabled = cacheEnabled;
        this.cacheTtlMillis = Math.max(1L, cacheTtlSeconds) * 1000L;
        this.routeCache =
                cacheEnabled
                        ? new LruRouteCache(Math.max(1, cacheMaxEntries))
                        : null;
    }

    /** 访问序 LRU：容量满时淘汰最久未使用的条目。 */
    private static final class LruRouteCache extends LinkedHashMap<String, CachedRoute> {

        private final int maxEntries;

        LruRouteCache(int maxEntries) {
            super(16, 0.75f, true);
            this.maxEntries = maxEntries;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedRoute> eldest) {
            return size() > maxEntries;
        }
    }

    private record CachedRoute(RouteDecision decision, long insertedAt) {}

    public static AgentRouter disabled() {
        return new AgentRouter(RouteModel.disabled(), false, 0.55D);
    }

    public boolean enabled() {
        return enabled;
    }

    /** 可路由的单智能体：在线且不是内置流水线（计算技能）的智能体。 */
    public static List<AgentDefinition> candidates(List<AgentDefinition> catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return List.of();
        }
        return catalog.stream()
                .filter(agent -> AgentDefinition.STATUS_ONLINE.equals(agent.status()))
                .filter(agent -> !PayrollAgentFactory.isPipeline(agent))
                .toList();
    }

    /** 非薪酬兜底分桶：问题特征词 → 候选智能体的领域特征词。 */
    private record FallbackBucket(List<String> triggers, List<String> domains) {}

    private static final List<FallbackBucket> FALLBACK_BUCKETS =
            List.of(
                    new FallbackBucket(
                            List.of("诗", "歌词", "散文", "小说", "剧本", "创作", "续写"),
                            List.of("诗词", "文学", "创作")),
                    new FallbackBucket(
                            List.of("通知", "报告", "纪要", "邮件", "公文", "汇报", "总结", "方案"),
                            List.of("办公", "写作")),
                    new FallbackBucket(
                            List.of("数据", "指标", "分析", "报表", "趋势", "统计", "图表"),
                            List.of("数据", "分析")),
                    new FallbackBucket(
                            List.of("代码", "编程", "java", "python", "前端", "后端", "接口", "编译"),
                            List.of("编程", "研发")),
                    new FallbackBucket(
                            List.of("运维", "排障", "故障", "部署", "服务器", "日志", "报错"),
                            List.of("运维", "排障")),
                    new FallbackBucket(List.of("视频", "剪辑", "特效", "字幕"), List.of("视频")),
                    new FallbackBucket(
                            List.of(
                                    "你好", "您好", "hi", "hello", "在吗", "谢谢", "你是谁",
                                    "你能做什么", "闲聊", "聊天", "心情", "情感"),
                            List.of("情感", "闲聊", "通用", "助理")));

    /**
     * 路由不可用（模型超时/失败/低置信度）时的确定性兜底。
     *
     * <p>非薪酬问题不能一律落回薪酬流水线，否则问候和闲聊都会由薪酬计算智能体作答：
     * 优先沿用管理员配置/上一轮执行者，其次按问题特征选择领域最接近的候选；
     * 没有匹配时才返回 null，由调用方保留原回退链路。
     */
    public static AgentDefinition fallbackAgent(
            String question, List<AgentDefinition> agents, AgentDefinition preferred) {
        List<AgentDefinition> usable =
                agents == null
                        ? List.of()
                        : agents.stream()
                                .filter(agent -> agent != null && !PayrollAgentFactory.isPipeline(agent))
                                .toList();
        if (usable.isEmpty()) {
            return null;
        }
        if (preferred != null) {
            AgentDefinition kept =
                    usable.stream()
                            .filter(agent -> agent.id().equals(preferred.id()))
                            .findFirst()
                            .orElse(null);
            if (kept != null) {
                return kept;
            }
        }
        String text = question == null ? "" : question.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        FallbackBucket best = null;
        int bestHits = 0;
        for (FallbackBucket bucket : FALLBACK_BUCKETS) {
            int hits = (int) bucket.triggers().stream().filter(text::contains).count();
            if (hits > bestHits) {
                best = bucket;
                bestHits = hits;
            }
        }
        if (best == null) {
            return null;
        }
        for (String domain : best.domains()) {
            for (AgentDefinition agent : usable) {
                if (haystack(agent).contains(domain)) {
                    return agent;
                }
            }
        }
        return null;
    }

    private static String haystack(AgentDefinition agent) {
        return (nullSafe(agent.name())
                        + " "
                        + nullSafe(agent.description())
                        + " "
                        + String.join(" ", agent.tags()))
                .toLowerCase(Locale.ROOT);
    }

    /** 上一轮执行者仍在线且可路由时，作为本轮默认首选。 */
    private static AgentDefinition priorAgent(List<AgentDefinition> agents, Prior prior) {
        if (prior == null || !prior.present()) {
            return null;
        }
        return agents.stream().filter(agent -> agent.id().equals(prior.agentId())).findFirst()
                .orElse(null);
    }

    /**
     * 决策一次路由；未启用时返回 null，表示调用方不改变既有链路。
     */
    public RouteDecision decide(
            String question,
            List<AgentDefinition> candidates,
            boolean teamAvailable,
            String sessionKey) {
        return decide(question, candidates, teamAvailable, sessionKey, false);
    }

    /**
     * @param payroll 薪酬问题使用原 instruction，不追加知识库判定段
     */
    public RouteDecision decide(
            String question,
            List<AgentDefinition> candidates,
            boolean teamAvailable,
            String sessionKey,
            boolean payroll) {
        return decide(question, candidates, teamAvailable, sessionKey, payroll, null);
    }

    /**
     * @param preferred 管理员配置的当前活跃智能体，作为默认首选写进提示词；
     *     只有问题明显属于其他候选的专长时才允许模型切换，避免小模型按单字误判换人。
     */
    public RouteDecision decide(
            String question,
            List<AgentDefinition> candidates,
            boolean teamAvailable,
            String sessionKey,
            boolean payroll,
            AgentDefinition preferred) {
        return decide(question, candidates, teamAvailable, sessionKey, payroll, preferred, null);
    }

    /**
     * @param prior 上一轮执行记录；追问、续写类问题沿用上一轮执行者，优先于管理员活跃智能体
     */
    public RouteDecision decide(
            String question,
            List<AgentDefinition> candidates,
            boolean teamAvailable,
            String sessionKey,
            boolean payroll,
            AgentDefinition preferred,
            Prior prior) {
        if (!enabled) {
            return null;
        }
        List<AgentDefinition> agents = candidates == null ? List.of() : List.copyOf(candidates);
        Prior priorTurn = prior == null || !prior.present() ? Prior.none() : prior;
        AgentDefinition priorAgent = priorAgent(agents, priorTurn);
        AgentDefinition preferredAgent = priorAgent == null ? preferred : priorAgent;
        // 上一轮执行者已下架或不属于候选时，不再要求模型延续，按新任务处理。
        Prior instructionPrior = priorAgent == null ? Prior.none() : priorTurn;
        String instruction =
                instruction(
                        agents,
                        teamAvailable,
                        payroll,
                        preferredAgent,
                        creativeNoKb,
                        instructionPrior);
        String cacheKey =
                cacheEnabled
                        ? cacheKey(
                                instruction,
                                question,
                                agents,
                                preferredAgent,
                                teamAvailable,
                                payroll)
                        : null;
        long startedAt = System.currentTimeMillis();
        if (cacheKey != null) {
            RouteDecision cached = cachedDecision(cacheKey, agents, startedAt);
            if (cached != null) {
                return cached;
            }
        }
        String raw;
        try {
            raw = model.complete(instruction, question, sessionKey);
        } catch (RuntimeException e) {
            return logFallback("轻量模型调用失败：" + rootMessage(e), agents, teamAvailable, startedAt);
        }
        if (raw == null || raw.isBlank()) {
            return logFallback("轻量模型不可用或超时", agents, teamAvailable, startedAt);
        }
        RouteDecision decision = parse(raw, agents, teamAvailable);
        long elapsed = System.currentTimeMillis() - startedAt;
        if (decision.routed()) {
            log.info(
                    "[智能体路由] 决策：target={}，agent={}，置信度={}，need_kb={}，kb_kind={}，候选={}，团队可用={}，耗时={}ms，理由={}",
                    decision.target(),
                    decision.agent() == null ? "" : decision.agent().id(),
                    String.format(Locale.ROOT, "%.2f", decision.confidence()),
                    decision.needKb(),
                    decision.kbKind(),
                    agents.size(),
                    teamAvailable,
                    elapsed,
                    decision.reason());
            if (cacheKey != null && decision.agent() != null) {
                cacheDecision(cacheKey, decision);
            }
        } else {
            log.info(
                    "[智能体路由] 未生效：候选={}，团队可用={}，耗时={}ms，原因={}",
                    agents.size(),
                    teamAvailable,
                    elapsed,
                    decision.failureReason());
        }
        return decision;
    }

    /** 命中后按当前候选重新绑定智能体；过期或候选已删除视为未命中。 */
    private RouteDecision cachedDecision(
            String key, List<AgentDefinition> agents, long startedAt) {
        synchronized (routeCache) {
            CachedRoute cached = routeCache.get(key);
            if (cached == null) {
                return null;
            }
            if (System.currentTimeMillis() - cached.insertedAt() > cacheTtlMillis) {
                routeCache.remove(key);
                return null;
            }
            String agentId = cached.decision().agent().id();
            AgentDefinition rebound =
                    agents.stream()
                            .filter(agent -> agent.id().equals(agentId))
                            .findFirst()
                            .orElse(null);
            if (rebound == null) {
                routeCache.remove(key);
                return null;
            }
            long elapsed = System.currentTimeMillis() - startedAt;
            log.info(
                    "[智能体路由] 缓存命中：agent={}，耗时={}ms",
                    rebound.id(),
                    elapsed);
            return new RouteDecision(
                    cached.decision().target(),
                    rebound,
                    cached.decision().reason(),
                    cached.decision().confidence(),
                    null,
                    cached.decision().needKb(),
                    cached.decision().kbKind(),
                    cached.decision().kbSource());
        }
    }

    private void cacheDecision(String key, RouteDecision decision) {
        synchronized (routeCache) {
            routeCache.put(key, new CachedRoute(decision, System.currentTimeMillis()));
        }
    }

    private static String cacheKey(
            String instruction,
            String question,
            List<AgentDefinition> agents,
            AgentDefinition preferred,
            boolean teamAvailable,
            boolean payroll) {
        StringBuilder raw = new StringBuilder(instruction.length() + 256);
        raw.append(instruction)
                .append('\n')
                .append(question == null ? "" : question)
                .append('\n');
        for (AgentDefinition agent : agents) {
            raw.append(agent.id()).append(',');
        }
        raw.append('\n')
                .append(preferred == null ? "" : preferred.id())
                .append('\n')
                .append(teamAvailable)
                .append('\n')
                .append(payroll);
        return sha256(raw.toString());
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 解析并校验模型输出；所有异常都折成回退决策。 */
    RouteDecision parse(String raw, List<AgentDefinition> agents, boolean teamAvailable) {
        JsonNode node;
        try {
            node = JSON.readTree(raw);
        } catch (Exception e) {
            return RouteDecision.fallback("路由输出不是合法 JSON");
        }
        if (node == null || !node.isObject()) {
            return RouteDecision.fallback("路由输出不是 JSON 对象");
        }
        String rawTarget = text(node, "target").toLowerCase(Locale.ROOT);
        String rawAgentId = text(node, "agent_id");
        // 小模型偶发把 agent_id 填进 target；能精确命中候选 id 时按单智能体处理。
        boolean idInTarget =
                rawAgentId.isBlank()
                        && !isTarget(rawTarget)
                        && agents.stream().anyMatch(item -> item.id().equals(rawTarget));
        String target = idInTarget ? RouteDecision.TARGET_AGENT : rawTarget;
        String agentId = idInTarget ? rawTarget : rawAgentId;
        String reason = limit(text(node, "reason"), REASON_LIMIT);
        double confidence = node.path("confidence").asDouble(0D);
        if (confidence > 1D) {
            confidence = confidence > 100D ? confidence / 100D : confidence;
        }
        confidence = Math.max(0D, Math.min(1D, confidence));
        if (confidence < minConfidence) {
            return RouteDecision.fallback(
                    String.format(
                            Locale.ROOT,
                            "置信度 %.2f 低于阈值 %.2f",
                            confidence,
                            minConfidence));
        }
        boolean needKb = needKb(node);
        String kbKind = kbKind(node, needKb);
        return switch (target) {
            case RouteDecision.TARGET_AGENT -> {
                if (agentId.isBlank()) {
                    yield RouteDecision.fallback("路由选择单智能体但未给出 agent_id");
                }
                AgentDefinition agent =
                        agents.stream().filter(item -> item.id().equals(agentId)).findFirst()
                                .orElse(null);
                yield agent == null
                        ? RouteDecision.fallback("路由给出的智能体不在候选列表：" + agentId)
                        : new RouteDecision(
                                RouteDecision.TARGET_AGENT,
                                agent,
                                reason,
                                confidence,
                                null,
                                needKb,
                                kbKind,
                                RouteDecision.KB_SOURCE_ROUTER);
            }
            case RouteDecision.TARGET_TEAM ->
                    teamAvailable
                            ? new RouteDecision(
                                    RouteDecision.TARGET_TEAM,
                                    null,
                                    reason,
                                    confidence,
                                    null,
                                    needKb,
                                    kbKind,
                                    RouteDecision.KB_SOURCE_ROUTER)
                            : RouteDecision.fallback("路由选择团队，但当前用户/会话未启用动态团队");
            case RouteDecision.TARGET_PIPELINE ->
                    new RouteDecision(
                            RouteDecision.TARGET_PIPELINE,
                            null,
                            reason,
                            confidence,
                            null,
                            needKb,
                            kbKind,
                            RouteDecision.KB_SOURCE_ROUTER);
            default -> RouteDecision.fallback("路由目标非法：" + target);
        };
    }

    /** 字段缺失或类型异常都按 false，不判路由失败。 */
    private static boolean needKb(JsonNode node) {
        JsonNode value = node.get("need_kb");
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return Boolean.parseBoolean(value.asText("").trim());
    }

    /** 非法或缺失值：需要检索时按 read，不检索时按 none。 */
    private static String kbKind(JsonNode node, boolean needKb) {
        String raw = text(node, "kb_kind").toLowerCase(Locale.ROOT);
        return switch (raw) {
            case RouteDecision.KB_READ, RouteDecision.KB_ADMIN -> raw;
            default -> needKb ? RouteDecision.KB_READ : RouteDecision.KB_NONE;
        };
    }

    private RouteDecision logFallback(
            String reason, List<AgentDefinition> agents, boolean teamAvailable, long startedAt) {
        log.info(
                "[智能体路由] 未生效：候选={}，团队可用={}，耗时={}ms，原因={}",
                agents.size(),
                teamAvailable,
                System.currentTimeMillis() - startedAt,
                reason);
        return RouteDecision.fallback(reason);
    }

    static String instruction(
            List<AgentDefinition> agents, boolean teamAvailable, boolean payroll) {
        return instruction(agents, teamAvailable, payroll, null);
    }

    static String instruction(
            List<AgentDefinition> agents,
            boolean teamAvailable,
            boolean payroll,
            AgentDefinition preferred) {
        return instruction(agents, teamAvailable, payroll, preferred, false);
    }

    static String instruction(
            List<AgentDefinition> agents,
            boolean teamAvailable,
            boolean payroll,
            AgentDefinition preferred,
            boolean creativeNoKb) {
        return instruction(agents, teamAvailable, payroll, preferred, creativeNoKb, null);
    }

    static String instruction(
            List<AgentDefinition> agents,
            boolean teamAvailable,
            boolean payroll,
            AgentDefinition preferred,
            boolean creativeNoKb,
            Prior prior) {
        StringBuilder text = new StringBuilder(INSTRUCTION);
        if (!payroll) {
            text.append(KB_SECTION);
            text.append(CREATIVE_SECTION);
            if (creativeNoKb) {
                text.append(CREATIVE_NO_KB_SECTION);
            }
        }
        text.append("\n候选智能体：\n");
        if (agents.isEmpty()) {
            text.append("- （无可用单智能体）\n");
        } else {
            for (AgentDefinition agent : agents) {
                text.append("- ")
                        .append(agent.id())
                        .append(" | ")
                        .append(nullSafe(agent.name()))
                        .append(" | ")
                        .append(limit(nullSafe(agent.description()), DESCRIPTION_LIMIT))
                        .append(" | tags=")
                        .append(agent.tags())
                        .append('\n');
            }
        }
        text.append("团队可用：").append(teamAvailable ? "是" : "否").append('\n');
        // 薪酬问题必须优先走核算链路，不注入活跃智能体偏好，保持 prompt 与改造前一致。
        if (!payroll && preferred != null) {
            text.append("当前活跃智能体（默认首选）：")
                    .append(preferred.id())
                    .append(" | ")
                    .append(nullSafe(preferred.name()))
                    .append(" | ")
                    .append(limit(nullSafe(preferred.description()), DESCRIPTION_LIMIT))
                    .append(" | tags=")
                    .append(preferred.tags())
                    .append('\n');
            text.append("默认保留当前活跃智能体；只有用户问题明显属于其他候选的专长")
                    .append("（工资核算、政策制度、数据查询、知识库管理等）时才切换。\n");
        }
        if (!payroll && prior != null && prior.present()) {
            text.append(
                    CONTINUATION_SECTION.formatted(
                            limit(prior.question(), PRIOR_QUESTION_LIMIT),
                            prior.agentId(),
                            limit(prior.answer(), PRIOR_ANSWER_LIMIT),
                            prior.agentId()));
        }
        return text.toString();
    }

    private static boolean isTarget(String value) {
        return RouteDecision.TARGET_AGENT.equals(value)
                || RouteDecision.TARGET_TEAM.equals(value)
                || RouteDecision.TARGET_PIPELINE.equals(value);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String limit(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }
}
