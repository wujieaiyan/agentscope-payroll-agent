package io.agentscope.payroll.runtime;

import io.agentscope.examples.payroll.agent.McpServerConfig;
import io.agentscope.examples.payroll.agent.ToolSafety;
import io.agentscope.examples.payroll.db.H2AgentTeamStore;
import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.memory.AnswerCache;
import io.agentscope.examples.payroll.memory.VectorMemoryService;
import io.agentscope.examples.payroll.router.AgentRouter;
import io.agentscope.examples.payroll.service.AgentStatsService;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.PayrollChatFactory;
import io.agentscope.examples.payroll.service.PayrollChatService;
import io.agentscope.examples.payroll.service.PayrollEngine;
import io.agentscope.examples.payroll.skill.NacosAgentRegistry;
import io.agentscope.examples.payroll.skill.NacosFlowRegistry;
import io.agentscope.examples.payroll.team.ActiveTeamService;
import io.agentscope.examples.payroll.team.ShadowTeamService;
import io.agentscope.examples.payroll.team.TeamLlmClient;
import io.agentscope.examples.payroll.team.TeamSettings;
import io.agentscope.examples.payroll.web.NodeRoleFilter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 智能体运行期的装配：模型编排、工具安全、动态团队与内部接口入口限制。
 *
 * <p>Web 后端不依赖本模块，因此这里的 Bean 只出现在 agent 节点。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ToolSafetyProperties.class)
