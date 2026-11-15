package io.agentscope.examples.payroll.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.examples.payroll.skill.SkillExecutionSettings;
import io.agentscope.examples.payroll.support.JsonSupport;
import io.agentscope.examples.payroll.sandbox.SandboxProtocol;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 沙箱内部通道客户端：HMAC 签名调用 {@code payroll-sandbox}，超时略大于脚本上限。
 *
 * <p>签名失败、网络不可用直接抛异常，由工具层或提案执行器转成明确错误；沙箱返回的
 * 业务状态（超时、繁忙、参数错误）原样透传，不静默降级。
 */
public final class SandboxClient implements SandboxGateway {

    private static final Logger log = LoggerFactory.getLogger(SandboxClient.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Duration EXTRA_TIMEOUT = Duration.ofSeconds(5);
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private final String baseUrl;
    private final String token;
    private final boolean enabled;
    private final int timeoutMs;

    public SandboxClient(SkillExecutionSettings settings) {
        SkillExecutionSettings effective =
                settings == null ? SkillExecutionSettings.disabled() : settings;
        String url = effective.sandboxUrl();
        this.baseUrl =
                url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.token = effective.sandboxToken();
        this.enabled = effective.configured();
        this.timeoutMs = effective.timeoutMs();
    }

    /** 从环境变量装配；未开启或缺少地址/令牌时返回禁用实例。 */
    public static SandboxGateway fromEnvironment() {
        return new SandboxClient(SkillExecutionSettings.fromEnvironment());
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public Execution execute(
            long userId,
            String skillId,
            String scriptPath,
            List<String> args,
            int timeoutMs,
            List<Script> scripts) {
        if (!enabled) {
            throw new IllegalStateException("技能脚本执行未启用（检查 PAYROLL_SKILL_EXEC_ENABLED 与沙箱地址/令牌）");
        }
        int timeout = SandboxProtocol.requireTimeout(timeoutMs <= 0 ? this.timeoutMs : timeoutMs);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user_id", userId);
        payload.put("skill_id", skillId);
        payload.put("script_path", scriptPath);
        payload.put("args", args == null ? List.of() : args);
        payload.put("timeout_ms", timeout);
        List<Map<String, Object>> files = new ArrayList<>();
        if (scripts != null) {
            for (Script script : scripts) {
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("path", script.path());
                file.put("content", script.content());
                files.add(file);
            }
        }
        payload.put("scripts", files);
        String body = JsonSupport.toJson(payload);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(baseUrl + SandboxProtocol.PATH))
                        .timeout(Duration.ofMillis(timeout).plus(EXTRA_TIMEOUT))
                        .header("Content-Type", "application/json")
                        .header("X-Payroll-Timestamp", timestamp)
                        .header(
                                "X-Payroll-Internal",
                                InternalAuth.sign(
                                        token, "POST", SandboxProtocol.PATH, timestamp, body))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("沙箱调用被中断", e);
        } catch (Exception e) {
            log.warn("[技能脚本] 沙箱调用失败：{}", rootMessage(e));
            throw new IllegalStateException("沙箱服务不可用：" + rootMessage(e), e);
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new IllegalStateException("沙箱内部签名校验失败，请核对 PAYROLL_SANDBOX_TOKEN");
        }
        if (status >= 500) {
            return new Execution(
                    Execution.UNAVAILABLE, -1, "", "", 0, "", false, messageOf(response.body(), "沙箱服务暂不可用"));
        }
        if (status == 429) {
            return new Execution(
                    Execution.BUSY, -1, "", "", 0, "", false, messageOf(response.body(), "沙箱并发已满，请稍后重试"));
        }
        if (status == 400) {
            return new Execution(
                    Execution.INVALID, -1, "", "", 0, "", false, messageOf(response.body(), "沙箱拒绝了该请求"));
        }
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("沙箱返回 HTTP " + status + "：" + messageOf(response.body(), ""));
        }
        return parse(response.body());
    }

    private static Execution parse(String body) {
        Map<String, Object> raw;
        try {
            raw = JsonSupport.fromJson(body == null ? "" : body, MAP_TYPE);
        } catch (RuntimeException e) {
            throw new IllegalStateException("沙箱响应无法解析：" + rootMessage(e), e);
        }
        if (raw == null) {
            throw new IllegalStateException("沙箱响应为空");
        }
        return new Execution(
                text(raw.get("status"), Execution.OK),
                integer(raw.get("exit_code"), -1),
                text(raw.get("stdout"), ""),
                text(raw.get("stderr"), ""),
                integer(raw.get("duration_ms"), 0),
                text(raw.get("script_sha256"), ""),
                truthy(raw.get("truncated")),
                raw.get("message") == null ? null : String.valueOf(raw.get("message")));
    }

    private static String messageOf(String body, String fallback) {
        try {
            Map<String, Object> raw = JsonSupport.fromJson(body == null ? "" : body, MAP_TYPE);
            Object message = raw == null ? null : raw.get("message");
            return message == null || String.valueOf(message).isBlank()
                    ? fallback
                    : String.valueOf(message);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
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
