package io.agentscope.examples.payroll.kb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.web.ApiResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.unit.DataSize;

class KbUploadLimitTest {

    @Test
    @DisplayName("声明长度超过 multipart 上限时在鉴权前直接 413")
    void rejectsOversizedRequestBeforeAuth() {
        KbDocumentController controller =
                new KbDocumentController(null, null, DataSize.ofMegabytes(512), 100);
        MockHttpServletRequest request =
                new MockHttpServletRequest() {
                    @Override
                    public long getContentLengthLong() {
                        return 600L * 1024 * 1024;
                    }
                };

        ResponseEntity<ApiResponse> response =
                controller.upload(1L, List.of(), null, null, request);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertTrue(response.getBody().message().contains("512MB"));
    }
}
