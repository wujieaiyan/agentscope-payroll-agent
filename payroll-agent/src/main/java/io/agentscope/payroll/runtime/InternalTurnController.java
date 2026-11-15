package io.agentscope.payroll.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.examples.payroll.agent.KbChatIntent;
import io.agentscope.examples.payroll.agent.KbRoutingPolicy;
import io.agentscope.examples.payroll.agent.PayrollRouteMode;
import io.agentscope.examples.payroll.agent.SemanticRouteClassifier;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.FlowDefinition;
import io.agentscope.examples.payroll.router.AgentRouter;
import io.agentscope.examples.payroll.router.KbRoutePlanner;
import io.agentscope.examples.payroll.router.PayrollRouteAdvisor;
import io.agentscope.examples.payroll.router.RouteDecision;
import io.agentscope.examples.payroll.service.ChatSessionManager;
import io.agentscope.examples.payroll.service.InternalAuth;
import io.agentscope.examples.payroll.service.SemanticCacheContext;
import io.agentscope.examples.payroll.skill.NacosAgentRegistry;
import io.agentscope.examples.payroll.skill.NacosFlowRegistry;
import io.agentscope.examples.payroll.support.JsonSupport;
import io.agentscope.examples.payroll.team.ActiveTeamService;
import io.agentscope.examples.payroll.web.ChatSse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * runtime 内部接口：只由 Web 节点按 HMAC 调用，不对外暴露。
 *
 * <p>提交后立即返回 202，执行结果通过 Redis Stream 事件流与 MySQL 体现；
 * 相同 turn_id 重复提交按幂等处理。
 */
@RestController
@RequestMapping("/internal")
public class InternalTurnController {

    private static final Logger log = LoggerFactory.getLogger(InternalTurnController.class);

    private final ChatSessionManager manager;
    private final Executor chatStreamExecutor;
    private final AgentRouter router;
    private final NacosAgentRegistry agentRegistry;
    private final NacosFlowRegistry flowRegistry;
    private final ActiveTeamService activeTeam;
    private final SemanticRouteClassifier semantic;
    private final PayrollRouteAdvisor payrollRouteAdvisor;
    private final KbRoutingPolicy.Options kbPolicy;
    private final String token;
    private final String role;
    private final long maxSkewSeconds;

    public InternalTurnController(
            ChatSessionManager manager,
            @Qualifier("chatStreamExecutor") Executor chatStreamExecutor,
            AgentRouter router,
            NacosAgentRegistry agentRegistry,
            NacosFlowRegistry flowRegistry,
            ActiveTeamService activeTeam,
            @Qualifier("routeSemanticClassifier") SemanticRouteClassifier semantic,
            @Qualifier("payrollRouteAdvisor") PayrollRouteAdvisor payrollRouteAdvisor,
            @Value("${payroll.chat.kb-policy.enabled:false}") boolean kbPolicyEnabled,
            @Value("${payroll.chat.kb-policy.follow-up:true}") boolean kbPolicyFollowUp,
            @Value("${payroll.chat.kb-policy.bound-candidate:false}")
                    boolean kbPolicyBoundCandidate,
            @Value("${payroll.chat.kb-policy.action-guard:true}") boolean kbPolicyActionGuard,
            @Value("${payroll.chat.kb-policy.follow-up-max-chars:20}")
                    int kbPolicyFollowUpMaxChars,
            @Value("${payroll.internal.token:}") String token,
            @Value("${payroll.node-role:all}") String role,
            @Value("${payroll.internal.max-skew-seconds:120}") long maxSkewSeconds) {
        this.manager = manager;
        this.chatStreamExecutor = chatStreamExecutor;
        this.router = router == null ? AgentRouter.disabled() : router;
        this.agentRegistry = agentRegistry;
        this.flowRegistry = flowRegistry;
        this.activeTeam = activeTeam == null ? ActiveTeamService.disabled() : activeTeam;
        this.semantic =
                semantic == null ? SemanticRouteClassifier.disabled("未配置") : semantic;
        this.payrollRouteAdvisor =
                payrollRouteAdvisor == null
                        ? PayrollRouteAdvisor.disabled()
                        : payrollRouteAdvisor;
        this.kbPolicy =
                new KbRoutingPolicy.Options(
                        kbPolicyEnabled,
                        kbPolicyFollowUp,
                        kbPolicyBoundCandidate,
                        kbPolicyActionGuard,
                        kbPolicyFollowUpMaxChars);
        this.token = token == null ? "" : token.trim();
        this.role = role == null ? "all" : role.trim().toLowerCase();
        this.maxSkewSeconds = maxSkewSeconds;
    }

