package io.agentscope.examples.payroll.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.examples.payroll.service.KbSkillGateway;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话只读检索工具：只允许查询本轮绑定的知识库，不注册任何写操作。
 *
 * <p>绑定集合由运行时写入 {@link KbSearchScope}，模型传入的 {@code series_ids} 必须是它的
 * 子集；实际检索仍在 Web 侧按库级 READER 权限执行，拒绝原样转述，禁止改用通用知识作答。
 */
public final class KbQueryTools {

    private static final Logger log = LoggerFactory.getLogger(KbQueryTools.class);

    public static final String KB_SEARCH = "kb_search";

    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(8);
    public static final int DEFAULT_TOP_K = 5;
    public static final int MAX_TOP_K = 8;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final KbSkillGateway gateway;
    private final ToolTraceRecorder recorder;
    private final KbCitationCollector citations;

    public KbQueryTools(KbSkillGateway gateway) {
        this(gateway, null, null);
    }

    public KbQueryTools(KbSkillGateway gateway, ToolTraceRecorder recorder) {
        this(gateway, recorder, null);
    }

    public KbQueryTools(
            KbSkillGateway gateway, ToolTraceRecorder recorder, KbCitationCollector citations) {
        this.gateway = gateway;
        this.recorder = recorder;
        this.citations = citations;
    }

    public boolean enabled() {
        return gateway != null && gateway.enabled();
    }

    @Tool(
            name = KB_SEARCH,
            description =
                    "检索本会话绑定的知识库，返回命中的文档分块、来源与命中路径；"
                            + "回答制度、流程、条款、规定类问题前先调用。未绑定知识库时不要调用。",
            readOnly = true)
    public String search(
            @ToolParam(name = "query", description = "检索问题，1 到 200 字") String query,
            @ToolParam(name = "series_ids", description = "可选，会话绑定库的子集；不传用全部绑定", required = false)
                    List<Long> seriesIds,
            @ToolParam(name = "top_k", description = "可选，返回知识点数，默认 5，最大 8", required = false)
                    Integer topK,
            RuntimeContext context) {
        long startedAt = System.nanoTime();
        Outcome outcome = execute(query, seriesIds, topK, context);
        record(outcome, context, startedAt);
        collect(outcome, context);
        return outcome.result();
    }

    private record Outcome(String result, Map<String, Object> arguments, boolean ok) {
    }

