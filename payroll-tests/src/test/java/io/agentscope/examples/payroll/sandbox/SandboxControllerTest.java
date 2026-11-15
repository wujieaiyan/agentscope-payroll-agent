package io.agentscope.examples.payroll.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.examples.payroll.service.InternalAuth;
import io.agentscope.payroll.sandbox.SandboxController;
import io.agentscope.payroll.sandbox.SandboxResult;
import io.agentscope.payroll.sandbox.SandboxRunner;
import io.agentscope.payroll.sandbox.SandboxService;
import io.agentscope.payroll.sandbox.SandboxSettings;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class SandboxControllerTest {

    private static final String TOKEN = "internal-test-token";
    private static final String SCRIPT = "print('ok')";
    private static final String BODY =
            "{\"user_id\":7,\"skill_id\":\"payroll-auditor\",\"script_path\":\"scripts/check.py\","
                    + "\"args\":[\"--month\",\"2026-11\"],\"timeout_ms\":1000,"
                    + "\"scripts\":[{\"path\":\"scripts/check.py\",\"content\":\"print('ok')\"}]}";

    @Test
    @DisplayName("未配置令牌时直接 503，不校验签名")
    void rejectsWhenNotConfigured() {
        SandboxController controller = controller("", okRunner());

        ResponseEntity<SandboxResult> response = call(controller, "not-a-signature", BODY);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(SandboxResult.UNAVAILABLE, response.getBody().status());
    }

    @Test
    @DisplayName("签名正确时执行并返回结果")
    void executesWithValidSignature() {
        SandboxController controller = controller(TOKEN, okRunner());

        ResponseEntity<SandboxResult> response = call(controller, sign(TOKEN, BODY), BODY);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(SandboxResult.OK, response.getBody().status());
        assertEquals(SandboxProtocol.scriptSha256(SCRIPT), response.getBody().scriptSha256());
        assertEquals("done", response.getBody().stdout());
    }

    @Test
    @DisplayName("签名不匹配或时间戳过期都返回 401")
    void rejectsBadSignatureAndStaleTimestamp() {
        SandboxController controller = controller(TOKEN, okRunner());

        ResponseEntity<SandboxResult> badSignature =
                controller.execute(timestamp(), sign("other-token", BODY), BODY);
        String staleTimestamp = String.valueOf(Instant.now().getEpochSecond() - 600);
        ResponseEntity<SandboxResult> stale =
                controller.execute(
                        staleTimestamp, sign(TOKEN, BODY, staleTimestamp), BODY);

        assertEquals(HttpStatus.UNAUTHORIZED, badSignature.getStatusCode());
        assertNull(badSignature.getBody());
        assertEquals(HttpStatus.UNAUTHORIZED, stale.getStatusCode());
    }

    @Test
    @DisplayName("请求体无法解析时返回 400")
    void rejectsMalformedBody() {
        SandboxController controller = controller(TOKEN, okRunner());
        String body = "{not-json";

        ResponseEntity<SandboxResult> response = call(controller, sign(TOKEN, body), body);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(SandboxResult.INVALID, response.getBody().status());
    }

    @Test
    @DisplayName("参数错误、繁忙与执行器不可用映射为 400/429/503")
    void mapsResultStatusToHttp() {
        String invalidBody =
                BODY.replace("scripts/check.py", "references/a.md");
        ResponseEntity<SandboxResult> invalid =
                call(controller(TOKEN, okRunner()), sign(TOKEN, invalidBody), invalidBody);
        ResponseEntity<SandboxResult> busy =
                call(
                        controller(TOKEN, (request, sha) -> SandboxResult.busy("并发已满")),
                        sign(TOKEN, BODY),
                        BODY);
        ResponseEntity<SandboxResult> unavailable =
                call(
                        controller(TOKEN, (request, sha) -> SandboxResult.unavailable("容器不可用")),
                        sign(TOKEN, BODY),
                        BODY);

        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, busy.getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getStatusCode());
    }

    private static ResponseEntity<SandboxResult> call(
            SandboxController controller, String signature, String body) {
        return controller.execute(timestamp(), signature, body);
    }

    private static SandboxController controller(String token, SandboxRunner runner) {
        SandboxSettings settings = new SandboxSettings(token, "docker", "test-image", 2, "");
        return new SandboxController(settings, new SandboxService(settings, runner));
    }

    private static SandboxRunner okRunner() {
        return (request, sha) -> SandboxResult.ok(0, "done", "", 12, sha, false);
    }

    private static String sign(String token, String body) {
        return sign(token, body, timestamp());
    }

    private static String sign(String token, String body, String timestamp) {
        return InternalAuth.sign(token, "POST", SandboxProtocol.PATH, timestamp, body);
    }

    private static String timestamp() {
        return String.valueOf(Instant.now().getEpochSecond());
    }
}
