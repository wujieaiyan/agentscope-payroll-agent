package io.agentscope.examples.payroll.service;

import com.fasterxml.jackson.core.type.TypeReference;
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
 * runtime 调用 Web 知识库工具的内部客户端。
 *
 * <p>知识库服务只存在于 Web 模块，runtime 通过 HMAC 签名的内部接口执行，避免把解析、索引与
 * 权限代码复制到运行期。权限拒绝（403）原样返回给模型；网络失败与签名/配置错误抛异常，
 * 由工具层转换成结构化错误，不做静默降级。
 */
public final class KbSkillClient implements KbSkillGateway {

    private static final Logger log = LoggerFactory.getLogger(KbSkillClient.class);
    private static final String EXECUTE_PATH = "/internal/kb/execute";
    private static final String CHECK_PATH = "/internal/kb/check";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final List<String> urls;
    private final String token;
    private final boolean enabled;
    private final Duration requestTimeout;
    private final AtomicInteger cursor = new AtomicInteger();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public KbSkillClient(List<String> urls, String token, boolean enabled) {
        List<String> normalized = new ArrayList<>();
        if (urls != null) {
            for (String url : urls) {
                if (url != null && !url.isBlank()) {
                    String value = url.trim();
                    normalized.add(
                            value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
                }
            }
        }
        this.urls = List.copyOf(normalized);
        this.token = token == null ? "" : token.trim();
        this.enabled = enabled && !this.urls.isEmpty() && !this.token.isBlank();
        this.requestTimeout = Duration.ofSeconds(15);
    }

    /** 从环境变量装配；未配置内部令牌时知识库工具整体关闭，不影响薪酬工具。 */
    public static KbSkillGateway fromEnvironment() {
        boolean enabled =
                !"false".equalsIgnoreCase(env("PAYROLL_KB_SKILL_ENABLED", "true").trim());
        String urls = env("PAYROLL_WEB_NODE_URLS", "http://127.0.0.1:8080");
        String token = env("PAYROLL_AGENT_RUNTIME_TOKEN", "");
        return new KbSkillClient(List.of(urls.split(",")), token, enabled);
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public String call(String tool, Map<String, Object> arguments, long userId) {
        return post(EXECUTE_PATH, body(tool, arguments, userId), false, requestTimeout);
    }

    @Override
    public String call(
            String tool, Map<String, Object> arguments, long userId, Duration timeout) {
        Duration effective =
                timeout == null || timeout.isZero() || timeout.isNegative()
                        ? requestTimeout
                        : timeout;
        return post(EXECUTE_PATH, body(tool, arguments, userId), false, effective);
    }

    @Override
    public Check check(String tool, Map<String, Object> arguments, long userId) {
        String raw = post(CHECK_PATH, body(tool, arguments, userId), false, requestTimeout);
        if (raw == null || raw.isBlank()) {
            return Check.denied("知识库权限预检未返回结果");
        }
        try {
            Map<String, Object> payload = JsonSupport.fromJson(raw, MAP_TYPE);
            Object allowed = payload == null ? null : payload.get("allowed");
            Object message = payload == null ? null : payload.get("message");
            if (Boolean.TRUE.equals(allowed) || "true".equals(String.valueOf(allowed))) {
                return Check.granted();
            }
            return Check.denied(message == null ? "当前账号无权执行该知识库操作" : String.valueOf(message));
        } catch (RuntimeException e) {
            return Check.denied("知识库权限预检结果无法解析：" + rootMessage(e));
        }
    }

    private String body(String tool, Map<String, Object> arguments, long userId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user_id", userId);
        payload.put("tool", tool);
        payload.put("arguments", arguments == null ? Map.of() : arguments);
        return JsonSupport.toJson(payload);
    }

    private String post(String path, String body, boolean bestEffort, Duration timeout) {
        if (!enabled) {
            throw new IllegalStateException("知识库对话工具未配置 Web 地址或内部令牌");
        }
        RuntimeException last = null;
        int start = Math.abs(cursor.getAndIncrement() % urls.size());
        for (int i = 0; i < urls.size(); i++) {
            String base = urls.get((start + i) % urls.size());
            try {
                HttpResponse<String> response = send(base, path, body, timeout);
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body();
                }
                if (status == 403) {
                    return response.body();
                }
                last =
                        new IllegalStateException(
                                "Web 节点 " + base + " 返回 HTTP " + status + "：" + response.body());
            } catch (RuntimeException e) {
                last = e;
            } catch (Exception e) {
                last = new IllegalStateException("Web 节点 " + base + " 调用失败：" + e.getMessage(), e);
            }
            if (bestEffort) {
                continue;
            }
            log.warn("[知识库工具] 调用失败，尝试下一个节点：{}，原因={}", base, last.getMessage());
        }
        throw last == null ? new IllegalStateException("没有可用的 Web 节点") : last;
    }

    private HttpResponse<String> send(
            String base, String path, String body, Duration timeout) throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = InternalAuth.sign(token, "POST", path, timestamp, body);
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(base + path))
                        .timeout(timeout == null ? requestTimeout : timeout)
                        .header("Content-Type", "application/json")
                        .header("X-Payroll-Timestamp", timestamp)
                        .header("X-Payroll-Internal", signature)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
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