    @PostMapping("/turns")
    public ResponseEntity<Map<String, Object>> submit(
            @RequestHeader(name = "X-Payroll-Timestamp", required = false) String timestamp,
            @RequestHeader(name = "X-Payroll-Internal", required = false) String signature,
            @RequestBody(required = false) String body) {
        ResponseEntity<Map<String, Object>> denied =
                authorize("POST", "/internal/turns", timestamp, signature, body);
        if (denied != null) {
            return denied;
        }
        SubmitRequest request;
        try {
            request = JsonSupport.fromJson(body, SubmitRequest.class);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "请求体不合法"));
        }
        if (request == null || request.turnId() == null || request.message() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "缺少 turn_id 或 message"));
        }
        var turn =
                manager.acceptRemoteTurn(
                        request.turnId(),
                        request.sessionId(),
                        request.userId(),
                        request.message(),
                        request.fencing(),
                        request.ownerNode());
        if (turn.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(
                            Map.of(
                                    "accepted", false,
                                    "message", "轮次已结束、已过期或已被其它节点接管"));
        }
        log.info(
                "[轮次] runtime 接管：turn={} session={} owner={}",
                request.turnId(),
                request.sessionId(),
                request.ownerNode());
        chatStreamExecutor.execute(() -> execute(turn.get(), request.message(), request.cacheScope()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("accepted", true, "turn_id", request.turnId()));
    }

    @PostMapping("/turns/{turnId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(
            @PathVariable String turnId,
            @RequestHeader(name = "X-Payroll-Timestamp", required = false) String timestamp,
            @RequestHeader(name = "X-Payroll-Internal", required = false) String signature,
            @RequestBody(required = false) String body) {
        ResponseEntity<Map<String, Object>> denied =
                authorize(
                        "POST",
                        "/internal/turns/" + turnId + "/cancel",
                        timestamp,
                        signature,
                        body);
        if (denied != null) {
            return denied;
        }
        return ResponseEntity.ok(Map.of("cancelled", manager.cancelInternal(turnId)));
    }

    /** 管理台调试：只跑路由决策，不执行模型回答。 */
    @PostMapping("/route/preview")
    public ResponseEntity<Map<String, Object>> routePreview(
            @RequestHeader(name = "X-Payroll-Timestamp", required = false) String timestamp,
            @RequestHeader(name = "X-Payroll-Internal", required = false) String signature,
            @RequestBody(required = false) String body) {
        ResponseEntity<Map<String, Object>> denied =
                authorize("POST", "/internal/route/preview", timestamp, signature, body);
        if (denied != null) {
            return denied;
        }
        RoutePreviewRequest request;
        try {
            request = JsonSupport.fromJson(body, RoutePreviewRequest.class);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "请求体不合法"));
        }
        if (request == null || request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "请输入用于预览的问题"));
        }
        long startedAt = System.currentTimeMillis();
        List<AgentDefinition> candidates;
        try {
            candidates = AgentRouter.candidates(agentRegistry.list());
        } catch (RuntimeException e) {
            candidates = List.of();
            log.warn("[智能体路由] 预览读取目录失败：{}", rootMessage(e));
        }
        boolean teamAvailable;
        try {
            teamAvailable = activeTeam.allowed(request.userId(), request.sessionId());
        } catch (RuntimeException e) {
            teamAvailable = false;
        }
        boolean payroll = KbChatIntent.isPayroll(request.message());
        List<AgentDefinition> routeCandidates = candidates;
        boolean teamOn = teamAvailable;
        Supplier<RouteDecision> llm =
                () ->
                        router.decide(
                                request.message(),
                                routeCandidates,
                                teamOn,
                                request.sessionId() + ":" + request.userId(),
                                payroll);
        // 规格 7：薪酬预览与真实链路共用同一顾问，off 档行为与改造前一致。
        PayrollRouteAdvisor.Outcome routeOutcome =
                payroll && payrollRouteAdvisor.mode().semanticEnabled()
                        ? payrollRouteAdvisor.decide(
                                request.message(), routeCandidates, teamOn, "", "", llm)
                        : null;
        RouteDecision decision = routeOutcome == null ? llm.get() : routeOutcome.decision();
        // 预览默认不读会话状态：按未绑定、无引用上下文计算 L0，与真实链路同一条合并规则；
        // 评测脚本可显式传入夹具（kb_bound / kb_last_cited）以覆盖追问类场景。
        KbRoutingPolicy.Result l0 =
                KbRoutingPolicy.decide(
                        request.message(),
                        Boolean.TRUE.equals(request.kbBound()),
                        Boolean.TRUE.equals(request.kbLastCited()),
                        kbPolicy);
        SemanticRouteClassifier.Decision semanticDecision =
                KbRoutePlanner.semanticEligible(l0, payroll)
                        ? semantic.classify(request.message())
                        : null;
        KbRoutePlanner.Plan kbPlan =
                KbRoutePlanner.plan(payroll, l0, semanticDecision, decision);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", router.enabled());
        result.put("payroll", payroll);
        result.put("target", decision == null ? null : decision.target());
        result.put(
                "agent_id",
                decision == null || decision.agent() == null ? null : decision.agent().id());
        result.put(
                "agent_name",
                decision == null || decision.agent() == null ? null : decision.agent().name());
        result.put("reason", decision == null ? "" : decision.reason());
        result.put("confidence", decision == null ? 0D : decision.confidence());
        result.put(
                "failure_reason",
                decision == null ? "路由未启用" : decision.failureReason());
        Map<String, Object> kb = new LinkedHashMap<>();
        kb.put("need", kbPlan.need());
        kb.put("kind", kbPlan.kind());
        kb.put("source", kbPlan.source());
        result.put("kb", kb);
        Map<String, Object> l0Info = new LinkedHashMap<>();
        l0Info.put("decision", l0.decision().name());
        l0Info.put("source", l0.source().name());
        l0Info.put("action_guard", l0.actionGuard());
        result.put("l0", l0Info);
        Map<String, Object> semanticInfo = new LinkedHashMap<>();
        semanticInfo.put(
                "label",
                semanticDecision == null
                        ? SemanticRouteClassifier.LABEL_UNAVAILABLE
                        : semanticDecision.label());
        semanticInfo.put("score", semanticDecision == null ? 0D : semanticDecision.score());
        semanticInfo.put("margin", semanticDecision == null ? 0D : semanticDecision.margin());
        semanticInfo.put(
                "confident", semanticDecision != null && semanticDecision.confident());
        semanticInfo.put("reason", semanticDecision == null ? "未执行" : semanticDecision.reason());
        result.put("semantic", semanticInfo);
        Map<String, Object> routerCache = new LinkedHashMap<>();
        routerCache.put("mode", payrollRouteAdvisor.mode().name().toLowerCase(Locale.ROOT));
        routerCache.put("enabled", payrollRouteAdvisor.mode().cacheEnabled());
        routerCache.put("bucket", routeOutcome == null ? "" : routeOutcome.bucket());
        routerCache.put("hit", routeOutcome != null && routeOutcome.cacheHit());
        result.put("router_cache", routerCache);
        Map<String, Object> routerShadow = new LinkedHashMap<>();
        routerShadow.put(
                "mode", payrollRouteAdvisor.mode().name().toLowerCase(Locale.ROOT));
        routerShadow.put(
                "ran",
                routeOutcome != null
                        && payrollRouteAdvisor.mode() == PayrollRouteMode.SHADOW
                        && !routeOutcome.bucket().isBlank());
        routerShadow.put("diff", routeOutcome != null && routeOutcome.shadowDiff());
        result.put("router_shadow", routerShadow);
        String flowId = flowActiveId();
        result.put("flow_active", flowId != null);
        result.put("flow_id", flowId);
        result.put("team_available", teamAvailable);
        result.put(
                "candidates",
                candidates.stream()
                        .map(
                                agent -> {
                                    Map<String, Object> item = new LinkedHashMap<>();
                                    item.put("id", agent.id());
                                    item.put("name", agent.name());
                                    item.put("description", agent.description());
                                    return item;
                                })
                        .toList());
        result.put("elapsed_ms", System.currentTimeMillis() - startedAt);
        return ResponseEntity.ok(result);
    }

    /** 预览用的默认流程 id；读取失败按未配置流程处理，不影响路由预览。 */
    private String flowActiveId() {
        if (flowRegistry == null) {
            return null;
        }
        try {
            FlowDefinition flow = flowRegistry.activeFlow();
            return flow == null || flow.id() == null || flow.id().isBlank() ? null : flow.id();
        } catch (RuntimeException e) {
            log.warn("[智能体路由] 预览读取默认流程失败：{}", rootMessage(e));
            return null;
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health(
            @RequestHeader(name = "X-Payroll-Timestamp", required = false) String timestamp,
            @RequestHeader(name = "X-Payroll-Internal", required = false) String signature) {
        ResponseEntity<Map<String, Object>> denied =
                authorize("GET", "/internal/health", timestamp, signature, "");
        if (denied != null) {
            return denied;
        }
        return ResponseEntity.ok(
                Map.of(
                        "status", "UP",
                        "role", role,
                        "model", io.agentscope.examples.payroll.agent.PayrollAgentFactory.modelId()));
    }

    private void execute(
            ChatSessionManager.TurnContext turn, String message, String cacheScope) {
        AtomicBoolean finished = new AtomicBoolean(false);
        try {
            SemanticCacheContext.set(cacheScope);
            turn.chat()
                    .stream(
                            message,
                            ChatSse.busOnly(
                                    manager,
                                    turn,
                                    reply -> {
                                        try {
                                            return manager.completeTurn(turn, reply);
                                        } finally {
                                            finished.set(true);
                                        }
                                    }));
        } catch (RuntimeException e) {
            log.error("[轮次] runtime 执行异常：turn={}", turn.turnId(), e);
            if (finished.compareAndSet(false, true)) {
                manager.failTurn(turn, rootMessage(e));
            }
        } finally {
            SemanticCacheContext.clear();
            if (finished.compareAndSet(false, true)) {
                manager.releaseTurn(turn, "runtime 提前结束");
            }
            manager.finishRemoteTurn(turn.turnId());
        }
    }

    /** 返回非空响应表示鉴权失败；web 角色直接拒绝内部调用。 */
    private ResponseEntity<Map<String, Object>> authorize(
            String method, String path, String timestamp, String signature, String body) {
        if ("web".equals(role)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "not found"));
        }
        if (token.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "未配置内部令牌"));
        }
        if (!InternalAuth.verify(
                token, method, path, timestamp, signature, body, maxSkewSeconds)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "内部签名校验失败"));
        }
        return null;
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

    public record SubmitRequest(
            @JsonProperty("turn_id") String turnId,
            @JsonProperty("session_id") long sessionId,
            @JsonProperty("user_id") long userId,
            @JsonProperty("message") String message,
            @JsonProperty("fencing") long fencing,
            @JsonProperty("owner_node") String ownerNode,
            @JsonProperty("cache_scope") String cacheScope) {
    }

    public record RoutePreviewRequest(
            @JsonProperty("message") String message,
            @JsonProperty("session_id") long sessionId,
            @JsonProperty("user_id") long userId,
            /** 评测用会话夹具：预览默认按未绑定、无引用计算，脚本可显式覆盖。 */
            @JsonProperty("kb_bound") Boolean kbBound,
            @JsonProperty("kb_last_cited") Boolean kbLastCited) {
    }
}
