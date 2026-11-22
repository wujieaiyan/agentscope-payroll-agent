package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import io.agentscope.examples.payroll.support.JsonSupport;
import io.agentscope.examples.payroll.web.TooManyRequestsException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Web 节点调用 agent-runtime 的内部客户端：多地址轮询，签名失败或不可达时由调用方报错。
 *
 * <p>拆分部署后 Web 节点不执行模型，因此这里没有本机回退。类保持可继承，便于测试用同进程替身
 * 模拟运行期接管轮次。
 */
public class AgentRuntimeClient {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeClient.class);
    /** 剩余预算低于该值时不再发起新的提交，避免连接建立到一半超时。 */
    private static final long MIN_REQUEST_NANOS = Duration.ofMillis(250).toNanos();

    private final List<String> urls;
    private final String token;
    private final Duration perUrlTimeout;
    private final Duration submitBudget;
    private final long maxSkewSeconds;
    private final AtomicInteger cursor = new AtomicInteger();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public AgentRuntimeClient(List<String> urls, String token) {
        this(urls, token, Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    public AgentRuntimeClient(
            List<String> urls, String token, Duration perUrlTimeout, Duration submitBudget) {
        List<String> normalized = new ArrayList<>();
        if (urls != null) {
            for (String url : urls) {
                if (url != null && !url.isBlank()) {
                    String value = url.trim();
                    normalized.add(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
                }
            }
        }
        this.urls = List.copyOf(normalized);
        this.token = token == null ? "" : token.trim();
        this.perUrlTimeout =
                perUrlTimeout == null || perUrlTimeout.isNegative() || perUrlTimeout.isZero()
                        ? Duration.ofSeconds(5)
                        : perUrlTimeout;
        this.submitBudget =
                submitBudget == null || submitBudget.isNegative() || submitBudget.isZero()
                        ? Duration.ofSeconds(10)
                        : submitBudget;
        this.maxSkewSeconds = 120;
    }

    public boolean enabled() {
        return !urls.isEmpty() && !token.isBlank();
    }

    /** 提交轮次；任一 runtime 接受即返回，全部失败时抛出异常。 */
    public void submit(ChatSessionManager.TurnContext turn, String message) {
        submit(turn, message, null);
    }

    /** 带附件上下文的提交：附件文本只在运行期拼入模型输入，不参与路由与缓存。 */
    public void submit(ChatSessionManager.TurnContext turn, String message, String attachmentText) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("turn_id", turn.turnId());
        body.put("session_id", turn.sessionId());
        body.put("user_id", turn.userId());
        body.put("message", message);
        body.put("fencing", turn.fencingToken());
        body.put("owner_node", turn.ownerNode());
        body.put("cache_scope", SemanticCacheContext.scope());
        if (attachmentText != null && !attachmentText.isBlank()) {
            body.put("attachment_text", attachmentText);
        }
        post("/internal/turns", JsonSupport.toJson(body), false, true);
    }

    /** 取消为尽力而为：runtime 不可达时仍会因租约过期结束。 */
    public boolean cancel(String turnId) {
        try {
            post("/internal/turns/" + turnId + "/cancel", "", true, false);
            return true;
        } catch (RuntimeException e) {
            log.warn("[运行期] 取消请求失败：turn={}，原因={}", turnId, e.getMessage());
            return false;
        }
    }

    /** 预览路由决策，返回 runtime 的 JSON 响应；未配置或不可达时抛出异常。 */
    public String routePreview(String body) {
        if (!enabled()) {
            throw new IllegalStateException("智能体运行期未配置");
        }
        return post("/internal/route/preview", body, false, false);
    }

    private String post(String path, String body, boolean bestEffort, boolean budgeted) {
        if (urls.isEmpty()) {
            throw new PayrollUnavailableException("智能体运行期不可用：未配置 runtime 地址");
        }
        long deadline = budgeted ? System.nanoTime() + submitBudget.toNanos() : Long.MAX_VALUE;
        RuntimeException last = null;
        int start = Math.abs(cursor.getAndIncrement() % urls.size());
        for (int i = 0; i < urls.size(); i++) {
            String base = urls.get((start + i) % urls.size());
            Duration timeout = perUrlTimeout;
            if (budgeted) {
                long remaining = deadline - System.nanoTime();
                if (remaining < MIN_REQUEST_NANOS) {
                    budgetExhausted();
                }
                timeout = Duration.ofNanos(Math.min(perUrlTimeout.toNanos(), remaining));
            }
            try {
                HttpResponse<String> response = send(base, path, body, timeout);
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return response.body();
                }
                if (response.statusCode() == 409) {
                    throw new IllegalStateException("轮次已被其它节点接管或已结束");
                }
                if (response.statusCode() == 429) {
                    throw new TooManyRequestsException("运行时繁忙，请稍后重试");
                }
                if (response.statusCode() == 503) {
                    throw new PayrollUnavailableException("运行时不可用");
                }
                last =
                        new IllegalStateException(
                                "runtime " + base + " 返回 HTTP " + response.statusCode());
            } catch (RuntimeException e) {
                last = e;
            } catch (Exception e) {
                last =
                        new IllegalStateException(
                                "runtime " + base + " 调用失败（" + timeout.toMillis() + "ms）："
                                        + e.getMessage(),
                                e);
            }
            if (bestEffort) {
                continue;
            }
            log.warn("[运行期] 提交失败，尝试下一个节点：{}，原因={}", base, last.getMessage());
        }
        // 所有节点都失败后，若总预算已耗尽按 503 返回，避免调用方继续长时间等待。
        if (budgeted && System.nanoTime() >= deadline) {
            budgetExhausted();
        }
        throw last == null ? new IllegalStateException("没有可用的 runtime 节点") : last;
    }

    private static void budgetExhausted() {
        TurnMetrics.submitBudgetExhausted();
        throw new PayrollUnavailableException("智能体运行期提交预算耗尽，请稍后重试");
    }

    private HttpResponse<String> send(
            String base, String path, String body, Duration timeout) throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = InternalAuth.sign(token, "POST", path, timestamp, body);
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(base + path))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header("X-Payroll-Timestamp", timestamp)
                        .header("X-Payroll-Internal", signature)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
