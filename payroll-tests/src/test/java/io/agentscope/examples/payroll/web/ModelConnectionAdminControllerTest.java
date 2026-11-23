package io.agentscope.examples.payroll.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 全局 SNAKE_CASE 下，模型配置页提交的 camelCase 请求体必须能绑定。 */
class ModelConnectionAdminControllerTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    @Test
    @DisplayName("连接保存请求按 camelCase 绑定多词字段")
    void connectionInputBindsCamelCase() throws Exception {
        ModelConnectionAdminController.ConnectionInput input = mapper.readValue(
                """
                {"id":"deepseek-chat","name":"DeepSeek对话","provider":"openai-compatible",
                 "baseUrl":"https://api.deepseek.com","model":"deepseek-chat",
                 "apiKey":"sk-test","apiKeyEnv":"","contextWindow":65536,"maxTokens":8192,
                 "temperature":0.3,"timeoutSeconds":120,"capabilities":["tools","stream"],
                 "enabled":true,"revision":7}
                """,
                ModelConnectionAdminController.ConnectionInput.class);

        assertEquals("deepseek-chat", input.id());
        assertEquals("https://api.deepseek.com", input.baseUrl());
        assertEquals("sk-test", input.apiKey());
        assertEquals("", input.apiKeyEnv());
        assertEquals(65536, input.contextWindow());
        assertEquals(8192, input.maxTokens());
        assertEquals(120, input.timeoutSeconds());
        assertEquals(List.of("tools", "stream"), input.capabilities());
        assertTrue(input.enabled());
        assertEquals(7L, input.revision());
    }

    @Test
    @DisplayName("密钥与默认值请求同样按 camelCase 绑定")
    void credentialAndDefaultsBindCamelCase() throws Exception {
        ModelConnectionAdminController.CredentialInput credential = mapper.readValue(
                """
                {"apiKey":"sk-test","revision":8}
                """,
                ModelConnectionAdminController.CredentialInput.class);
        assertEquals("sk-test", credential.apiKey());
        assertEquals(8L, credential.revision());

        ModelConnectionAdminController.DefaultsInput defaults = mapper.readValue(
                """
                {"control":"deepseek-chat","coding":"","utility":"","revision":9}
                """,
                ModelConnectionAdminController.DefaultsInput.class);
        assertEquals("deepseek-chat", defaults.control());
        assertEquals(9L, defaults.revision());
    }
}
