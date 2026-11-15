package io.agentscope.examples.payroll.sandbox;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.payroll.sandbox.SandboxResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 响应体契约：与 SandboxClient 的 snake_case 解析字段一致，避免真实调用被判失败。 */
class SandboxResultJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("SandboxResult 序列化为 snake_case")
    void serializesSnakeCase() throws Exception {
        String json =
                MAPPER.writeValueAsString(
                        SandboxResult.ok(0, "out", "", 12, "abc", false));

        assertTrue(json.contains("\"exit_code\":0"), json);
        assertTrue(json.contains("\"duration_ms\":12"), json);
        assertTrue(json.contains("\"script_sha256\":\"abc\""), json);
        assertTrue(json.contains("\"truncated\":false"), json);
        assertFalse(json.contains("exitCode"), json);
        assertFalse(json.contains("durationMs"), json);
    }

    @Test
    @DisplayName("失败结果同样使用 snake_case 字段")
    void errorResultSerializesSnakeCase() throws Exception {
        String json = MAPPER.writeValueAsString(SandboxResult.timeout("sha", 2000));

        assertTrue(json.contains("\"status\":\"TIMEOUT\""), json);
        assertTrue(json.contains("\"exit_code\":-1"), json);
        assertTrue(json.contains("\"duration_ms\":2000"), json);
        assertTrue(json.contains("\"message\""), json);
    }
}
