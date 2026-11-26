package io.agentscope.examples.payroll.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.examples.payroll.sandbox.SkillDataProtocol;
import io.agentscope.examples.payroll.service.InternalAuth;
import io.agentscope.payroll.sandbox.SandboxDataController;
import io.agentscope.payroll.sandbox.SandboxDataService;
import io.agentscope.payroll.sandbox.SandboxSettings;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class SandboxDataControllerTest {

    private static final String TOKEN = "internal-test-token";
    private static final String SKILL = "poetry";

    private final SandboxDataService service = mock(SandboxDataService.class);

    @Test
    @DisplayName("未配置令牌时 503，不触达数据服务")
    void rejectsWhenNotConfigured() throws Exception {
        ResponseEntity<?> response = controller("").tree(SKILL, null, null, timestamp(), "sig");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("NOT_CONFIGURED", ((Map<?, ?>) response.getBody()).get("status"));
        verify(service, never()).tree(any(), any(), any());
    }

    @Test
    @DisplayName("query 参与签名：签名与原始 query 一致才返回目录树")
    void verifiesSignedQueryForTree() throws Exception {
        SandboxDataService.TreeResult tree =
                new SandboxDataService.TreeResult(
                        SKILL,
                        "",
                        List.of(new SandboxDataService.Entry("a.txt", "a.txt", "file", 1, 2, true)),
                        false,
                        500);
        when(service.tree(SKILL, null, null)).thenReturn(tree);

        String signed = SkillDataProtocol.treePath(SKILL, null, null);
        String timestamp = timestamp();
        ResponseEntity<?> response =
                controller(TOKEN)
                        .tree(
                                SKILL,
                                null,
                                null,
                                timestamp,
                                InternalAuth.sign(TOKEN, "GET", signed, timestamp, null));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(tree, response.getBody());
    }

    @Test
    @DisplayName("改动 path 或 depth 后签名失效，一律 401")
    void rejectsTamperedQuery() throws Exception {
        SandboxDataController controller = controller(TOKEN);
        String timestamp = timestamp();
        String signature =
                InternalAuth.sign(
                        TOKEN, "GET", SkillDataProtocol.treePath(SKILL, null, 2), timestamp, null);

        ResponseEntity<?> depth =
                controller.tree(SKILL, null, 1, timestamp, signature);
        ResponseEntity<?> path =
                controller.tree(SKILL, "reports", 2, timestamp, signature);

        assertEquals(HttpStatus.UNAUTHORIZED, depth.getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, path.getStatusCode());
        verify(service, never()).tree(any(), any(), any());
    }

    @Test
    @DisplayName("非法 depth 在验签前直接 400")
    void rejectsInvalidDepthBeforeSignature() {
        ResponseEntity<?> response = controller(TOKEN).tree(SKILL, null, 4, timestamp(), "sig");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID", ((Map<?, ?>) response.getBody()).get("status"));
    }

    @Test
    @DisplayName("初始化中 409、未初始化 404、非法参数 400、读失败 500")
    void mapsServiceFailures() throws Exception {
        String signed = SkillDataProtocol.treePath(SKILL, null, null);
        String timestamp = timestamp();
        String signature = InternalAuth.sign(TOKEN, "GET", signed, timestamp, null);
        SandboxDataController controller = controller(TOKEN);

        doThrow(new SandboxDataService.DataNotReadyException("初始化中"))
                .when(service)
                .tree(SKILL, null, null);
        ResponseEntity<?> busy = controller.tree(SKILL, null, null, timestamp, signature);
        assertEquals(HttpStatus.CONFLICT, busy.getStatusCode());
        assertEquals("BUSY", ((Map<?, ?>) busy.getBody()).get("status"));

        doThrow(new NoSuchFileException("技能数据未初始化")).when(service).tree(SKILL, null, null);
        ResponseEntity<?> missing = controller.tree(SKILL, null, null, timestamp, signature);
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals("NOT_FOUND", ((Map<?, ?>) missing.getBody()).get("status"));

        doThrow(new IllegalArgumentException("数据路径越界：../x"))
                .when(service)
                .tree(SKILL, null, null);
        ResponseEntity<?> invalid = controller.tree(SKILL, null, null, timestamp, signature);
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
        assertEquals("INVALID", ((Map<?, ?>) invalid.getBody()).get("status"));

        doThrow(new IOException("disk")).when(service).tree(SKILL, null, null);
        ResponseEntity<?> failed = controller.tree(SKILL, null, null, timestamp, signature);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, failed.getStatusCode());
        assertEquals("ERROR", ((Map<?, ?>) failed.getBody()).get("status"));
    }

    @Test
    @DisplayName("文件预览同样按 maxBytes 签名，缺路径直接 400")
    void verifiesSignedQueryForFile() throws Exception {
        SandboxDataService.FileResult file =
                new SandboxDataService.FileResult("a.txt", 5, 1, true, "utf-8", false, "hello", "text/plain");
        when(service.file(SKILL, "a.txt", 1024)).thenReturn(file);
        SandboxDataController controller = controller(TOKEN);
        String timestamp = timestamp();
        String signature =
                InternalAuth.sign(
                        TOKEN, "GET", SkillDataProtocol.filePath(SKILL, "a.txt", 1024), timestamp, null);

        ResponseEntity<?> response =
                controller.file(SKILL, "a.txt", 1024, timestamp, signature);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(file, response.getBody());

        String tampered = timestamp();
        ResponseEntity<?> changed =
                controller.file(
                        SKILL,
                        "a.txt",
                        2048,
                        tampered,
                        InternalAuth.sign(
                                TOKEN,
                                "GET",
                                SkillDataProtocol.filePath(SKILL, "a.txt", 1024),
                                tampered,
                                null));
        assertEquals(HttpStatus.UNAUTHORIZED, changed.getStatusCode());

        ResponseEntity<?> empty = controller.file(SKILL, "", null, timestamp(), "sig");
        assertEquals(HttpStatus.BAD_REQUEST, empty.getStatusCode());
    }

    private SandboxDataController controller(String token) {
        SandboxSettings settings = new SandboxSettings(token, "docker", "runtime", 2, "");
        return new SandboxDataController(settings, service);
    }

    private static String timestamp() {
        return String.valueOf(Instant.now().getEpochSecond());
    }
}