    private Outcome execute(
            String query, List<Long> seriesIds, Integer topK, RuntimeContext context) {
        String value = query == null ? "" : query.trim();
        if (value.isEmpty()) {
            return outcome("请输入要检索的问题");
        }
        if (value.length() > 200) {
            return outcome("检索问题不能超过 200 个字符");
        }
        KbSearchScope scope = context == null ? null : context.get(KbSearchScope.class);
        if (scope == null || !scope.bound()) {
            return outcome("本会话未启用知识库，请先在会话顶部选择知识库");
        }
        List<Long> requested;
        try {
            requested = requestedSeries(seriesIds, scope);
        } catch (IllegalArgumentException e) {
            return outcome(e.getMessage());
        }
        long userId = userId(context);
        if (userId < 0) {
            return new Outcome(error("缺少用户身份，无法检索知识库"), Map.of(), false);
        }
        if (!enabled()) {
            return new Outcome(
                    error("知识库对话工具未启用，请检查 Web 地址与内部令牌配置"), Map.of(), false);
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("query", value);
        arguments.put("series_ids", requested);
        int topKValue = topK(topK);
        arguments.put("top_k", topKValue);
        // 额度由对话层每轮注入；未注入（如本地工具调试）时保持无缓存、无上限的旧行为。
        KbSearchBudget budget = context == null ? null : context.get(KbSearchBudget.class);
        String cacheKey = budget == null ? null : cacheKey(value, requested, topKValue);
        KbSearchBudget.Slot slot = cacheKey == null ? null : budget.begin(cacheKey);
        if (slot != null && !slot.allowed()) {
            log.info("[知识库检索] 本轮额度已用尽：limit={}，query=[{}]", budget.limit(), value);
            return new Outcome(
                    error("本轮知识库检索已达上限（" + budget.limit() + " 次），请基于已获取的内容作答"),
                    arguments,
                    false);
        }
        boolean cached = slot != null && slot.cached();
        try {
            String result =
                    cached
                            ? slot.result()
                            : gateway.call(KB_SEARCH, arguments, userId, SEARCH_TIMEOUT);
            String status = statusOf(result);
            if (!"OK".equals(status)) {
                // FORBIDDEN/ERROR 原样交给模型转述，且不采集引用。
                return new Outcome(result, arguments, false);
            }
            if (slot != null && !cached) {
                budget.succeed(cacheKey, result);
            }
            if (hitCount(result) == 0) {
                return new Outcome(noHit(value), arguments, false);
            }
            return new Outcome(result, arguments, true);
        } catch (RuntimeException e) {
            if (timedOut(e)) {
                log.warn("[知识库检索] 超时：query=[{}]，series={}", value, requested);
                return new Outcome(
                        error("知识库检索超时，请稍后重试或缩小检索范围"), arguments, false);
            }
            log.warn("[知识库检索] 失败：query=[{}]，原因={}", value, rootMessage(e));
            return new Outcome(error("知识库检索失败：" + rootMessage(e)), arguments, false);
        }
    }

    private static Outcome outcome(String message) {
        return new Outcome(error(message), Map.of(), false);
    }

    /** 无命中单独成态，方便模型明确说明“未收录”，而不是把空结果当成功。 */
    private static String noHit(String query) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "NO_HIT");
        response.put("message", "当前知识库未收录该内容，请检查文档是否处理完成或换关键词");
        response.put("query", query);
        return JsonSupport.toJson(response);
    }

    private static String statusOf(String result) {
        Map<String, Object> payload = parse(result);
        Object status = payload.get("status");
        return status == null ? "" : status.toString().trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static int hitCount(String result) {
        Map<String, Object> payload = parse(result);
        if (!(payload.get("data") instanceof Map<?, ?> data)) {
            return 0;
        }
        if (!(data.get("results") instanceof List<?> libraries)) {
            return 0;
        }
        int count = 0;
        for (Object library : libraries) {
            if (library instanceof Map<?, ?> libraryMap
                    && libraryMap.get("hits") instanceof List<?> hits) {
                count += hits.size();
            }
        }
        return count;
    }

    private static Map<String, Object> parse(String result) {
        try {
            return JsonSupport.fromJson(result, MAP_TYPE);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static List<Long> requestedSeries(List<Long> seriesIds, KbSearchScope scope) {
        if (seriesIds == null || seriesIds.isEmpty()) {
            return scope.seriesIds();
        }
        LinkedHashSet<Long> distinct = new LinkedHashSet<>();
        for (Long seriesId : seriesIds) {
            if (seriesId == null || seriesId <= 0) {
                throw new IllegalArgumentException("知识库 ID 不合法");
            }
            if (!scope.contains(seriesId)) {
                throw new IllegalArgumentException("只能检索本会话已绑定的知识库");
            }
            distinct.add(seriesId);
        }
        return new ArrayList<>(distinct);
    }

    private static int topK(Integer topK) {
        int value = topK == null ? DEFAULT_TOP_K : topK;
        return Math.max(1, Math.min(MAX_TOP_K, value));
    }

    /** 查询词折叠空白并小写，库集合按校验后的有序去重列表，避免同义调用绕过复用。 */
    private static String cacheKey(String query, List<Long> seriesIds, int topK) {
        String normalized =
                query.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
        return normalized + "|" + seriesIds + "|" + topK;
    }

    private void record(Outcome outcome, RuntimeContext context, long startedAt) {
        ToolTraceRecorder active = recorderFor(context);
        if (active == null) {
            return;
        }
        try {
            active.record(
                    KB_SEARCH,
                    JsonSupport.toJson(outcome.arguments()),
                    outcome.result(),
                    (System.nanoTime() - startedAt) / 1_000_000);
        } catch (RuntimeException e) {
            log.warn("[知识库检索] 记录追溯失败：{}", e.getMessage());
        }
    }

    private void collect(Outcome outcome, RuntimeContext context) {
        if (!outcome.ok()) {
            return;
        }
        KbCitationCollector active = collectorFor(context);
        if (active == null) {
            return;
        }
        try {
            active.record(outcome.result());
        } catch (RuntimeException e) {
            log.warn("[知识库检索] 采集引用失败：{}", e.getMessage());
        }
    }

    private KbCitationCollector collectorFor(RuntimeContext context) {
        if (context != null) {
            KbCitationCollector scoped = context.get(KbCitationCollector.class);
            if (scoped != null) {
                return scoped;
            }
        }
        return citations;
    }

    private ToolTraceRecorder recorderFor(RuntimeContext context) {
        if (context != null) {
            ToolTraceRecorder scoped = context.get(ToolTraceRecorder.class);
            if (scoped != null) {
                return scoped;
            }
        }
        return recorder;
    }

    private static long userId(RuntimeContext context) {
        String value = context == null ? null : context.getUserId();
        if (value == null || value.isBlank()) {
            return -1L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static boolean timedOut(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutException
                    || current instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String error(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ERROR");
        response.put("message", message);
        return JsonSupport.toJson(response);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }
}
