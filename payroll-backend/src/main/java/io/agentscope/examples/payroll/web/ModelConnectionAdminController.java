package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.model.ModelCatalog;
import io.agentscope.examples.payroll.model.ModelConfigException;
import io.agentscope.examples.payroll.model.ModelConnection;
import io.agentscope.examples.payroll.model.ModelConnectionTester;
import io.agentscope.examples.payroll.model.ModelDefaults;
import io.agentscope.examples.payroll.model.ModelOutboundPolicy;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.service.ModelCredentialService;
import io.agentscope.examples.payroll.skill.ModelConnectionRegistry;
import io.agentscope.examples.payroll.skill.NacosAgentRegistry;
import io.agentscope.examples.payroll.support.Permissions;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型连接管理接口：元数据存 Nacos，页面密钥加密存库；列表与审计只暴露脱敏信息。
 *
 * <p>环境变量派生的连接只读；修改前请先「复制为可编辑连接」（用新建接口提交复制值）。
 */
@RestController
@RequestMapping("/api/admin/model-connections")
@RequirePermission(Permissions.AGENT_MANAGE)
public class ModelConnectionAdminController {

    /** 新建/更新请求；{@code apiKey} 只写不读，{@code apiKeyEnv} 与页面密钥二选一。 */
    public record ConnectionInput(
            String id,
            String name,
            String provider,
            String baseUrl,
            String model,
            String apiKey,
            String apiKeyEnv,
            Integer contextWindow,
            Integer maxTokens,
            Double temperature,
            Integer timeoutSeconds,
            List<String> capabilities,
            Boolean enabled,
            Long revision) {}

    public record CredentialInput(String apiKey, Long revision) {}

    public record DefaultsInput(String control, String coding, String utility, Long revision) {}

    private final ModelConnectionRegistry registry;
    private final ModelCredentialService credentials;
    private final NacosAgentRegistry agentRegistry;
    private final AuditService auditService;
    private final AuthService authService;
    private final ModelConnectionTester tester;

    public ModelConnectionAdminController(
            ModelConnectionRegistry registry,
            ModelCredentialService credentials,
            NacosAgentRegistry agentRegistry,
            AuditService auditService,
            AuthService authService,
            ModelOutboundPolicy policy) {
        this.registry = registry;
        this.credentials = credentials;
        this.agentRegistry = agentRegistry;
        this.auditService = auditService;
        this.authService = authService;
        this.tester = new ModelConnectionTester(policy, credentials);
    }

