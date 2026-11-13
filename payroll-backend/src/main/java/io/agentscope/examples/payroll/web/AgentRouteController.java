package io.agentscope.examples.payroll.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.AgentRuntimeClient;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.support.JsonSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 智能体路由预览：转发到 agent-runtime，仅供管理台调试，不触发模型回答。 */
@RestController
@RequestMapping("/api/agents")
public class AgentRouteController {

    private final AgentRuntimeClient runtimeClient;
    private final AuthService authService;

    public AgentRouteController(AgentRuntimeClient runtimeClient, AuthService authService) {
        this.runtimeClient = runtimeClient;
        this.authService = authService;
    }

    @PostMapping("/route-preview")
    public ApiResponse preview(
            @RequestBody(required = false) RoutePreviewRequest request,
            HttpServletRequest httpRequest) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new PayrollException("请输入用于预览的问题");
        }
        AuthUser user = authService.currentUser(httpRequest);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", request.message());
        body.put("session_id", request.sessionId());
        body.put("user_id", user.id());
        String json;
        try {
            json = runtimeClient.routePreview(JsonSupport.toJson(body));
        } catch (RuntimeException e) {
            throw new PayrollException("智能体运行期不可达，无法预览路由：" + rootMessage(e), e);
        }
        Map<String, Object> data =
                JsonSupport.fromJson(json, new TypeReference<Map<String, Object>>() {});
        return ApiResponse.ok(data);
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

    public record RoutePreviewRequest(
            String message, @JsonProperty("session_id") Long sessionId) {
        public RoutePreviewRequest {
            sessionId = sessionId == null ? 0L : sessionId;
        }
    }
}
