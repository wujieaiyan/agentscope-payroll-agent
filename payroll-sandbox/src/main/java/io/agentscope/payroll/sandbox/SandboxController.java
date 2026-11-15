package io.agentscope.payroll.sandbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.agentscope.examples.payroll.sandbox.SandboxProtocol;
import io.agentscope.examples.payroll.service.InternalAuth;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 内部执行接口：HMAC 校验原始请求体，不做匿名放行。 */
@RestController
public class SandboxController {

    private static final long MAX_SKEW_SECONDS = 120;

    private static final ObjectMapper MAPPER =
            JsonMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .build();

    private final SandboxSettings settings;
    private final SandboxService service;

    public SandboxController(SandboxSettings settings, SandboxService service) {
        this.settings = settings;
        this.service = service;
    }

    @PostMapping(SandboxProtocol.PATH)
    public ResponseEntity<SandboxResult> execute(
            @RequestHeader(value = "X-Payroll-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Payroll-Internal", required = false) String signature,
            @RequestBody(required = false) String body) {
        if (!settings.configured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(SandboxResult.unavailable("沙箱服务未启用"));
        }
        if (!InternalAuth.verify(
                settings.token(),
                "POST",
                SandboxProtocol.PATH,
                timestamp,
                signature,
                body,
                MAX_SKEW_SECONDS)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        SandboxRequest request;
        try {
            request = MAPPER.readValue(body == null ? "" : body, SandboxRequest.class);
        } catch (JsonProcessingException e) {
            return ResponseEntity.badRequest().body(SandboxResult.invalid("请求体解析失败"));
        }
        SandboxResult result = service.execute(request);
        return ResponseEntity.status(httpStatus(result.status())).body(result);
    }

    private static HttpStatus httpStatus(String status) {
        return switch (status) {
            case SandboxResult.BUSY -> HttpStatus.TOO_MANY_REQUESTS;
            case SandboxResult.UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case SandboxResult.INVALID -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.OK;
        };
    }
}
