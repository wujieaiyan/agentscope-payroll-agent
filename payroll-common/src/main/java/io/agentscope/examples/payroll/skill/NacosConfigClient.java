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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final int LIST_PAGE_SIZE = 200;

    /** 配置列表项，读取管理台技能列表时一并带回正文，避免逐条再查。 */
    record ConfigEntry(String dataId, String content, long updatedAt) {
    }

    /** 单条配置读取结果：区分“没有该配置”和“读取失败”。 */
    record FetchResult(Status status, ConfigEntry entry, String message) {

        enum Status {
            OK,
            ABSENT,
            ERROR
        }

        static FetchResult ok(ConfigEntry entry) {
            return new FetchResult(Status.OK, entry, "");
        }

        static FetchResult absent(String dataId) {
            return new FetchResult(Status.ABSENT, null, "配置 " + dataId + " 不存在");
        }

        static FetchResult error(String message) {
            return new FetchResult(Status.ERROR, null, message);
        }

        boolean ok() {
            return status == Status.OK;
        }

        Optional<String> content() {
            return status == Status.OK ? Optional.of(entry.content()) : Optional.empty();
        }
    }

    /** 配置列表读取结果：ok=false 表示 Nacos 不可用或返回格式错误。 */
    record ListResult(boolean ok, List<ConfigEntry> entries, String warning) {

        static ListResult ok(List<ConfigEntry> entries) {
            return new ListResult(true, List.copyOf(entries), "");
        }

        static ListResult failed(String warning) {
            return new ListResult(false, List.of(), warning);
        }
    }

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
        return fetchResult(dataId).content();
    }

    FetchResult fetchResult(String dataId) {
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
                if (body == null || body.isBlank()) {
                    return FetchResult.absent(dataId);
                }
                return FetchResult.ok(new ConfigEntry(dataId, body, 0));
            }
            if (response.statusCode() == 404) {
                return FetchResult.absent(dataId);
            }
            if (response.statusCode() == 403) {
                accessToken = null;
                tokenExpiresAt = 0;
            }
            String message = "读取配置 " + dataId + " 失败：HTTP " + response.statusCode();
            warnOnce(message);
            return FetchResult.error(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FetchResult.error("读取配置 " + dataId + " 被中断");
        } catch (IOException | RuntimeException e) {
            String message = "读取 Nacos 配置 " + dataId + " 失败：" + rootMessage(e);
            warnOnce(message);
            return FetchResult.error(message);
        }
    }

    /** 按 dataId 模式列出配置（Nacos v1 模糊搜索），失败时返回空列表由调用方回退。 */
    List<ConfigEntry> list(String dataIdPattern) {
        ListResult result = listResult(dataIdPattern);
        return result.ok() ? result.entries() : List.of();
    }

    /** 全量翻页读取；任一页失败即整体失败，绝不返回部分结果。 */
    ListResult listResult(String dataIdPattern) {
        List<ConfigEntry> entries = new ArrayList<>();
        int maxPages = Math.max(1, settings.listMaxPages());
        int pageNo = 1;
        while (true) {
            ListResult page = fetchPage(dataIdPattern, pageNo, LIST_PAGE_SIZE);
            if (!page.ok()) {
                return ListResult.failed(page.warning());
            }
            entries.addAll(page.entries());
            if (page.entries().size() < LIST_PAGE_SIZE) {
                if (pageNo > 1) {
                    log.info(
                            "[Skill] 配置列表 {} 共 {} 条（{} 页）",
                            dataIdPattern,
                            entries.size(),
                            pageNo);
                }
                return ListResult.ok(entries);
            }
            if (pageNo >= maxPages) {
                String message =
                        "配置列表 "
                                + dataIdPattern
                                + " 超过 "
                                + (maxPages * LIST_PAGE_SIZE)
                                + " 条，请手工清理";
                warnOnce(message);
                return ListResult.failed(message);
            }
            pageNo++;
        }
    }

    private ListResult fetchPage(String dataIdPattern, int pageNo, int pageSize) {
        try {
            ensureToken();
            Map<String, String> params = new LinkedHashMap<>();
            params.put("search", "blur");
            params.put("dataId", dataIdPattern);
            params.put("group", settings.group());
            params.put("pageNo", String.valueOf(pageNo));
            params.put("pageSize", String.valueOf(pageSize));
            if (!settings.namespace().isBlank()) {
                params.put("tenant", settings.namespace());
            }
            if (accessToken != null && !accessToken.isBlank()) {
                params.put("accessToken", accessToken);
            }
            HttpRequest request =
                    HttpRequest.newBuilder(uri("/nacos/v1/cs/configs", params))
                            .timeout(settings.timeout())
                            .header("Accept", "application/json")
                            .GET()
                            .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                lastWarning = null;
                String body = response.body();
                if (body == null || body.isBlank()) {
                    return ListResult.ok(List.of());
                }
                JsonNode pageItems = JSON.readTree(body).path("pageItems");
                if (!pageItems.isArray()) {
                    String message = "解析配置列表 " + dataIdPattern + " 失败：pageItems 不是数组";
                    warnOnce(message);
                    return ListResult.failed(message);
                }
                List<ConfigEntry> entries = new ArrayList<>(pageItems.size());
                for (JsonNode item : pageItems) {
                    String dataId = item.path("dataId").asText("");
                    if (dataId.isBlank()) {
                        continue;
                    }
                    entries.add(
                            new ConfigEntry(
                                    dataId,
                                    item.path("content").asText(""),
                                    item.path("gmtModified").asLong(0)));
                }
                return ListResult.ok(entries);
            }
            if (response.statusCode() == 404) {
                return ListResult.ok(List.of());
            }
            if (response.statusCode() == 403) {
                accessToken = null;
                tokenExpiresAt = 0;
            }
            String message = "列出配置 " + dataIdPattern + " 失败：HTTP " + response.statusCode();
            warnOnce(message);
            return ListResult.failed(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ListResult.failed("列出配置 " + dataIdPattern + " 被中断");
        } catch (IOException | RuntimeException e) {
            String message = "列出 Nacos 配置 " + dataIdPattern + " 失败：" + rootMessage(e);
            warnOnce(message);
            return ListResult.failed(message);
        }
    }

    boolean publish(String dataId, String content) {
        return publish(dataId, content, "json");
    }

    boolean publish(String dataId, String content, String type) {
        Map<String, String> params = baseParams(dataId);
        params.put("content", content);
        params.put("type", type);
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
