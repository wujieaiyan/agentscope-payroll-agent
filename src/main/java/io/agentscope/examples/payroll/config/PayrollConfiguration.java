package io.agentscope.examples.payroll.config;

import io.agentscope.examples.payroll.agent.McpServerConfig;
import io.agentscope.examples.payroll.agent.ToolSafety;
import io.agentscope.examples.payroll.db.H2AdminRepository;
import io.agentscope.examples.payroll.db.H2ActionProposalStore;
import io.agentscope.examples.payroll.db.H2AgentTeamStore;
import io.agentscope.examples.payroll.db.H2ApprovalStore;
import io.agentscope.examples.payroll.db.H2AuditStore;
import io.agentscope.examples.payroll.db.H2ChatFeedbackStore;
import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.db.H2PayrollRepository;
import io.agentscope.examples.payroll.db.H2PayrollRunStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.PayrollPolicy;
import io.agentscope.examples.payroll.memory.VectorMemoryService;
import io.agentscope.examples.payroll.memory.VectorMemorySettings;
import io.agentscope.examples.payroll.service.ActionExecutor;
import io.agentscope.examples.payroll.service.ActionProposalService;
import io.agentscope.examples.payroll.service.AgentStatsService;
import io.agentscope.examples.payroll.service.ApprovalService;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.service.ChatFeedbackService;
import io.agentscope.examples.payroll.service.ChatQuotaService;
import io.agentscope.examples.payroll.service.ChatSessionManager;
import io.agentscope.examples.payroll.service.DefaultActionExecutor;
import io.agentscope.examples.payroll.service.DryRunEmailGateway;
import io.agentscope.examples.payroll.service.ExcelExporter;
import io.agentscope.examples.payroll.service.ExcelImportService;
import io.agentscope.examples.payroll.service.LoginRateLimiter;
import io.agentscope.examples.payroll.service.PayrollChat;
import io.agentscope.examples.payroll.service.PayrollChatFactory;
import io.agentscope.examples.payroll.service.PayrollChatService;
import io.agentscope.examples.payroll.service.PayrollEngine;
import io.agentscope.examples.payroll.service.PayrollRunService;
import io.agentscope.examples.payroll.service.PayrollServices;
import io.agentscope.examples.payroll.service.SessionLockService;
import io.agentscope.examples.payroll.service.StaticRunRecorder;
import io.agentscope.examples.payroll.service.TeamRunQueryService;
import io.agentscope.examples.payroll.skill.NacosAgentRegistry;
import io.agentscope.examples.payroll.skill.NacosFlowRegistry;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry;
import io.agentscope.examples.payroll.team.ActiveTeamService;
import io.agentscope.examples.payroll.team.ShadowReportService;
import io.agentscope.examples.payroll.team.ShadowTeamService;
import io.agentscope.examples.payroll.team.TeamAdminService;
import io.agentscope.examples.payroll.team.TeamLlmClient;
import io.agentscope.examples.payroll.team.TeamMode;
import io.agentscope.examples.payroll.team.TeamSettings;
import io.agentscope.examples.payroll.web.AuditFilter;
import io.agentscope.examples.payroll.web.AuthInterceptor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Spring 容器中的薪酬组件装配，与 CLI 使用同一套领域实现。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ToolSafetyProperties.class)
public class PayrollConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PayrollConfiguration.class);

    @Bean
    public PayrollDatabase payrollDatabase(
            @Value("${payroll.db.url}") String url,
            @Value("${payroll.db.user}") String user,
            @Value("${payroll.db.password}") String password,
            @Value("${payroll.db.pool-size:16}") int poolSize) {
        return PayrollDatabase.open(url, user, password, poolSize);
    }

    /** redis profile 下注入 StringRedisTemplate；未启用 Redis 时为 null，限流/配额自动回退内存。 */
    @Bean
    public LoginRateLimiter loginRateLimiter(
            ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${payroll.security.login-max-failures:5}") int maxFailures,
            @Value("${payroll.security.login-lock-minutes:5}") int lockMinutes) {
        return new LoginRateLimiter(redisProvider.getIfAvailable(), maxFailures, lockMinutes);
    }

    @Bean
    public ChatQuotaService chatQuotaService(
            ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${payroll.chat.max-concurrent-per-user:2}") int maxConcurrent) {
        return new ChatQuotaService(redisProvider.getIfAvailable(), maxConcurrent);
    }

    /** redis profile 下同一会话跨实例互斥；未启用 Redis 时退化为进程内锁。 */
    @Bean
    public SessionLockService sessionLockService(
            ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${payroll.chat.session-lock-minutes:15}") int ttlMinutes) {
        return new SessionLockService(redisProvider.getIfAvailable(), ttlMinutes);
    }

    @Bean
    public H2PayrollRepository payrollRepository(PayrollDatabase database) {
        return new H2PayrollRepository(database);
    }

    @Bean
    public PayrollEngine payrollEngine(H2PayrollRepository repository) {
        PayrollPolicy defaultPolicy = repository.policyFor("默认");
        if (defaultPolicy == null) {
            throw new IllegalStateException("默认城市政策缺失，无法启动");
        }
        return new PayrollEngine(repository, repository, defaultPolicy);
    }

    @Bean
    public H2PayrollRunStore payrollRunStore(PayrollDatabase database) {
        return new H2PayrollRunStore(database);
    }

    @Bean
    public PayrollRunService payrollRunService(
            PayrollEngine engine,
            H2PayrollRepository repository,
            H2PayrollRunStore runStore,
            @Value("${payroll.output-dir}") String outputDir) {
        return new PayrollRunService(
                engine,
                repository,
                runStore,
                new ExcelExporter(),
                new DryRunEmailGateway(),
                Path.of(outputDir));
    }

    @Bean
    public H2ApprovalStore approvalStore(PayrollDatabase database) {
        return new H2ApprovalStore(database);
    }

    @Bean
    public ApprovalService approvalService(
            H2ApprovalStore store, H2PayrollRunStore runStore) {
        return new ApprovalService(store, runStore);
    }

    @Bean
    public PayrollChat payrollChat(PayrollEngine engine) {
        return new PayrollChatService(engine);
    }

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
    public PayrollChatFactory payrollChatFactory(
            PayrollEngine engine,
            H2ChatStore chatStore,
            VectorMemoryService memory,
            NacosAgentRegistry agentRegistry,
            NacosFlowRegistry flowRegistry,
            ActiveTeamService activeTeamService,
            ToolSafety toolSafety,
            @Value("${payroll.orchestrator:legacy}") String orchestrator,
            @Value("${payroll.routing.enabled:true}") boolean routingEnabled,
            @Value("${payroll.chat.action-confirm.enabled:false}") boolean actionConfirmEnabled,
            @Value("${payroll.chat.action-confirm.ttl-minutes:15}") int actionProposalTtlMinutes) {
        boolean graph = "graph".equalsIgnoreCase(orchestrator == null ? "" : orchestrator.trim());
        if (graph) {
            log.info("[多智能体] 编排模式：langgraph4j 图编排（POC）");
        } else {
            if (orchestrator != null && !"legacy".equalsIgnoreCase(orchestrator.trim())) {
                log.warn("[多智能体] 未知编排模式 {}，回退为 legacy", orchestrator);
            }
            log.info("[多智能体] 编排模式：手写状态机");
        }
        if (!actionConfirmEnabled) {
            log.warn("[写操作] 会话内确认开关关闭：写工具将按旧行为直接执行");
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
                        toolSafety);
    }

    /** 智能体定义与全局核算智能体设置都以 Nacos 为存储，管理接口与运行时共享同一实例。 */
    @Bean
    public NacosAgentRegistry agentRegistry() {
        return new NacosAgentRegistry();
    }

    /** 流程定义与全局默认流程同样存储于 Nacos，管理台与对话运行时共享同一实例。 */
    @Bean
    public NacosFlowRegistry flowRegistry() {
        return new NacosFlowRegistry();
    }

    /** 技能管理直接读写 Nacos 中的 {@code <skill-id>.md}，与运行时技能仓库同源。 */
    @Bean
    public NacosSkillRegistry skillRegistry() {
        return new NacosSkillRegistry();
    }

    @Bean(destroyMethod = "close")
    public VectorMemoryService vectorMemoryService(
            @Value("${payroll.memory.enabled:false}") boolean enabled,
            @Value("${payroll.memory.es-url:http://localhost:9200}") String esUrl,
            @Value("${payroll.memory.es-username:}") String esUsername,
            @Value("${payroll.memory.es-password:}") String esPassword,
            @Value("${payroll.memory.index:payroll-chat-memory}") String index,
            @Value("${payroll.memory.embedding-base-url:http://localhost:11434}")
                    String embeddingBaseUrl,
            @Value("${payroll.memory.embedding-model:nomic-embed-text}") String embeddingModel,
            @Value("${payroll.memory.embedding-dim:768}") int embeddingDim,
            @Value("${payroll.memory.embed-timeout:3s}") Duration embedTimeout,
            @Value("${payroll.memory.top-k:5}") int topK,
            @Value("${payroll.memory.min-score:0.55}") double minScore,
            @Value("${payroll.memory.max-inject-chars:1200}") int maxInjectChars,
            @Value("${payroll.memory.async-index:true}") boolean asyncIndex,
            @Value("${payroll.memory.cross-session:false}") boolean crossSession) {
        return VectorMemoryService.start(
                new VectorMemorySettings(
                        enabled,
                        esUrl,
                        esUsername,
                        esPassword,
                        index,
                        embeddingBaseUrl,
                        embeddingModel,
                        embeddingDim,
                        embedTimeout,
                        topK,
                        minScore,
                        maxInjectChars,
                        asyncIndex,
                        crossSession));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public H2AdminRepository adminRepository(
            PayrollDatabase database,
            PasswordEncoder passwordEncoder,
            @Value("${payroll.auth.admin-password:admin123}") String adminPassword) {
        H2AdminRepository repository = new H2AdminRepository(database);
        if (repository.ensureAdminUser(passwordEncoder.encode(adminPassword))) {
            if ("admin123".equals(adminPassword)) {
                log.warn("已创建默认管理员 admin / admin123，请登录后立即修改密码");
            } else {
                log.info("已创建管理员 admin，密码取自 PAYROLL_ADMIN_PASSWORD");
            }
        }
        return repository;
    }

    @Bean
    public H2ChatStore chatStore(PayrollDatabase database) {
        return new H2ChatStore(database);
    }

    @Bean
    public H2AgentTeamStore agentTeamStore(PayrollDatabase database) {
        return new H2AgentTeamStore(database);
    }

    @Bean
    public H2ActionProposalStore actionProposalStore(PayrollDatabase database) {
        return new H2ActionProposalStore(database);
    }

    @Bean
    public ActionExecutor actionExecutor() {
        return new DefaultActionExecutor();
    }

    @Bean
    public ActionProposalService actionProposalService(
            H2ChatStore chatStore,
            H2ActionProposalStore actionProposalStore,
            ActionExecutor actionExecutor,
            AuditService auditService) {
        return new ActionProposalService(
                chatStore, actionProposalStore, actionExecutor, auditService);
    }

    @Bean
    public H2ChatFeedbackStore chatFeedbackStore(PayrollDatabase database) {
        return new H2ChatFeedbackStore(database);
    }

    @Bean
    public ChatFeedbackService chatFeedbackService(
            H2ChatStore chatStore, H2ChatFeedbackStore feedbackStore) {
        return new ChatFeedbackService(chatStore, feedbackStore);
    }

    @Bean
    public TeamRunQueryService teamRunQueryService(
            H2AgentTeamStore teamStore,
            H2ChatStore chatStore,
            ChatFeedbackService feedbackService) {
        return new TeamRunQueryService(teamStore, chatStore, feedbackService);
    }

    @Bean
    public AgentStatsService agentStatsService(
            H2AgentTeamStore teamStore,
            H2ChatFeedbackStore feedbackStore,
            TeamSettings teamSettings,
            NacosAgentRegistry agentRegistry) {
        return new AgentStatsService(teamStore, feedbackStore, teamSettings, agentRegistry::list);
    }

    @Bean
    public TeamSettings teamSettings(
            @Value("${payroll.team.mode:OFF}") String mode,
            @Value("${payroll.team.retention-days:180}") int retentionDays,
            @Value("${payroll.team.stats-window-days:30}") int statsWindowDays,
            @Value("${payroll.team.shadow-sample-rate:0.15}") double shadowSampleRate,
            @Value("${payroll.team.active-user-ids:}") String activeUserIds,
            @Value("${payroll.team.active-session-ids:}") String activeSessionIds) {
        TeamMode parsed = TeamMode.from(mode);
        if (mode != null && !mode.isBlank() && !parsed.name().equalsIgnoreCase(mode.trim())) {
            log.warn("[动态团队] 未知模式 {}，按 OFF 处理", mode);
        }
        Set<Long> users = parseIds(activeUserIds, "active-user-ids");
        Set<Long> sessions = parseIds(activeSessionIds, "active-session-ids");
        log.info(
                "[动态团队] 模式：{}，ACTIVE 白名单：用户={}，会话={}",
                parsed,
                users.isEmpty() ? "全部" : users,
                sessions.isEmpty() ? "全部" : sessions);
        return new TeamSettings(
                parsed, retentionDays, statsWindowDays, shadowSampleRate, users, sessions);
    }

    private static Set<Long> parseIds(String value, String key) {
        Set<Long> ids = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return ids;
        }
        for (String part : value.split("[,;\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException e) {
                log.warn("[动态团队] 忽略非法 {}：{}", key, part);
            }
        }
        return ids;
    }

    @Bean
    public ShadowReportService shadowReportService(
            H2AgentTeamStore agentTeamStore, TeamSettings teamSettings) {
        return new ShadowReportService(agentTeamStore, teamSettings);
    }

    @Bean
    public TeamAdminService teamAdminService(
            TeamSettings teamSettings, H2AgentTeamStore agentTeamStore) {
        return new TeamAdminService(teamSettings, agentTeamStore);
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

    @Bean(destroyMethod = "close")
    public ChatSessionManager chatSessionManager(
            H2ChatStore chatStore,
            PayrollChatFactory chatFactory,
            VectorMemoryService memory,
            H2AgentTeamStore agentTeamStore,
            TeamSettings teamSettings,
            ShadowTeamService shadowTeamService,
            ActiveTeamService activeTeamService,
            SessionLockService sessionLockService,
            AuditService auditService,
            @Value("${payroll.chat.cache-max-sessions:200}") int cacheMaxSessions,
            @Value("${payroll.chat.cache-idle-minutes:60}") int cacheIdleMinutes) {
        return new ChatSessionManager(
                chatStore,
                chatFactory,
                memory,
                new StaticRunRecorder(agentTeamStore, teamSettings),
                shadowTeamService,
                activeTeamService,
                sessionLockService,
                cacheMaxSessions,
                cacheIdleMinutes,
                auditService);
    }

    @Bean
    public AuthService authService(
            H2AdminRepository adminRepository,
            PasswordEncoder passwordEncoder,
            @Value("${payroll.auth.enabled:true}") boolean authEnabled) {
        return new AuthService(adminRepository, passwordEncoder, authEnabled);
    }

    @Bean
    public AuthInterceptor authInterceptor(AuthService authService) {
        return new AuthInterceptor(authService);
    }

    @Bean
    public H2AuditStore auditStore(PayrollDatabase database) {
        return new H2AuditStore(database);
    }

    @Bean
    public AuditService auditService(
            H2AuditStore auditStore,
            @Value("${payroll.audit.retention-days:180}") int retentionDays) {
        return new AuditService(auditStore, retentionDays);
    }

    @Bean
    public FilterRegistrationBean<AuditFilter> auditFilter(
            AuditService auditService, AuthService authService) {
        FilterRegistrationBean<AuditFilter> registration =
                new FilterRegistrationBean<>(new AuditFilter(auditService, authService));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /** 启动时清理超过保留期的审计日志，避免 H2 文件无限增长。 */
    @Bean
    public ApplicationRunner auditRetentionRunner(AuditService auditService) {
        return args -> {
            try {
                int removed = auditService.purgeExpired();
                if (removed > 0) {
                    log.info(
                            "[审计] 清理过期日志 {} 条（保留 {} 天）",
                            removed,
                            auditService.retentionDays());
                }
            } catch (RuntimeException e) {
                log.warn("[审计] 清理过期日志失败：{}", e.getMessage());
            }
        };
    }

    /** 启动时软归档超过保留期的团队运行；节点与 trace 保留，供审计按 run_id 追溯。 */
    @Bean
    public ApplicationRunner teamRetentionRunner(
            H2AgentTeamStore teamStore, TeamSettings teamSettings) {
        return args -> {
            try {
                int archived = teamStore.archiveExpired(teamSettings.retentionDays());
                if (archived > 0) {
                    log.info(
                            "[动态团队] 归档过期运行 {} 条（保留 {} 天）",
                            archived,
                            teamSettings.retentionDays());
                }
            } catch (RuntimeException e) {
                log.warn("[动态团队] 归档过期运行失败：{}", e.getMessage());
            }
        };
    }

    @Bean
    public ExcelImportService excelImportService(H2PayrollRepository repository) {
        return new ExcelImportService(repository);
    }

    /** 流式对话线程池：SseEmitter 在独立线程中消费模型事件。 */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService chatStreamExecutor() {
        return Executors.newFixedThreadPool(
                4,
                runnable -> {
                    Thread thread = new Thread(runnable, "payroll-chat-stream");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Bean(destroyMethod = "close")
    public PayrollServices payrollServices(
            PayrollDatabase database,
            H2PayrollRepository repository,
            PayrollEngine engine,
            PayrollRunService runService,
            PayrollChat chat,
            ExcelImportService excelImportService) {
        return PayrollServices.of(
                database, repository, engine, runService, chat, excelImportService);
    }

    /** 启动完成后打印一次关键运行参数，方便本机排查端口与数据落盘位置。 */
    @Bean
    public ApplicationRunner payrollStartupReporter(
            PayrollServices services, Environment environment) {
        return args -> {
            String port =
                    environment.getProperty("local.server.port", "0");
            String address = environment.getProperty("server.address", "127.0.0.1");
            log.info("=".repeat(72));
            log.info("薪酬管理台  http://{}:{}", address, port);
            log.info("数据库      {}", services.database().url());
            log.info("邮件模式    dry-run（只校验并记录，不会真实投递）");
            log.info("输出目录    {}", services.runService().outputDir().toAbsolutePath());
            log.info("按 Ctrl+C 停止");
            log.info("=".repeat(72));
        };
    }
}
