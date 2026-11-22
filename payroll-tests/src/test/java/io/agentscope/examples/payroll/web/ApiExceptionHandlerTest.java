package io.agentscope.examples.payroll.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler("600MB", "1GB");

    @Test
    @DisplayName("超过 multipart 上限返回 413 与可读提示")
    void reportsUploadTooLarge() {
        ResponseEntity<ApiResponse> response =
                handler.handleUploadTooLarge(
                        new MaxUploadSizeExceededException(700L * 1024 * 1024));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        String message = response.getBody() == null ? "" : response.getBody().message();
        assertTrue(message.contains("单文件上限 600 MB"), message);
        assertTrue(message.contains("单次请求上限 1 GB"), message);
    }

    @Test
    @DisplayName("revision 冲突返回 409 并携带服务端当前指纹")
    void reportsConflictWithCurrentRevision() {
        ResponseEntity<ApiResponse> response =
                handler.handleConflict(
                        new PayrollConflictException("配置已被他人修改，请刷新后重试", "abc123"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("配置已被他人修改，请刷新后重试", response.getBody().message());
        assertEquals(Map.of("currentRevision", "abc123"), response.getBody().data());
    }

    @Test
    @DisplayName("未知当前指纹时 409 不带 data")
    void reportsConflictWithoutRevision() {
        ResponseEntity<ApiResponse> response =
                handler.handleConflict(new PayrollConflictException("配置已被他人修改"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNull(response.getBody().data());
    }

    @Test
    @DisplayName("配置中心不可用返回 503 且不泄露内部地址")
    void reportsUnavailableWithoutInternals() {
        ResponseEntity<ApiResponse> response =
                handler.handleUnavailable(
                        new PayrollUnavailableException(
                                "无法写入 Nacos 配置 http://127.0.0.1:8848 连接被拒绝"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        String message = response.getBody().message();
        assertEquals("配置中心暂时不可用，请稍后重试", message);
        assertFalse(message.contains("127.0.0.1"), message);
        assertFalse(message.contains("Nacos"), message);
    }
}
