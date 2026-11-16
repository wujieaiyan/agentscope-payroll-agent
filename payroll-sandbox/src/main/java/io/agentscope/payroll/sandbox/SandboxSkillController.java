package io.agentscope.payroll.sandbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.agentscope.examples.payroll.sandbox.SkillInitProtocol;
import io.agentscope.examples.payroll.service.InternalAuth;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 技能初始化内部接口：HMAC 校验原始请求体，状态查询与删除同样需要签名。 */
@RestController
public class SandboxSkillController {

    private static final long MAX_SKEW_SECONDS = 120;

    private static final ObjectMapper MAPPER =
            JsonMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .build();

    private final SandboxSettings settings;
    private final SkillWorkspaceService workspace;

    public SandboxSkillController(SandboxSettings settings, SkillWorkspaceService workspace) {
        this.settings = settings;
        this.workspace = workspace;
    }

    @PostMapping(SkillInitProtocol.INIT_PATH)
    public ResponseEntity<SkillInitStatus> init(
            @RequestHeader(value = "X-Payroll-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Payroll-Internal", required = false) String signature,
            @RequestBody(required = false) String body) {
        if (!settings.configured()) {
            return notConfigured();
        }
        if (!InternalAuth.verify(
                settings.token(),
                "POST",
                SkillInitProtocol.INIT_PATH,
                timestamp,
                signature,
                body,
                MAX_SKEW_SECONDS)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        SkillInitRequest request;
        try {
            request = MAPPER.readValue(body == null ? "" : body, SkillInitRequest.class);
        } catch (JsonProcessingException e) {
            return response(SkillInitStatus.invalid("请求体解析失败"));
        }
        return response(workspace.start(request));
    }

    @GetMapping(SkillInitProtocol.STATUS_PATH_PREFIX + "{id}")
    public ResponseEntity<SkillInitStatus> status(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-Payroll-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Payroll-Internal", required = false) String signature) {
        if (!settings.configured()) {
            return notConfigured();
        }
        String path = SkillInitProtocol.STATUS_PATH_PREFIX + (id == null ? "" : id);
        if (!InternalAuth.verify(
                settings.token(), "GET", path, timestamp, signature, null, MAX_SKEW_SECONDS)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return response(workspace.status(id));
    }

    @DeleteMapping(SkillInitProtocol.STATUS_PATH_PREFIX + "{id}")
    public ResponseEntity<SkillInitStatus> delete(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-Payroll-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Payroll-Internal", required = false) String signature) {
        if (!settings.configured()) {
            return notConfigured();
        }
        String path = SkillInitProtocol.STATUS_PATH_PREFIX + (id == null ? "" : id);
        if (!InternalAuth.verify(
                settings.token(), "DELETE", path, timestamp, signature, null, MAX_SKEW_SECONDS)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return response(workspace.delete(id));
    }

    private static ResponseEntity<SkillInitStatus> notConfigured() {
        return response(SkillInitStatus.notConfigured("沙箱服务未启用"));
    }

    private static ResponseEntity<SkillInitStatus> response(SkillInitStatus status) {
        return ResponseEntity.status(httpStatus(status.status())).body(status);
    }

    private static HttpStatus httpStatus(String status) {
        return switch (status) {
            case SkillInitStatus.BUSY -> HttpStatus.TOO_MANY_REQUESTS;
            case SkillInitStatus.NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
            case SkillInitStatus.INVALID -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.OK;
        };
    }
}
