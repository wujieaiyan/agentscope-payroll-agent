package io.agentscope.examples.payroll.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModelConnectionTest {

    @Test
    @DisplayName("版本键覆盖所有影响推理的字段，忽略名称与更新时间")
    void versionKeyTracksInferenceFields() {
        ModelConnection base =
                connection("dash", "qwen-max", 8192, 1024, 0.2, 120, List.of("stream"), true);

        assertEquals(base.versionKey(), renameAndTouch(base).versionKey());
        assertNotEquals(base.versionKey(), withModel(base, "qwen-plus").versionKey());
        assertNotEquals(base.versionKey(), withBaseUrl(base, "https://other.test").versionKey());
        assertNotEquals(base.versionKey(), withParams(base, 16_384, 1024, 0.2, 120).versionKey());
        assertNotEquals(base.versionKey(), withParams(base, 8192, 512, 0.2, 120).versionKey());
        assertNotEquals(base.versionKey(), withParams(base, 8192, 1024, 0.5, 120).versionKey());
        assertNotEquals(base.versionKey(), withParams(base, 8192, 1024, 0.2, 60).versionKey());
        assertNotEquals(base.versionKey(), withCapabilities(base, List.of("tools")).versionKey());
    }

    @Test
    @DisplayName("智能体覆盖只改写显式提供的字段")
    void overlayOnlyTouchesExplicitFields() {
        ModelConnection base =
                connection("dash", "qwen-max", 8192, 1024, 0.2, 120, List.of("stream"), true);

        assertSame(base, base.overlay(AgentModelSettings.none()));
        ModelConnection overridden =
                base.overlay(new AgentModelSettings("", "qwen-plus", 16_384, null, null, null));
        assertEquals("qwen-plus", overridden.model());
        assertEquals(16_384, overridden.contextWindow());
        assertEquals(1024, overridden.maxTokens());
        assertEquals("dash", overridden.id());
        assertEquals("stream", overridden.capabilities().get(0));
    }

    @Test
    @DisplayName("保存校验拒绝非法 ID、提供方与参数")
    void validatedRejectsInvalidInput() {
        assertInvalid(connection("Bad_ID", "m", 8192, 1024, 0.2, 120, List.of(), true));
        assertInvalid(connection("", "m", 8192, 1024, 0.2, 120, List.of(), true));
        assertInvalid(
                new ModelConnection(
                        "dash", "", "openai-compatible", "https://api.test", "m",
                        "", "", 8192, 1024, 0.2, 120, List.of(), true, null, "", "STORED"));
        assertInvalid(withProvider(connection("dash", "m", 8192, 1024, 0.2, 120, List.of(), true), "acme"));
        assertInvalid(withModel(connection("dash", "m", 8192, 1024, 0.2, 120, List.of(), true), " "));
        assertInvalid(withParams(connection("dash", "m", 8192, 1024, 0.2, 120, List.of(), true), 0, 1024, 0.2, 120));
        assertInvalid(withParams(connection("dash", "m", 8192, 1024, 0.2, 120, List.of(), true), 8192, 0, 0.2, 120));
        assertInvalid(withParams(connection("dash", "m", 8192, 1024, 0.2, 120, List.of(), true), 8192, 1024, 2.5, 120));
        assertInvalid(withParams(connection("dash", "m", 8192, 1024, 0.2, 120, List.of(), true), 8192, 1024, 0.2, 0));
        assertInvalid(
                new ModelConnection(
                        "dash", "n", "openai-compatible", "", "m",
                        "", "", 8192, 1024, 0.2, 120, List.of(), true, null, "", "STORED"));
        assertInvalid(
                new ModelConnection(
                        "dash", "n", "openai-compatible", "ftp://api.test", "m",
                        "", "", 8192, 1024, 0.2, 120, List.of(), true, null, "", "STORED"));
        assertInvalid(
                new ModelConnection(
                        "dash", "n", "openai-compatible", "https://api.test", "m",
                        "PAYROLL_KEY", "cred-dash", 8192, 1024, 0.2, 120, List.of(), true, null, "", "STORED"));
        assertInvalid(
                new ModelConnection(
                        "dash", "n", "openai-compatible", "https://api.test", "m",
                        "bad-env-name", "", 8192, 1024, 0.2, 120, List.of(), true, null, "", "STORED"));
    }

    @Test
    @DisplayName("Ollama 连接缺省填充本机地址")
    void ollamaFillsDefaultBaseUrl() {
        ModelConnection validated =
                new ModelConnection(
                                "local", "本机", ModelConnection.PROVIDER_OLLAMA, "", "qwen2.5:3b-instruct",
                                "", "", null, null, null, null, null, null, null, "", "STORED")
                        .validated();
        assertEquals(ModelConnections.DEFAULT_OLLAMA_BASE_URL, validated.baseUrl());
        assertEquals(Boolean.TRUE, validated.enabled());
        assertEquals(List.of(), validated.capabilities());
    }

    @Test
    @DisplayName("密钥来源按凭证引用 > 环境变量 > Ollama 无密钥 判定")
    void credentialSourceReflectsConfiguration() {
        assertEquals(
                "stored",
                new ModelConnection(
                                "d", "n", "openai-compatible", "https://api.test", "m",
                                "", "cred-d", null, null, null, null, null, null, null, "", "STORED")
                        .credentialSource());
        assertEquals(
                "env",
                new ModelConnection(
                                "d", "n", "openai-compatible", "https://api.test", "m",
                                "PAYROLL_KEY", "", null, null, null, null, null, null, null, "", "STORED")
                        .credentialSource());
        assertEquals("none", withProvider(connection("local", "m", 8192, 1024, 0.2, 120, List.of(), true),
                ModelConnection.PROVIDER_OLLAMA)
                .credentialSource());
        assertEquals(
                "unset",
                new ModelConnection(
                                "d", "n", "openai-compatible", "https://api.test", "m",
                                "", "", null, null, null, null, null, null, null, "", "STORED")
                        .credentialSource());
    }

    @Test
    @DisplayName("withApiKeyEnv 保留旧引用，清空引用后才能保存")
    void withApiKeyEnvReplacesReference() {
        ModelConnection stored =
                new ModelConnection(
                        "d", "n", "openai-compatible", "https://api.test", "m",
                        "", "cred-d", null, null, null, null, null, null, null, "", "STORED");

        ModelConnection env = stored.withApiKeyEnv("MY_KEY");
        assertEquals("MY_KEY", env.apiKeyEnv());
        assertEquals("cred-d", env.credentialRef());
        assertThrows(PayrollException.class, env::validated);

        ModelConnection cleared = env.withCredentialRef("");
        assertEquals("MY_KEY", cleared.validated().apiKeyEnv());
        assertEquals("", cleared.validated().credentialRef());
    }

    private static void assertInvalid(ModelConnection connection) {
        assertThrows(PayrollException.class, connection::validated);
    }

    private static ModelConnection connection(
            String id,
            String model,
            Integer contextWindow,
            Integer maxTokens,
            Double temperature,
            Integer timeoutSeconds,
            List<String> capabilities,
            boolean enabled) {
        return new ModelConnection(
                id,
                "连接 " + id,
                ModelConnection.PROVIDER_OPENAI,
                "https://api." + id + ".test",
                model,
                "",
                "",
                contextWindow,
                maxTokens,
                temperature,
                timeoutSeconds,
                capabilities,
                enabled,
                null,
                "tester",
                ModelConnection.ORIGIN_STORED);
    }

    private static ModelConnection renameAndTouch(ModelConnection base) {
        return new ModelConnection(
                base.id(),
                "改名",
                base.provider(),
                base.baseUrl(),
                base.model(),
                base.apiKeyEnv(),
                base.credentialRef(),
                base.contextWindow(),
                base.maxTokens(),
                base.temperature(),
                base.timeoutSeconds(),
                base.capabilities(),
                base.enabled(),
                java.time.LocalDateTime.now(),
                "other",
                base.origin());
    }

    private static ModelConnection withProvider(ModelConnection base, String provider) {
        return new ModelConnection(
                base.id(), base.name(), provider, base.baseUrl(), base.model(), base.apiKeyEnv(),
                base.credentialRef(), base.contextWindow(), base.maxTokens(), base.temperature(),
                base.timeoutSeconds(), base.capabilities(), base.enabled(), base.updatedAt(),
                base.updatedBy(), base.origin());
    }

    private static ModelConnection withModel(ModelConnection base, String model) {
        return new ModelConnection(
                base.id(), base.name(), base.provider(), base.baseUrl(), model, base.apiKeyEnv(),
                base.credentialRef(), base.contextWindow(), base.maxTokens(), base.temperature(),
                base.timeoutSeconds(), base.capabilities(), base.enabled(), base.updatedAt(),
                base.updatedBy(), base.origin());
    }

    private static ModelConnection withBaseUrl(ModelConnection base, String baseUrl) {
        return new ModelConnection(
                base.id(), base.name(), base.provider(), baseUrl, base.model(), base.apiKeyEnv(),
                base.credentialRef(), base.contextWindow(), base.maxTokens(), base.temperature(),
                base.timeoutSeconds(), base.capabilities(), base.enabled(), base.updatedAt(),
                base.updatedBy(), base.origin());
    }

    private static ModelConnection withParams(
            ModelConnection base, Integer ctx, Integer max, Double temp, Integer timeout) {
        return new ModelConnection(
                base.id(), base.name(), base.provider(), base.baseUrl(), base.model(), base.apiKeyEnv(),
                base.credentialRef(), ctx, max, temp, timeout, base.capabilities(), base.enabled(),
                base.updatedAt(), base.updatedBy(), base.origin());
    }

    private static ModelConnection withCapabilities(ModelConnection base, List<String> capabilities) {
        return new ModelConnection(
                base.id(), base.name(), base.provider(), base.baseUrl(), base.model(), base.apiKeyEnv(),
                base.credentialRef(), base.contextWindow(), base.maxTokens(), base.temperature(),
                base.timeoutSeconds(), capabilities, base.enabled(), base.updatedAt(),
                base.updatedBy(), base.origin());
    }
}
