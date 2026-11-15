package io.agentscope.examples.payroll.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class ApiExceptionHandlerTest {

    @Test
    @DisplayName("超过 multipart 上限返回 413 与可读提示")
    void reportsUploadTooLarge() {
        ApiExceptionHandler handler = new ApiExceptionHandler("600MB", "1GB");

        ResponseEntity<ApiResponse> response =
                handler.handleUploadTooLarge(
                        new MaxUploadSizeExceededException(700L * 1024 * 1024));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        String message = response.getBody() == null ? "" : response.getBody().message();
        assertTrue(message.contains("单文件上限 600 MB"), message);
        assertTrue(message.contains("单次请求上限 1 GB"), message);
    }
}
