package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollForbiddenException;
import io.agentscope.examples.payroll.domain.PayrollGoneException;
import io.agentscope.examples.payroll.domain.PayrollNotFoundException;
import io.agentscope.examples.payroll.support.AuditContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.unit.DataSize;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 统一 REST 错误响应，保持与旧版管理台一致的 {status,message} 结构。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final String maxFileSize;
    private final String maxRequestSize;

    public ApiExceptionHandler(
            @Value("${spring.servlet.multipart.max-file-size:600MB}") String maxFileSize,
            @Value("${spring.servlet.multipart.max-request-size:1GB}") String maxRequestSize) {
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

    @ExceptionHandler({PayrollException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiResponse> handleBadRequest(RuntimeException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(PayrollConflictException.class)
    public ResponseEntity<ApiResponse> handleConflict(PayrollConflictException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
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
    public ResponseEntity<ApiResponse> handleRateLimited(TooManyRequestsException e) {
        return error(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "请求体格式错误，请检查 JSON 字段");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse> handleMethod(HttpRequestMethodNotSupportedException e) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse> handleNotFound(NoResourceFoundException e) {
        return error(HttpStatus.NOT_FOUND, "接口不存在：/" + e.getResourcePath());
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
}
