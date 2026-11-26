package io.agentscope.examples.payroll.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

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
    @DisplayName("本仓库抛出的参数校验消息原样回传")
    void reportsApplicationValidationMessage() {
        ResponseEntity<ApiResponse> response =
                handler.handleIllegalArgument(new IllegalArgumentException("员工编号不能为空"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("员工编号不能为空", response.getBody().message());
    }

    @Test
    @DisplayName("JDK 抛出的 IllegalArgumentException 只给通用提示，不泄露内部文本")
    void hidesLibraryExceptionText() {
        // 与技能列表那次 400 同形：模板漏转义时 Formatter 抛出 Conversion = '''。
        IllegalArgumentException jdk =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> "SELECT 'scripts/%'".formatted("'x'"));

        ResponseEntity<ApiResponse> response = handler.handleIllegalArgument(jdk);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("请求参数不合法，请检查后重试", response.getBody().message());
        assertFalse(response.getBody().message().contains("Conversion"));
    }

    @Test
    @DisplayName("业务异常仍按原文回传 400")
    void keepsBusinessMessageOnBadRequest() {
        ResponseEntity<ApiResponse> response =
                handler.handleBadRequest(new PayrollException("请提供原密码与新密码"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("请提供原密码与新密码", response.getBody().message());
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
    @DisplayName("容量拒绝返回统一 429 契约与 Retry-After 头")
    void reportsRateLimitedContract() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleRateLimited(new TooManyRequestsException("当前排队已满，请稍后重试"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("5", response.getHeaders().getFirst("Retry-After"));
        assertEquals("BUSY", response.getBody().get("code"));
        assertEquals(5, response.getBody().get("retryAfterSeconds"));
        assertEquals("当前排队已满，请稍后重试", response.getBody().get("message"));
    }

    @Test
    @DisplayName("容量拒绝支持自定义码与冷却秒数")
    void reportsRateLimitedWithCustomRetryAfter() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleRateLimited(new TooManyRequestsException("QUOTA", 12, "配额已用完"));

        assertEquals("12", response.getHeaders().getFirst("Retry-After"));
        assertEquals("QUOTA", response.getBody().get("code"));
        assertEquals(12, response.getBody().get("retryAfterSeconds"));
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

    @Test
    @DisplayName("未映射路由返回 404 而非 500")
    void reportsUnmappedRouteAsNotFound() {
        ResponseEntity<ApiResponse> response =
                handler.handleNotFound(new NoHandlerFoundException("GET", "/nope", new HttpHeaders()));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertTrue(response.getBody().message().contains("/nope"), response.getBody().message());
    }
}