    @GetMapping
    public ApiResponse list() {
        ModelCatalog catalog = registry.catalog();
        List<Map<String, Object>> connections = new ArrayList<>();
        for (ModelConnection connection : catalog.connections()) {
            connections.add(view(connection));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("revision", catalog.revision());
        data.put("defaults", catalog.defaults());
        data.put("connections", connections);
        data.put("writable", registry.writable());
        data.put("envCodingLocked", registry.envCodingLocked());
        data.put("secretKeyAvailable", credentials.secretKeyAvailable());
        if (!credentials.secretKeyAvailable()) {
            data.put("secretKeyUnavailableReason", credentials.secretKeyUnavailableReason());
        }
        return ApiResponse.ok(data);
    }

    @PostMapping
    public ApiResponse create(@RequestBody ConnectionInput input, HttpServletRequest request) {
        String operator = operator(request);
        if (isBlank(input.id())) {
            throw new PayrollException("请填写连接 ID");
        }
        String id = input.id().trim();
        if (registry.find(id).isPresent()) {
            throw new ModelConfigException(
                    ModelConfigException.INVALID_CONNECTION, "连接 ID 已存在：" + id);
        }
        boolean hasKey = !isBlank(input.apiKey());
        if (hasKey && !credentials.secretKeyAvailable()) {
            throw secretKeyMissing();
        }
        ModelConnection connection =
                build(input, id, hasKey ? "cred-" + id : "", trimmed(input.apiKeyEnv()));
        if (hasKey) {
            credentials.save(id, input.apiKey().trim(), operator);
        }
        ModelCatalog saved;
        try {
            saved = registry.saveConnection(connection, revisionOf(input.revision()), operator);
        } catch (RuntimeException e) {
            if (hasKey) {
                credentials.clear(id);
            }
            throw e;
        }
        auditService.record("model.connection.create", "model_connection", id, null, view(connection));
        if (hasKey) {
            auditService.record(
                    "model.credential.set",
                    "model_connection",
                    id,
                    null,
                    Map.of("connectionId", id, "source", "stored"));
        }
        return ApiResponse.ok(view(saved.connection(id).orElse(connection)));
    }

    @PutMapping("/{id}")
    public ApiResponse update(
            @PathVariable String id,
            @RequestBody ConnectionInput input,
            HttpServletRequest request) {
        String operator = operator(request);
        ModelConnection existing = requireEditable(id);
        Map<String, Object> before = view(existing);
        boolean hasKey = !isBlank(input.apiKey());
        if (hasKey && !credentials.secretKeyAvailable()) {
            throw secretKeyMissing();
        }
        String credentialRef = existing.credentialRef();
        String apiKeyEnv = existing.apiKeyEnv();
        if (hasKey) {
            credentialRef = "cred-" + existing.id();
            apiKeyEnv = "";
        } else if (input.apiKeyEnv() != null) {
            apiKeyEnv = input.apiKeyEnv().trim();
            if (!apiKeyEnv.isBlank()) {
                credentialRef = "";
            }
        }
        ModelConnection updated = build(input, existing.id(), credentialRef, apiKeyEnv);
        if (hasKey) {
            credentials.save(existing.id(), input.apiKey().trim(), operator);
        }
        ModelCatalog saved;
        try {
            saved = registry.saveConnection(updated, revisionOf(input.revision()), operator);
        } catch (RuntimeException e) {
            if (hasKey) {
                credentials.clear(existing.id());
            }
            throw e;
        }
        if (!hasKey && !apiKeyEnv.isBlank() && !existing.apiKeyEnv().equals(apiKeyEnv)) {
            credentials.clear(existing.id());
        }
        ModelConnection result = saved.connection(existing.id()).orElse(updated);
        auditService.record(
                "model.connection.update", "model_connection", existing.id(), before, view(result));
        if (hasKey) {
            auditService.record(
                    "model.credential.set",
                    "model_connection",
                    existing.id(),
                    null,
                    Map.of("connectionId", existing.id(), "source", "stored"));
        }
        return ApiResponse.ok(view(result));
    }

    @PutMapping("/{id}/credential")
    public ApiResponse setCredential(
            @PathVariable String id,
            @RequestBody CredentialInput input,
            HttpServletRequest request) {
        ModelConnection existing = requireEditable(id);
        if (existing.ollama()) {
            throw new PayrollException("Ollama 连接不需要 API Key");
        }
        if (isBlank(input.apiKey())) {
            throw new PayrollException("请填写要保存的 API Key");
        }
        if (!credentials.secretKeyAvailable()) {
            throw secretKeyMissing();
        }
        String operator = operator(request);
        credentials.save(id, input.apiKey().trim(), operator);
        ModelConnection updated =
                copy(existing)
                        .withCredentialRef("cred-" + id)
                        .withApiKeyEnv("")
                        .withOrigin(ModelConnection.ORIGIN_STORED);
        ModelCatalog saved;
        try {
            saved = registry.saveConnection(updated, revisionOf(input.revision()), operator);
        } catch (RuntimeException e) {
            credentials.clear(id);
            throw e;
        }
        auditService.record(
                "model.credential.set",
                "model_connection",
                id,
                null,
                Map.of("connectionId", id, "source", "stored"));
        return ApiResponse.ok(view(saved.connection(id).orElse(updated)));
    }

    @DeleteMapping("/{id}/credential")
    public ApiResponse clearCredential(
            @PathVariable String id,
            @RequestParam(name = "revision", required = false) Long revision,
            HttpServletRequest request) {
        ModelConnection existing = requireEditable(id);
        String operator = operator(request);
        credentials.clear(id);
        ModelConnection updated =
                copy(existing)
                        .withCredentialRef("")
                        .withOrigin(ModelConnection.ORIGIN_STORED);
        ModelCatalog saved = registry.saveConnection(updated, revisionOf(revision), operator);
        auditService.record(
                "model.credential.clear",
                "model_connection",
                id,
                null,
                Map.of("connectionId", id, "source", "cleared"));
        return ApiResponse.ok(view(saved.connection(id).orElse(updated)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse delete(
            @PathVariable String id,
            @RequestParam(name = "revision", required = false) Long revision,
            HttpServletRequest request) {
        ModelConnection existing = requireEditable(id);
        List<String> references = agentReferences(id);
        if (!references.isEmpty()) {
            throw new PayrollException(
                    "连接仍被智能体引用，请先解除绑定：" + String.join("、", references));
        }
        String operator = operator(request);
        ModelCatalog saved =
                registry.deleteConnection(id, revisionOf(revision), operator);
        credentials.clear(id);
        auditService.record(
                "model.connection.delete", "model_connection", id, view(existing), null);
        return ApiResponse.ok(Map.of("revision", saved.revision(), "deleted", id));
    }

    @PostMapping("/{id}/test")
    public ApiResponse test(@PathVariable String id) {
        ModelConnection connection =
                registry.find(id)
                        .orElseThrow(
                                () ->
                                        new ModelConfigException(
                                                ModelConfigException.CONNECTION_UNAVAILABLE,
                                                "连接不存在：" + id));
        if (!connection.isEnabled()) {
            throw new ModelConfigException(
                    ModelConfigException.CONNECTION_UNAVAILABLE, "连接已停用：" + id);
        }
        ModelConnectionTester.Probe probe = tester.test(connection);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("provider", connection.provider());
        summary.put("model", connection.model());
        summary.put("elapsedMillis", probe.elapsedMillis());
        summary.put("ok", probe.ok());
        if (!probe.ok()) {
            summary.put("code", probe.code());
        }
        auditService.record(
                "model.connection.test", "model_connection", id, null, summary);
        return ApiResponse.ok(probe);
    }

    @PutMapping("/defaults")
    public ApiResponse defaults(@RequestBody DefaultsInput input, HttpServletRequest request) {
        ModelDefaults before = registry.catalog().defaults();
        ModelDefaults next =
                new ModelDefaults(input.control(), input.coding(), input.utility());
        ModelCatalog saved =
                registry.saveDefaults(next, revisionOf(input.revision()), operator(request));
        auditService.record(
                "model.defaults.update", "model_defaults", "defaults", before, saved.defaults());
        return ApiResponse.ok(
                Map.of("revision", saved.revision(), "defaults", saved.defaults()));
    }

    // ------------------------------------------------------------------ impl

    private ModelConnection requireEditable(String id) {
        ModelConnection existing =
                registry.find(id)
                        .orElseThrow(
                                () ->
                                        new ModelConfigException(
                                                ModelConfigException.CONNECTION_UNAVAILABLE,
                                                "连接不存在：" + id));
        if (existing.envDerived()) {
            throw new PayrollException(
                    "连接「" + existing.name() + "」由环境变量派生，只读；请先复制为可编辑连接");
        }
        return existing;
    }

    private ModelConnection build(
            ConnectionInput input, String id, String credentialRef, String apiKeyEnv) {
        if (!isBlank(input.apiKey()) && !isBlank(apiKeyEnv)) {
            throw new PayrollException("页面密钥与环境变量引用只能二选一");
        }
        ModelConnection connection =
                new ModelConnection(
                        id,
                        input.name(),
                        input.provider(),
                        input.baseUrl(),
                        input.model(),
                        apiKeyEnv,
                        credentialRef,
                        input.contextWindow(),
                        input.maxTokens(),
                        input.temperature(),
                        input.timeoutSeconds(),
                        input.capabilities(),
                        input.enabled(),
                        null,
                        "",
                        ModelConnection.ORIGIN_STORED);
        return connection.validated();
    }

    private static ModelConnection copy(ModelConnection connection) {
        return new ModelConnection(
                connection.id(),
                connection.name(),
                connection.provider(),
                connection.baseUrl(),
                connection.model(),
                connection.apiKeyEnv(),
                connection.credentialRef(),
                connection.contextWindow(),
                connection.maxTokens(),
                connection.temperature(),
                connection.timeoutSeconds(),
                connection.capabilities(),
                connection.enabled(),
                connection.updatedAt(),
                connection.updatedBy(),
                ModelConnection.ORIGIN_STORED);
    }

    private List<String> agentReferences(String connectionId) {
        List<String> references = new ArrayList<>();
        for (AgentDefinition agent : agentRegistry.list()) {
            if (agent.model() != null && connectionId.equals(agent.model().connectionId())) {
                references.add(agent.name() + "（" + agent.id() + "）");
            }
        }
        return references;
    }

    private Map<String, Object> view(ModelConnection connection) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", connection.id());
        view.put("name", connection.name());
        view.put("provider", connection.provider());
        view.put("baseUrl", connection.baseUrl());
        view.put("model", connection.model());
        view.put("apiKeyEnv", connection.apiKeyEnv());
        view.put("apiKeyEnvPresent", envPresent(connection.apiKeyEnv()));
        view.put("credentialSource", connection.credentialSource());
        view.put("contextWindow", connection.contextWindow());
        view.put("maxTokens", connection.maxTokens());
        view.put("temperature", connection.temperature());
        view.put("timeoutSeconds", connection.timeoutSeconds());
        view.put("capabilities", connection.capabilities());
        view.put("enabled", connection.isEnabled());
        view.put("origin", connection.origin());
        view.put("updatedAt", connection.updatedAt());
        view.put("updatedBy", connection.updatedBy());
        credentials
                .metadata(connection.id())
                .ifPresent(
                        metadata -> {
                            view.put("credentialStored", true);
                            view.put("credentialCreatedAt", metadata.createdAt());
                            view.put("credentialLastUsedAt", metadata.lastUsedAt());
                        });
        if (!Boolean.TRUE.equals(view.get("credentialStored"))) {
            view.put("credentialStored", false);
        }
        return view;
    }

    private static boolean envPresent(String name) {
        if (isBlank(name)) {
            return false;
        }
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }

    private static ModelConfigException secretKeyMissing() {
        return new ModelConfigException(
                ModelConfigException.SECRET_KEY_MISSING,
                "未配置 PAYROLL_SECRET_KEY，无法加密保存页面密钥");
    }

    private static long revisionOf(Long revision) {
        return revision == null ? -1L : revision;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private String operator(HttpServletRequest request) {
        return authService.currentUser(request).username();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
