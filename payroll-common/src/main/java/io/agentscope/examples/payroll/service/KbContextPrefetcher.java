package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.agent.KbQueryTools;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.Citation;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 执行前预取一次 {@code kb_search}，把有界材料交给当前智能体（方案 B）。
 *
 * <p>整个预取受 {@code timeout-ms} 截止时间约束：超时后丢弃结果、不阻塞回答，后台 HTTP
 * 由其自身 read timeout 收尾。预取不占用模型轮内的检索额度，也不写入工具追溯——它不是
 * 模型发起的工具调用，只在 {@code routing_json.kb.context} 记账。
 */
public final class KbContextPrefetcher {

    private static final Logger log = LoggerFactory.getLogger(KbContextPrefetcher.class);

    private static final int MAX_QUERY = 200;
    private static final int SHORT_QUESTION = 12;
    private static final List<String> REFERENTS =
            List.of("这个", "那个", "它", "上面", "刚才", "前面", "还有", "那");
    private static final List<String> CREATIVE_HINTS =
            List.of("诗词", "诗歌", "歌词", "小说", "散文", "剧本", "文学", "创作", "文案",
                    "poetry", "novel", "creative", "writer");

    private final KbSkillGateway gateway;
    private final KbChatSettings settings;
    private final ExecutorService executor;

    public KbContextPrefetcher(KbSkillGateway gateway, KbChatSettings settings) {
        this.gateway = gateway;
        this.settings = settings == null ? KbChatSettings.defaults() : settings;
        this.executor =
                Executors.newFixedThreadPool(
                        4,
                        runnable -> {
                            Thread thread = new Thread(runnable, "kb-context-prefetch");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    /** 开关关闭或通道未配置时仍返回实例，避免调用方到处判空；{@link #enabled()} 为 false。 */
    public static KbContextPrefetcher disabled(KbChatSettings settings) {
        return new KbContextPrefetcher(null, settings);
    }

    public boolean enabled() {
        return gateway != null && gateway.enabled() && settings.contextEnabled();
    }

    public KbChatSettings settings() {
        return settings;
    }

    /** 预取结果；{@code attempted=false} 表示本轮根本没有发起检索（开关关闭或通道不可用）。 */
    public record Result(boolean attempted, KbContextPacket packet, long elapsedMs) {

        static Result none() {
            return new Result(false, KbContextPacket.empty(KbContextPacket.STATUS_DISABLED, ""), 0L);
        }

        public boolean injected() {
            return packet != null && packet.injected();
        }

        public String material() {
            return packet == null ? "" : packet.block();
        }

        public int hits() {
            return packet == null ? 0 : packet.hits().size();
        }

        public int chars() {
            return packet == null ? 0 : packet.chars();
        }

        public int dropped() {
            return packet == null ? 0 : packet.dropped();
        }

        public String status() {
            return packet == null ? KbContextPacket.STATUS_DISABLED : packet.status();
        }

        public List<Citation> citations() {
            return packet == null ? List.of() : packet.citations();
        }
    }

    public Result prefetch(
            String question,
            String lastQuestion,
            List<Long> seriesIds,
            long userId,
            boolean creative) {
        if (!enabled()) {
            return Result.none();
        }
        long startedAt = System.currentTimeMillis();
        String query = buildQuery(question, lastQuestion);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("query", query);
        arguments.put("series_ids", seriesIds == null ? List.of() : List.copyOf(seriesIds));
        arguments.put("top_k", Math.min(settings.maxResults(), KbQueryTools.MAX_TOP_K));

        String response;
        Future<String> future;
        try {
            future =
                    executor.submit(
                            () ->
                                    gateway.call(
                                            KbQueryTools.KB_SEARCH,
                                            arguments,
                                            userId,
                                            Duration.ofMillis(settings.timeoutMs())));
        } catch (RejectedExecutionException e) {
            log.warn("[知识库][材料] 预取线程池拒绝任务：{}", rootMessage(e));
            return new Result(
                    true,
                    KbContextPacket.empty(KbContextPacket.STATUS_ERROR, query),
                    System.currentTimeMillis() - startedAt);
        }
        try {
            response = future.get(settings.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn(
                    "[知识库][材料] 预取超时：截止={}ms，question={}",
                    settings.timeoutMs(),
                    preview(query));
            return new Result(
                    true,
                    KbContextPacket.empty(KbContextPacket.STATUS_TIMEOUT, query),
                    System.currentTimeMillis() - startedAt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(
                    true,
                    KbContextPacket.empty(KbContextPacket.STATUS_ERROR, query),
                    System.currentTimeMillis() - startedAt);
        } catch (ExecutionException e) {
            log.warn("[知识库][材料] 预取失败：{}", rootMessage(e.getCause()));
            return new Result(
                    true,
                    KbContextPacket.empty(KbContextPacket.STATUS_ERROR, query),
                    System.currentTimeMillis() - startedAt);
        }
        KbContextPacket packet = KbContextPacket.from(response, query, settings, creative);
        long elapsed = System.currentTimeMillis() - startedAt;
        log.info(
                "[知识库][材料] status={}，预取 {} 条 → 注入 {} 条（{} 字），丢弃 {} 条，耗时 {}ms，question={}",
                packet.status(),
                packet.hits().size() + packet.dropped(),
                packet.hits().size(),
                packet.chars(),
                packet.dropped(),
                elapsed,
                preview(query));
        return new Result(true, packet, elapsed);
    }

    /** 短问或指代词开头的追问补充最近一轮用户问题，帮助检索理解指代。 */
    static String buildQuery(String question, String lastQuestion) {
        String current = collapse(question);
        String previous = collapse(lastQuestion);
        if (current.isEmpty()) {
            return previous.length() <= MAX_QUERY ? previous : previous.substring(0, MAX_QUERY);
        }
        boolean referential = current.length() <= SHORT_QUESTION;
        if (!referential) {
            String lower = current.toLowerCase(Locale.ROOT);
            for (String referent : REFERENTS) {
                if (lower.startsWith(referent)) {
                    referential = true;
                    break;
                }
            }
        }
        if (!referential || previous.isEmpty()) {
            return current.length() <= MAX_QUERY ? current : current.substring(0, MAX_QUERY);
        }
        int keepPrevious = MAX_QUERY - current.length() - 1;
        String prefix =
                keepPrevious <= 0
                        ? ""
                        : previous.substring(Math.max(0, previous.length() - keepPrevious));
        String merged = prefix.isEmpty() ? current : prefix + "；" + current;
        return merged.length() <= MAX_QUERY ? merged : merged.substring(0, MAX_QUERY);
    }

    /** 创作类智能体可收到材料，但头部声明"可选、不必引用"，避免强灌资料。 */
    public static boolean isCreativeAgent(AgentDefinition agent) {
        if (agent == null) {
            return false;
        }
        String text =
                (nullSafe(agent.name())
                                + " "
                                + nullSafe(agent.description())
                                + " "
                                + String.join(" ", agent.tags()))
                        .toLowerCase(Locale.ROOT);
        for (String hint : CREATIVE_HINTS) {
            if (text.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static String collapse(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String preview(String value) {
        return value.length() <= 120 ? value : value.substring(0, 120) + "…";
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current == null || current.getMessage() == null
                ? "unknown"
                : current.getMessage();
    }
}
