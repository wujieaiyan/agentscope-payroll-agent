package io.agentscope.examples.payroll.kb;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.examples.payroll.agent.KbQueryTools;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollForbiddenException;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.InternalAuth;
import io.agentscope.examples.payroll.service.KbSkillGateway;
import io.agentscope.examples.payroll.support.AuditContext;
import io.agentscope.examples.payroll.support.JsonSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web 侧知识库工具入口：只由 runtime 按 HMAC 调用，复用页面同源服务执行。
 *
 * <p>写操作的权限预检与确认后执行都走这里；拒绝会返回 FORBIDDEN 并落审计，
 * 让模型明确转述“无权操作”，不静默改用通用知识回答。
 */
@RestController
@RequestMapping("/internal/kb")
public class KbSkillController {

    private static final Logger log = LoggerFactory.getLogger(KbSkillController.class);
    private static final String EXECUTE_PATH = "/internal/kb/execute";
    private static final String CHECK_PATH = "/internal/kb/check";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final KbSkillService skillService;
    private final AuditService auditService;
    private final String token;
    private final String role;
    private final long maxSkewSeconds;

    public KbSkillController(
            KbSkillService skillService,
            AuditService auditService,
            @Value("${payroll.internal.token:}") String token,
            @Value("${payroll.node-role:all}") String role,
            @Value("${payroll.internal.max-skew-seconds:120}") long maxSkewSeconds) {
        this.skillService = skillService;
        this.auditService = auditService;
        this.token = token == null ? "" : token.trim();
        this.role = role == null ? "all" : role.trim().toLowerCase();
        this.maxSkewSeconds = maxSkewSeconds;
    }

    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> execute(
            @RequestHeader(name = "X-Payroll-Timestamp", required = false) String timestamp,
            @RequestHeader(name = "X-Payroll-Internal", required = false) String signature,
            @RequestBody(required = false) String body,
            HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> denied =
                authorize(EXECUTE_PATH, timestamp, signature, body);
        if (denied != null) {
            return denied;
        }
        KbRequest payload;
        try {
            payload = parse(body);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(error("请求体不合法：" + e.getMessage()));
        }
        AuthUser user;
        try {
            user = skillService.resolveUser(payload.userId());
        } catch (PayrollForbiddenException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbidden(e.getMessage()));
        }
        try {
            Object data = skillService.execute(user, payload.tool(), payload.arguments());
            auditSearch(user, payload, data, request);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "OK");
            result.put("data", data);
            return ResponseEntity.ok(result);
        } catch (PayrollForbiddenException e) {
            auditDenied(user, payload.tool(), e.getMessage(), request);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbidden(e.getMessage()));
        } catch (PayrollException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (RuntimeException e) {
            log.warn(
                    "[知识库工具] 执行失败：tool={}，userId={}，原因={}",
                    payload.tool(),
                    payload.userId(),
                    rootMessage(e));
            return ResponseEntity.badRequest().body(error("知识库操作失败：" + rootMessage(e)));
        }
    }

    @PostMapping("/check")
    public ResponseEntity<Map<String, Object>> check(
            @RequestHeader(name = "X-Payroll-Timestamp", required = false) String timestamp,
            @RequestHeader(name = "X-Payroll-Internal", required = false) String signature,
            @RequestBody(required = false) String body,
            HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> denied =
                authorize(CHECK_PATH, timestamp, signature, body);
        if (denied != null) {
            return denied;
        }
        KbRequest payload;
        try {
            payload = parse(body);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(error("请求体不合法：" + e.getMessage()));
        }
        AuthUser user;
        try {
            user = skillService.resolveUser(payload.userId());
        } catch (PayrollForbiddenException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbidden(e.getMessage()));
        }
        KbSkillGateway.Check check = skillService.check(user, payload.tool(), payload.arguments());
        if (!check.allowed()) {
            auditDenied(user, payload.tool(), check.message(), request);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allowed", check.allowed());
        result.put("message", check.message());
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<Map<String, Object>> authorize(
            String path, String timestamp, String signature, String body) {
        if ("runtime".equals(role)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("not found"));
        }
        if (token.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(error("未配置内部令牌"));
        }
        if (!InternalAuth.verify(token, "POST", path, timestamp, signature, body, maxSkewSeconds)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(error("内部签名校验失败"));
        }
        return null;
    }

    private KbRequest parse(String body) {
        if (body == null || body.isBlank()) {
            throw new PayrollException("请求体不能为空");
        }
        Map<String, Object> raw = JsonSupport.fromJson(body, MAP_TYPE);
        if (raw == null) {
            throw new PayrollException("请求体不能为空");
        }
        long userId = raw.get("user_id") instanceof Number number ? number.longValue() : 0L;
        String tool = raw.get("tool") == null ? null : String.valueOf(raw.get("tool"));
        Map<String, Object> arguments = new LinkedHashMap<>();
        Object argumentsRaw = raw.get("arguments");
        if (argumentsRaw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> arguments.put(String.valueOf(key), value));
        }
        return new KbRequest(userId, tool, arguments);
    }

    /** 检索审计只记录问题、库范围、命中数与耗时，不落地文档正文。 */
    private void auditSearch(
            AuthUser user, KbRequest payload, Object data, HttpServletRequest request) {
        if (!KbQueryTools.KB_SEARCH.equals(payload.tool())) {
            return;
        }
        AuditContext.begin(user, request.getRemoteAddr(), request.getHeader("User-Agent"));
        try {
            Map<?, ?> result = data instanceof Map<?, ?> map ? map : Map.of();
            int hitCount = 0;
            if (result.get("results") instanceof List<?> libraries) {
                for (Object library : libraries) {
                    if (library instanceof Map<?, ?> entry
                            && entry.get("hits") instanceof List<?> hits) {
                        hitCount += hits.size();
                    }
                }
            }
            Object reason = result.get("reason");
            Object seriesIds = payload.arguments().get("series_ids");
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("query", result.get("query"));
            summary.put("series_ids", seriesIds);
            summary.put("hit_count", hitCount);
            summary.put("result", reason != null ? reason : hitCount > 0 ? "HIT" : "EMPTY");
            summary.put("denied_series", result.get("denied_series"));
            summary.put("truncated", result.get("truncated"));
            if (result.get("diagnostics") instanceof Map<?, ?> diagnostics) {
                summary.put("took_ms", diagnostics.get("took_ms"));
            }
            auditService.record(
                    "kb.search",
                    "kb_search",
                    seriesIds == null ? null : String.valueOf(seriesIds),
                    null,
                    summary);
        } catch (RuntimeException e) {
            log.warn("[知识库工具] 记录检索审计失败：原因={}", e.getMessage());
        } finally {
            AuditContext.clear();
        }
    }

    private void auditDenied(
            AuthUser user, String tool, String message, HttpServletRequest request) {
        AuditContext.begin(user, request.getRemoteAddr(), request.getHeader("User-Agent"));
        try {
            auditService.recordFailure(
                    "kb.skill.denied",
                    "kb_tool",
                    tool == null ? "unknown" : tool,
                    HttpStatus.FORBIDDEN.value(),
                    message);
        } catch (RuntimeException e) {
            log.warn("[知识库工具] 记录拒绝审计失败：tool={}，原因={}", tool, e.getMessage());
        } finally {
            AuditContext.clear();
        }
    }

    private static Map<String, Object> forbidden(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "FORBIDDEN");
        body.put("message", message == null || message.isBlank() ? "无权执行该知识库操作" : message);
        return body;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ERROR");
        body.put("message", message);
        return body;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    private record KbRequest(long userId, String tool, Map<String, Object> arguments) {
    }
}