public class PayrollAgentConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PayrollAgentConfiguration.class);

    /** 工具读写分类：本地覆盖优先，MCP 是否信任服务端 readOnlyHint 可配置。 */
    @Bean
    public ToolSafety toolSafety(
            ToolSafetyProperties properties,
            @Value("${payroll.mcp.trust-read-only-hints:true}") boolean trustReadOnlyHints) {
        Map<String, Boolean> mcpReadOnly = new LinkedHashMap<>();
        for (McpServerConfig config : McpServerConfig.fromEnvironment()) {
            config.readOnlyTools().forEach(tool -> mcpReadOnly.put(tool, true));
        }
        return new ToolSafety(properties.safetyOverrides(), trustReadOnlyHints, mcpReadOnly);
    }

    /** 强制改判会影响确认行为，启动时逐条写审计，便于事后核对是谁放行的。 */
    @Bean
    public ApplicationRunner toolSafetyAuditRunner(
            AuditService auditService, ToolSafety toolSafety) {
        return args -> {
            toolSafety
                    .overrides()
                    .forEach(
                            (tool, readOnly) ->
                                    auditSafetyOverride(
                                            auditService,
                                            "payroll.tools.safety-overrides",
                                            tool,
                                            readOnly));
            toolSafety
                    .mcpReadOnlyTools()
                    .forEach(
                            (tool, readOnly) ->
                                    auditSafetyOverride(
                                            auditService,
                                            "mcp.readOnlyTools",
                                            tool,
                                            readOnly));
        };
    }

    private static void auditSafetyOverride(
            AuditService auditService, String source, String toolName, boolean readOnly) {
        try {
            auditService.record(
                    "chat.action.safety.override",
                    "tool_safety",
                    toolName,
                    null,
                    Map.of("source", source, "read_only", readOnly));
            log.info(
                    "[工具安全] 启动审计已记录强制改判：tool={}，来源={}，readOnly={}",
                    toolName,
                    source,
                    readOnly);
        } catch (RuntimeException e) {
            log.warn(
                    "[工具安全] 记录强制改判审计失败：tool={}，来源={}，原因={}",
                    toolName,
                    source,
                    e.getMessage());
        }
    }

    @Bean
    public TeamLlmClient teamLlmClient() {
        return new TeamLlmClient();
    }

    @Bean
    public ActiveTeamService activeTeamService(
            TeamSettings teamSettings,
            H2AgentTeamStore agentTeamStore,
            AgentStatsService agentStatsService,
            NacosAgentRegistry agentRegistry,
            TeamLlmClient teamLlmClient,
            AuditService auditService) {
        ActiveTeamService service =
                new ActiveTeamService(
                        teamSettings,
                        agentTeamStore,
                        agentStatsService,
                        agentRegistry::list,
                        teamLlmClient,
                        auditService);
        log.info(
                "[动态团队] ACTIVE 服务：{}",
                service.enabled() ? "已启用（按白名单生效）" : "未启用");
        return service;
    }

    @Bean(destroyMethod = "close")
    public ShadowTeamService shadowTeamService(
            TeamSettings teamSettings,
            H2AgentTeamStore agentTeamStore,
            H2ChatStore chatStore,
            AgentStatsService agentStatsService,
            NacosAgentRegistry agentRegistry,
            PayrollChatFactory payrollChatFactory,
            TeamLlmClient teamLlmClient) {
        ShadowTeamService service =
                new ShadowTeamService(
                        teamSettings,
                        agentTeamStore,
                        chatStore,
                        agentStatsService,
                        agentRegistry::list,
                        payrollChatFactory,
                        teamLlmClient,
                        null);
        log.info("[动态团队] 影子服务：{}", service.enabled() ? "已启用" : "未启用");
        return service;
    }

    /** 纯轻量模型路由：所有目标由模型决定，失败时保持既有链路。 */
    @Bean
    public AgentRouter agentRouter(
            TeamLlmClient teamLlmClient,
            @Value("${payroll.router.enabled:true}") boolean enabled,
            @Value("${payroll.router.timeout-ms:10000}") long timeoutMs,
            @Value("${payroll.router.min-confidence:0.55}") double minConfidence) {
        AgentRouter router =
                new AgentRouter(
                        teamLlmClient,
                        enabled,
                        minConfidence,
                        java.time.Duration.ofMillis(Math.max(500L, timeoutMs)));
        log.info(
                "[智能体路由] {}，超时={}ms，置信度阈值={}",
                router.enabled() ? "已启用" : "未启用",
                Math.max(500L, timeoutMs),
                minConfidence);
        return router;
    }

    /** 每个对话窗口独立实例：模型上下文、路由与团队执行都按会话隔离。 */
    @Bean
    public PayrollChatFactory payrollChatFactory(
            PayrollEngine engine,
            H2ChatStore chatStore,
            VectorMemoryService memory,
            AnswerCache answerCache,
            NacosAgentRegistry agentRegistry,
            NacosFlowRegistry flowRegistry,
            ActiveTeamService activeTeamService,
            ToolSafety toolSafety,
            AgentRouter router,
            @Value("${payroll.orchestrator:legacy}") String orchestrator,
            @Value("${payroll.routing.enabled:true}") boolean routingEnabled,
            @Value("${payroll.chat.action-confirm.enabled:false}") boolean actionConfirmEnabled,
            @Value("${payroll.chat.action-confirm.ttl-minutes:15}") int actionProposalTtlMinutes,
            @Value("${payroll.chat.action-confirm.always-confirm-tools:}")
                    String alwaysConfirmToolsCsv) {
        Set<String> alwaysConfirmTools = parseTools(alwaysConfirmToolsCsv);
        boolean graph = "graph".equalsIgnoreCase(orchestrator == null ? "" : orchestrator.trim());
        if (graph) {
            log.info("[多智能体] 编排模式：langgraph4j 图编排（POC）");
        } else {
            if (orchestrator != null && !"legacy".equalsIgnoreCase(orchestrator.trim())) {
                log.warn("[多智能体] 未知编排模式 {}，回退为 legacy", orchestrator);
            }
            log.info("[多智能体] 编排模式：手写状态机");
        }
        if (!actionConfirmEnabled && alwaysConfirmTools.isEmpty()) {
            log.warn("[写操作] 会话内确认开关关闭：写工具将按旧行为直接执行");
        } else if (!actionConfirmEnabled) {
            log.warn(
                    "[写操作] 会话内确认开关关闭，但以下工具仍强制确认：{}",
                    String.join(",", alwaysConfirmTools));
        }
        return (sessionId, userId) ->
                new PayrollChatService(
                        engine,
                        chatStore,
                        sessionId,
                        userId,
                        graph,
                        memory,
                        agentRegistry::effectiveAgent,
                        flowRegistry::activeFlow,
                        flowRegistry::activeFlowId,
                        agentRegistry::list,
                        activeTeamService,
                        routingEnabled,
                        actionConfirmEnabled,
                        actionProposalTtlMinutes,
                        toolSafety,
                        answerCache,
                        router,
                        alwaysConfirmTools);
    }

    /** 逗号分隔的工具名；空值表示不强制，默认值由 application-common.yml 提供。 */
    private static Set<String> parseTools(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<String> tools = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                tools.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(tools);
    }

    /** runtime 只开放 /internal，避免模型执行入口被外部直接访问。 */
    @Bean
    public FilterRegistrationBean<NodeRoleFilter> nodeRoleFilter(
            @Value("${payroll.node-role:runtime}") String role) {
        FilterRegistrationBean<NodeRoleFilter> registration =
                new FilterRegistrationBean<>(new NodeRoleFilter(role));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        return registration;
    }
}
