package io.agentscope.examples.payroll.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通过 Nacos v1 OpenAPI 读取配置。使用原生 HttpClient，避免为配置读取引入
 * Spring Cloud Alibaba 或 Nacos SDK 的版本约束。
 */
final class NacosConfigClient {

    private static final Logger log = LoggerFactory.getLogger(NacosConfigClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final NacosSkillSettings settings;
    private final HttpClient http;

    private volatile String accessToken;
    private volatile long tokenExpiresAt;
    private volatile String lastWarning;

    NacosConfigClient(NacosSkillSettings settings) {
        this.settings = settings;
        this.http =
                HttpClient.newBuilder()
                        .connectTimeout(settings.timeout())
                        .build();
    }

    Optional<String> fetch(String dataId) {
        try {
            ensureToken();
            Map<String, String> params = new LinkedHashMap<>();
            params.put("dataId", dataId);
            params.put("group", settings.group());
            if (!settings.namespace().isBlank()) {
                params.put("tenant", settings.namespace());
            }
            if (accessToken != null && !accessToken.isBlank()) {
                params.put("accessToken", accessToken);
            }
            HttpRequest request =
                    HttpRequest.newBuilder(uri("/nacos/v1/cs/configs", params))
                            .timeout(settings.timeout())
                            .header("Accept", "text/plain")
                            .GET()
                            .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                String body = response.body();
                lastWarning = null;
                return body == null || body.isBlank() ? Optional.empty() : Optional.of(body);
            }
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() == 403) {
                accessToken = null;
                tokenExpiresAt = 0;
            }
            warnOnce("读取配置 " + dataId + " 失败：HTTP " + response.statusCode());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            warnOnce("读取 Nacos 配置 " + dataId + " 失败：" + rootMessage(e));
            return Optional.empty();
        }
    }

    boolean publish(String dataId, String content) {
        Map<String, String> params = baseParams(dataId);
        params.put("content", content);
        params.put("type", "json");
        return write("发布", "POST", dataId, params);
    }

    boolean remove(String dataId) {
        return write("删除", "DELETE", dataId, baseParams(dataId));
    }

    private Map<String, String> baseParams(String dataId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("dataId", dataId);
        params.put("group", settings.group());
        if (!settings.namespace().isBlank()) {
            params.put("tenant", settings.namespace());
        }
        return params;
    }

    private boolean write(String action, String method, String dataId, Map<String, String> params) {
        try {
            ensureToken();
            if (accessToken != null && !accessToken.isBlank()) {
                params.put("accessToken", accessToken);
            }
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder(
                                    "DELETE".equals(method)
                                            ? uri("/nacos/v1/cs/configs", params)
                                            : URI.create(settings.server() + "/nacos/v1/cs/configs"))
                            .timeout(settings.timeout());
            HttpRequest request =
                    "DELETE".equals(method)
                            ? builder.DELETE().build()
                            : builder.header("Content-Type", "application/x-www-form-urlencoded")
                                    .POST(formBody(params))
                                    .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200 && "true".equalsIgnoreCase(response.body().trim())) {
                return true;
            }
            if (response.statusCode() == 403) {
                accessToken = null;
                tokenExpiresAt = 0;
            }
            warnOnce(action + "配置 " + dataId + " 失败：HTTP " + response.statusCode());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException e) {
            warnOnce(action + " Nacos 配置 " + dataId + " 失败：" + rootMessage(e));
            return false;
        }
    }

    private static HttpRequest.BodyPublisher formBody(Map<String, String> params) {
        StringBuilder form = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (form.length() > 0) {
                form.append('&');
            }
            form.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return HttpRequest.BodyPublishers.ofString(form.toString());
    }

    private void ensureToken() {
        if (settings.username().isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (accessToken != null && now < tokenExpiresAt) {
            return;
        }
        synchronized (this) {
            if (accessToken != null && now < tokenExpiresAt) {
                return;
            }
            try {
                String form =
                        "username="
                                + encode(settings.username())
                                + "&password="
                                + encode(settings.password());
                HttpRequest request =
                        HttpRequest.newBuilder(URI.create(settings.server() + "/nacos/v1/auth/login"))
                                .timeout(settings.timeout())
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(HttpRequest.BodyPublishers.ofString(form))
                                .build();
                HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    warnOnce("Nacos 登录失败：HTTP " + response.statusCode());
                    return;
                }
                JsonNode json = JSON.readTree(response.body());
                String token = json.path("accessToken").asText("");
                if (token.isBlank()) {
                    warnOnce("Nacos 登录成功但未返回 accessToken");
                    return;
                }
                long ttlSeconds = json.path("tokenTtl").asLong(1800);
                accessToken = token;
                tokenExpiresAt = now + Math.max(30, ttlSeconds - 30) * 1000L;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                warnOnce("Nacos 登录失败：" + rootMessage(e));
            }
        }
    }

    private URI uri(String path, Map<String, String> params) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return URI.create(settings.server() + path + "?" + query);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void warnOnce(String message) {
        if (!message.equals(lastWarning)) {
            lastWarning = message;
            log.warn("[Skill] {}", message);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        String message = error.getMessage();
        while (current.getCause() != null) {
            current = current.getCause();
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
        }
        return message == null ? error.getClass().getSimpleName() : message;
    }
}
