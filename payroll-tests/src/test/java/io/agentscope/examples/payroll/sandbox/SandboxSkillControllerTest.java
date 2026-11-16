package io.agentscope.examples.payroll.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.examples.payroll.service.InternalAuth;
import io.agentscope.examples.payroll.sandbox.SkillInitProtocol;
import io.agentscope.payroll.sandbox.SandboxSettings;
import io.agentscope.payroll.sandbox.SandboxSkillController;
import io.agentscope.payroll.sandbox.SkillInitRequest;
import io.agentscope.payroll.sandbox.SkillInitStatus;
import io.agentscope.payroll.sandbox.SkillWorkspaceService;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class SandboxSkillControllerTest {

    private static final String TOKEN = "internal-test-token";
    private static final String BODY =
            "{\"skill_id\":\"poetry\",\"setup_path\":\"setup.sh\",\"setup_content\":\"echo hi\","
                    + "\"files\":[{\"path\":\"scripts/q.py\",\"content\":\"print(1)\"}],"
                    + "\"timeout_ms\":120000,\"max_bytes\":1024}";

    private final SkillWorkspaceService workspace = mock(SkillWorkspaceService.class);

    @Test
    @DisplayName("未配置令牌时 503，不解析请求")
    void rejectsWhenNotConfigured() {
        SandboxSkillController controller = controller("");

        ResponseEntity<SkillInitStatus> response =
                controller.init(timestamp(), "bad", BODY);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(SkillInitStatus.NOT_CONFIGURED, response.getBody().status());
        verify(workspace, never()).start(any());
    }

    @Test
    @DisplayName("签名正确时解析 snake_case 请求并返回状态")
    void startsInitWithValidSignature() {
        when(workspace.start(any()))
                .thenReturn(SkillInitStatus.initializing("poetry", "setup.sh", 1L));
        SandboxSkillController controller = controller(TOKEN);
        String timestamp = timestamp();

        ResponseEntity<SkillInitStatus> response =
                controller.init(
                        timestamp,
                        InternalAuth.sign(
                                TOKEN, "POST", SkillInitProtocol.INIT_PATH, timestamp, BODY),
                        BODY);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(SkillInitStatus.INITIALIZING, response.getBody().status());
        ArgumentCaptor<SkillInitRequest> captor = ArgumentCaptor.forClass(SkillInitRequest.class);
        verify(workspace).start(captor.capture());
        SkillInitRequest request = captor.getValue();
        assertEquals("poetry", request.skillId());
        assertEquals("setup.sh", request.setupPath());
        assertEquals("echo hi", request.setupContent());
        assertEquals("scripts/q.py", request.files().get(0).path());
        assertEquals(120_000, request.timeoutMs());
        assertEquals(1024L, request.maxBytes());
    }

    @Test
    @DisplayName("签名错误与非法请求体分别返回 401 与 400")
    void rejectsBadSignatureAndBody() {
        SandboxSkillController controller = controller(TOKEN);

        ResponseEntity<SkillInitStatus> unauthorized =
                controller.init(timestamp(), "deadbeef", BODY);
        assertEquals(HttpStatus.UNAUTHORIZED, unauthorized.getStatusCode());

        String body = "{not-json";
        String timestamp = timestamp();
        ResponseEntity<SkillInitStatus> invalid =
                controller.init(
                        timestamp,
                        InternalAuth.sign(
                                TOKEN, "POST", SkillInitProtocol.INIT_PATH, timestamp, body),
                        body);
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
        assertNotNull(invalid.getBody());
        assertEquals(SkillInitStatus.INVALID, invalid.getBody().status());
    }

    @Test
    @DisplayName("BUSY 与 INVALID 状态映射为 429/400")
    void mapsWorkspaceStatus() {
        when(workspace.start(any())).thenReturn(SkillInitStatus.busy("poetry", "并发已满"));
        SandboxSkillController controller = controller(TOKEN);
        String timestamp = timestamp();
        ResponseEntity<SkillInitStatus> busy =
                controller.init(
                        timestamp,
                        InternalAuth.sign(
                                TOKEN, "POST", SkillInitProtocol.INIT_PATH, timestamp, BODY),
                        BODY);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, busy.getStatusCode());
        assertNotNull(busy.getBody());
        assertTrue(busy.getBody().message().contains("并发"), busy.getBody().message());
    }

    @Test
    @DisplayName("状态查询与删除按方法签名校验")
    void signsStatusAndDelete() {
        SkillInitStatus ready =
                new SkillInitStatus(
                        SkillInitStatus.READY, "poetry", "setup.sh", 0, 1, 2, 10, "log", "完成");
        when(workspace.status("poetry")).thenReturn(ready);
        when(workspace.delete("poetry"))
                .thenReturn(
                        new SkillInitStatus(
                                SkillInitStatus.UNKNOWN, "poetry", null, null, 0, 3, 0, null, "已删除"));
        SandboxSkillController controller = controller(TOKEN);

        String getTimestamp = timestamp();
        ResponseEntity<SkillInitStatus> status =
                controller.status(
                        "poetry",
                        getTimestamp,
                        InternalAuth.sign(
                                TOKEN,
                                "GET",
                                SkillInitProtocol.statusPath("poetry"),
                                getTimestamp,
                                null));
        assertEquals(HttpStatus.OK, status.getStatusCode());
        assertNotNull(status.getBody());
        assertEquals(SkillInitStatus.READY, status.getBody().status());

        String wrongPathTimestamp = timestamp();
        ResponseEntity<SkillInitStatus> wrongPath =
                controller.status(
                        "poetry",
                        wrongPathTimestamp,
                        InternalAuth.sign(
                                TOKEN, "GET", "/internal/skills/other", wrongPathTimestamp, null));
        assertEquals(HttpStatus.UNAUTHORIZED, wrongPath.getStatusCode());

        String deleteTimestamp = timestamp();
        ResponseEntity<SkillInitStatus> deleted =
                controller.delete(
                        "poetry",
                        deleteTimestamp,
                        InternalAuth.sign(
                                TOKEN,
                                "DELETE",
                                SkillInitProtocol.statusPath("poetry"),
                                deleteTimestamp,
                                null));
        assertEquals(HttpStatus.OK, deleted.getStatusCode());
        assertNotNull(deleted.getBody());
        assertEquals(SkillInitStatus.UNKNOWN, deleted.getBody().status());
    }

    private SandboxSkillController controller(String token) {
        SandboxSettings settings =
                new SandboxSettings(token, "docker", "runtime", 2, "");
        return new SandboxSkillController(settings, workspace);
    }

    private static String timestamp() {
        return String.valueOf(Instant.now().getEpochSecond());
    }
}
