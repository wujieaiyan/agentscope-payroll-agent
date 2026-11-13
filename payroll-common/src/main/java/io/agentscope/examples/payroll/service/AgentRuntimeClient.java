package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.support.JsonSupport;
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

    private final List<String> urls;
    private final String token;
    private final Duration requestTimeout;
    private final long maxSkewSeconds;
    private final AtomicInteger cursor = new AtomicInteger();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public AgentRuntimeClient(List<String> urls, String token) {
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
        this.requestTimeout = Duration.ofSeconds(15);
        this.maxSkewSeconds = 120;
    }

    public boolean enabled() {
        return !urls.isEmpty() && !token.isBlank();
    }

    /** 提交轮次；任一 runtime 接受即返回，全部失败时抛出异常。 */
    public void submit(ChatSessionManager.TurnContext turn, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("turn_id", turn.turnId());
        body.put("session_id", turn.sessionId());
        body.put("user_id", turn.userId());
        body.put("message", message);
        body.put("fencing", turn.fencingToken());
        body.put("owner_node", turn.ownerNode());
        body.put("cache_scope", SemanticCacheContext.scope());
        post("/internal/turns", JsonSupport.toJson(body), false);
    }

    /** 取消为尽力而为：runtime 不可达时仍会因租约过期结束。 */
    public boolean cancel(String turnId) {
        try {
            post("/internal/turns/" + turnId + "/cancel", "", true);
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
        return post("/internal/route/preview", body, false);
    }

    private String post(String path, String body, boolean bestEffort) {
        RuntimeException last = null;
        int start = Math.abs(cursor.getAndIncrement() % urls.size());
        for (int i = 0; i < urls.size(); i++) {
            String base = urls.get((start + i) % urls.size());
            try {
                HttpResponse<String> response = send(base, path, body);
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return response.body();
                }
                if (response.statusCode() == 409) {
                    throw new IllegalStateException("轮次已被其它节点接管或已结束");
                }
                last =
                        new IllegalStateException(
                                "runtime " + base + " 返回 HTTP " + response.statusCode());
            } catch (RuntimeException e) {
                last = e;
            } catch (Exception e) {
                last = new IllegalStateException("runtime " + base + " 调用失败：" + e.getMessage(), e);
            }
            if (bestEffort) {
                continue;
            }
            log.warn("[运行期] 提交失败，尝试下一个节点：{}，原因={}", base, last.getMessage());
        }
        throw last == null ? new IllegalStateException("没有可用的 runtime 节点") : last;
    }

    private HttpResponse<String> send(String base, String path, String body)
            throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = InternalAuth.sign(token, "POST", path, timestamp, body);
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(base + path))
                        .timeout(requestTimeout)
                        .header("Content-Type", "application/json")
                        .header("X-Payroll-Timestamp", timestamp)
                        .header("X-Payroll-Internal", signature)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
