package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.domain.AuditEntry;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollForbiddenException;
import io.agentscope.examples.payroll.domain.PayrollGoneException;
import io.agentscope.examples.payroll.domain.PayrollNotFoundException;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import io.agentscope.examples.payroll.domain.SandboxUnavailableException;
import io.agentscope.examples.payroll.support.AuditContext;
import jakarta.servlet.ServletException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.unit.DataSize;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 统一 REST 错误响应，保持与旧版管理台一致的 {status,message} 结构。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 本仓库自己的代码都在这个前缀下；AgentScope 框架本体（{@code io.agentscope.core}）不在其中。 */
    private static final String APPLICATION_PACKAGE = "io.agentscope.examples.";

    private final String maxFileSize;
    private final String maxRequestSize;

    public ApiExceptionHandler(
            @Value("${spring.servlet.multipart.max-file-size:200MB}") String maxFileSize,
            @Value("${spring.servlet.multipart.max-request-size:512MB}") String maxRequestSize) {
        this.maxFileSize = maxFileSize;
        this.maxRequestSize = maxRequestSize;
    }

    @ExceptionHandler(PayrollNotFoundException.class)
    public ResponseEntity<ApiResponse> handleNotFound(PayrollNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(PayrollGoneException.class)
    public ResponseEntity<ApiResponse> handleGone(PayrollGoneException e) {
        return error(HttpStatus.GONE, e.getMessage());
    }

    @ExceptionHandler(PayrollException.class)
    public ResponseEntity<ApiResponse> handleBadRequest(PayrollException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * 参数校验沿用 400，但只有本仓库代码主动抛出的消息才回给页面。JDK 与框架抛出的
     * {@link IllegalArgumentException}（格式化、数字解析等）是实现细节，原文只进日志。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse> handleIllegalArgument(IllegalArgumentException e) {
        if (thrownByApplication(e)) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        log.warn("请求参数被库代码拒绝，已隐藏内部异常文本：{}", e.toString());
        return error(HttpStatus.BAD_REQUEST, "请求参数不合法，请检查后重试");
    }

    /** 按抛出点判定而不是消息内容：栈顶帧在应用包内才算我们写给用户看的校验文案。 */
    private static boolean thrownByApplication(IllegalArgumentException e) {
        StackTraceElement[] frames = e.getStackTrace();
        return frames.length > 0 && frames[0].getClassName().startsWith(APPLICATION_PACKAGE);
    }

    @ExceptionHandler(PayrollConflictException.class)
    public ResponseEntity<ApiResponse> handleConflict(PayrollConflictException e) {
        AuditContext.markWriteResult(AuditEntry.WRITE_CONFLICT);
        if (e.currentRevision() == null) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        }
        return error(
                HttpStatus.CONFLICT,
                e.getMessage(),
                Map.of("currentRevision", e.currentRevision()));
    }

    @ExceptionHandler(PayrollUnavailableException.class)
    public ResponseEntity<ApiResponse> handleUnavailable(PayrollUnavailableException e) {
        AuditContext.markWriteResult(AuditEntry.WRITE_UNAVAILABLE);
        // 503 不回传内部地址，详细原因只进日志。
        log.warn("配置中心不可用，拒绝写入：{}", e.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "配置中心暂时不可用，请稍后重试");
    }

    @ExceptionHandler(SandboxUnavailableException.class)
    public ResponseEntity<ApiResponse> handleSandboxUnavailable(SandboxUnavailableException e) {
        AuditContext.markWriteResult(AuditEntry.WRITE_UNAVAILABLE);
        log.warn("沙箱服务不可用：{}", e.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "沙箱服务暂时不可用，请稍后重试");
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse> handleUnauthorized(UnauthorizedException e) {
        return error(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(PayrollForbiddenException.class)
    public ResponseEntity<ApiResponse> handleForbidden(PayrollForbiddenException e) {
        return error(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimited(TooManyRequestsException e) {
        AuditContext.markFailure(e.getMessage(), HttpStatus.TOO_MANY_REQUESTS.value());
        // 容量拒绝统一契约：{code, retryAfterSeconds, message}，并保留 Retry-After 头。
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(
                        Map.of(
                                "code", e.code(),
                                "retryAfterSeconds", e.retryAfterSeconds(),
                                "message", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "请求体格式错误，请检查 JSON 字段");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse> handleMethod(HttpRequestMethodNotSupportedException e) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, e.getMessage());
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse> handleNotFound(ServletException e) {
        String path =
                e instanceof NoResourceFoundException notFound
                        ? "/" + notFound.getResourcePath()
                        : ((NoHandlerFoundException) e).getRequestURL();
        return error(HttpStatus.NOT_FOUND, "接口不存在：" + path);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过限制：{}", e.getMessage());
        return error(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "上传文件超过限制：单文件上限 "
                        + sizeText(maxFileSize)
                        + "，单次请求上限 "
                        + sizeText(maxRequestSize)
                        + "，请压缩或拆分后重试");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> handleUnexpected(Exception e) {
        log.error("未处理的管理台异常", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误，请查看日志");
    }

    private static String sizeText(String raw) {
        try {
            long megabytes = DataSize.parse(raw).toMegabytes();
            return megabytes >= 1024 && megabytes % 1024 == 0
                    ? (megabytes / 1024) + " GB"
                    : megabytes + " MB";
        } catch (RuntimeException e) {
            return raw;
        }
    }

    private static ResponseEntity<ApiResponse> error(HttpStatus status, String message) {
        AuditContext.markFailure(message, status.value());
        return ResponseEntity.status(status).body(ApiResponse.error(message));
    }

    private static ResponseEntity<ApiResponse> error(
            HttpStatus status, String message, Object data) {
        AuditContext.markFailure(message, status.value());
        return ResponseEntity.status(status).body(new ApiResponse("ERROR", data, message));
    }
}
