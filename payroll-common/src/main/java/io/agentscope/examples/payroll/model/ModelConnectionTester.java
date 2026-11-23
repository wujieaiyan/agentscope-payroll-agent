package io.agentscope.examples.payroll.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.examples.payroll.service.ModelCredentialService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 连接连通性测试（规格 3.7）：与推理共用出网策略，5 秒超时，密钥只参与请求、不回填结果。
 *
 * <p>Ollama 走 {@code /api/tags} + {@code /api/show}；OpenAI 兼容端点走 {@code /models}，
 * 不支持时回落到 1 token 对话；DashScope 走框架模型最小一轮。
 */
public final class ModelConnectionTester {

    /** 探测结果；{@code code} 为空表示成功。 */
    public record Probe(
            boolean ok,
            String code,
            String message,
            long elapsedMillis,
            List<String> capabilities,
            List<String> models) {}

    private static final Logger log = LoggerFactory.getLogger(ModelConnectionTester.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_MODEL_NAMES = 50;
    private static final String DEFAULT_DASHSCOPE_URL = "https://dashscope.aliyuncs.com";

    private final ModelOutboundPolicy policy;
    private final ModelCredentialService credentials;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public ModelConnectionTester(ModelOutboundPolicy policy, ModelCredentialService credentials) {
        this.policy = policy;
        this.credentials = credentials;
    }

    public Probe test(ModelConnection connection) {
        long start = System.nanoTime();
        try {
            if (ModelConnection.PROVIDER_OLLAMA.equals(connection.provider())) {
                return testOllama(connection, start);
            }
            if (ModelConnection.PROVIDER_DASHSCOPE.equals(connection.provider())) {
                return testDashscope(connection, start);
            }
            if (ModelConnection.PROVIDER_OPENAI.equals(connection.provider())) {
                return testOpenAi(connection, start);
            }
            throw new ModelConfigException(
                    ModelConfigException.CAPABILITY_UNSUPPORTED,
                    "不支持的模型提供方：" + connection.provider());
        } catch (ModelConfigException e) {
            return failure(e.code(), e.getMessage(), start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(ModelConfigException.UNREACHABLE, "测试被中断", start);
        } catch (Exception e) {
            return failure(
                    ModelConfigException.UNREACHABLE, "连接失败：" + describe(e), start);
        }
    }

    // ---------------------------------------------------------------- Ollama

    private Probe testOllama(ModelConnection connection, long start) throws Exception {
        String root = baseOf(connection, ModelConnections.DEFAULT_OLLAMA_BASE_URL);
        policy.check(root);
        HttpResponse<String> tags = send(get(root + "/api/tags", ""));
        if (tags.statusCode() != 200) {
            return failure(
                    ModelConfigException.UNREACHABLE,
                    "Ollama /api/tags 返回 HTTP " + tags.statusCode(),
                    start);
        }
        JsonNode models = json.readTree(tags.body()).path("models");
        List<String> names = new ArrayList<>();
        boolean found = false;
        for (JsonNode model : models) {
            String name = model.path("name").asText("");
            if (!name.isBlank() && names.size() < MAX_MODEL_NAMES) {
                names.add(name);
            }
            if (connection.model().equals(name) || (connection.model() + ":latest").equals(name)) {
                found = true;
            }
        }
        if (!found) {
            return failure(
                    ModelConfigException.UNREACHABLE,
                    "Ollama 未加载模型「" + connection.model() + "」，请先执行 ollama pull",
                    start);
        }
        Set<String> capabilities = new LinkedHashSet<>();
        HttpResponse<String> show =
                send(
                        post(
                                root + "/api/show",
                                json.writeValueAsString(
                                        java.util.Map.of("model", connection.model())),
                                ""));
        if (show.statusCode() == 200) {
            for (JsonNode item : json.readTree(show.body()).path("capabilities")) {
                capabilities.add(item.asText(""));
            }
            capabilities.remove("");
        }
        return new Probe(
                true,
                "",
                "模型已加载",
                elapsed(start),
                List.copyOf(capabilities),
                names);
    }

    // ------------------------------------------------------------ DashScope

    private Probe testDashscope(ModelConnection connection, long start) throws Exception {
        String root = baseOf(connection, DEFAULT_DASHSCOPE_URL);
        policy.check(root);
        String apiKey = credentials.resolveApiKey(connection);
        String id =
                connection.model().startsWith("dashscope:")
                        ? connection.model()
                        : "dashscope:" + connection.model();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey(apiKey)
                        .baseUrl(connection.baseUrl().isBlank() ? null : connection.baseUrl())
                        .build();
        Model model = ModelRegistry.resolve(id, context);
        Msg probe = Msg.builder().role(MsgRole.USER).textContent("ping").build();
        GenerateOptions options = GenerateOptions.builder().maxTokens(1).build();
        Object response = model.stream(List.of(probe), null, options).blockFirst(TIMEOUT);
        if (response == null) {
            return failure(ModelConfigException.UNREACHABLE, "DashScope 未返回响应", start);
        }
        Set<String> capabilities = new LinkedHashSet<>(connection.capabilities());
        capabilities.add(ModelConnection.CAP_STREAM);
        return new Probe(true, "", "模型可用", elapsed(start), List.copyOf(capabilities), List.of());
    }

    // ------------------------------------------------------ OpenAI-compatible

    private Probe testOpenAi(ModelConnection connection, long start) throws Exception {
        String root = baseOf(connection, "");
        if (root.isBlank()) {
            throw new ModelConfigException(
                    ModelConfigException.INVALID_CONNECTION, "OpenAI 兼容连接必须填写 baseUrl");
        }
        policy.check(root);
        String apiKey = credentials.resolveApiKey(connection);
        HttpResponse<String> listed = send(get(root + "/models", apiKey));
        if (listed.statusCode() == 200) {
            List<String> names = new ArrayList<>();
            JsonNode data = json.readTree(listed.body()).path("data");
            for (JsonNode item : data) {
                String name = item.path("id").asText("");
                if (!name.isBlank() && names.size() < MAX_MODEL_NAMES) {
                    names.add(name);
                }
            }
            return new Probe(
                    true,
                    "",
                    "模型列表可用",
                    elapsed(start),
                    List.copyOf(connection.capabilities()),
                    names);
        }
        if (listed.statusCode() == 401 || listed.statusCode() == 403) {
            return failure(
                    ModelConfigException.CREDENTIAL_MISSING,
                    "密钥被拒绝（HTTP " + listed.statusCode() + "）",
                    start);
        }
        String body =
                json.writeValueAsString(
                        java.util.Map.of(
                                "model", connection.model(),
                                "messages",
                                        List.of(
                                                java.util.Map.of(
                                                        "role", "user", "content", "ping")),
                                "max_tokens", 1,
                                "stream", false));
        HttpResponse<String> chat = send(post(root + "/chat/completions", body, apiKey));
        if (chat.statusCode() / 100 == 2) {
            return new Probe(
                    true,
                    "",
                    "最小对话可用（/models 不可用）",
                    elapsed(start),
                    List.copyOf(connection.capabilities()),
                    List.of());
        }
        return failure(
                ModelConfigException.UNREACHABLE,
                "对话接口返回 HTTP " + chat.statusCode() + "：" + truncate(chat.body()),
                start);
    }

    // ----------------------------------------------------------------- utils

    private static String baseOf(ModelConnection connection, String fallback) {
        String base = connection.baseUrl().isBlank() ? fallback : connection.baseUrl();
        return base == null ? "" : base.replaceAll("/+$", "");
    }

    private HttpRequest get(String url, String apiKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    private HttpRequest post(String url, String body, String apiKey) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static long elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private static Probe failure(String code, String message, long start) {
        log.info("[Model] 连接测试失败（{}）：{}", code, message);
        return new Probe(false, code, message, elapsed(start), List.of(), List.of());
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        String single = body.replaceAll("\\s+", " ").trim();
        return single.length() > 200 ? single.substring(0, 200) + "…" : single;
    }

    private static String describe(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getMessage();
    }
}
