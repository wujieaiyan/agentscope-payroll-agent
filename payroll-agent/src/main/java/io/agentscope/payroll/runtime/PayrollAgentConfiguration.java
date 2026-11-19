package io.agentscope.payroll.runtime;

import io.agentscope.examples.payroll.agent.DevLlmTaskRunner;
import io.agentscope.examples.payroll.agent.KbRoutingPolicy;
import io.agentscope.examples.payroll.agent.McpServerConfig;
import io.agentscope.examples.payroll.agent.PayrollAgentFactory;
import io.agentscope.examples.payroll.agent.PayrollRouteBucket;
import io.agentscope.examples.payroll.agent.PayrollRouteCache;
import io.agentscope.examples.payroll.agent.PayrollRouteMode;
import io.agentscope.examples.payroll.agent.SemanticRouteClassifier;
import io.agentscope.examples.payroll.agent.SemanticRouteSettings;
import io.agentscope.examples.payroll.agent.ToolSafety;
import io.agentscope.examples.payroll.db.DevArtifactStore;
import io.agentscope.examples.payroll.db.DevTaskStore;
import io.agentscope.examples.payroll.db.H2AgentTeamStore;
import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.devteam.DevBuildRunner;
import io.agentscope.examples.payroll.devteam.DevDockerBuildRunner;
import io.agentscope.examples.payroll.devteam.DevPathPolicy;
import io.agentscope.examples.payroll.devteam.DevRunnerSettings;
import io.agentscope.examples.payroll.devteam.DevTaskBoard;
import io.agentscope.examples.payroll.devteam.DevTaskExecutor;
import io.agentscope.examples.payroll.devteam.DevTaskRunner;
import io.agentscope.examples.payroll.devteam.DevTeamSettings;
import io.agentscope.examples.payroll.devteam.ProcessCommandLauncher;
import io.agentscope.examples.payroll.memory.AnswerCache;
import io.agentscope.examples.payroll.memory.VectorMemoryService;
import io.agentscope.examples.payroll.model.ModelOutboundPolicy;
import io.agentscope.examples.payroll.router.AgentRouter;
import io.agentscope.examples.payroll.router.PayrollRouteAdvisor;
import io.agentscope.examples.payroll.service.AgentStatsService;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.PayrollChatFactory;
import io.agentscope.examples.payroll.service.ModelCredentialService;
import io.agentscope.examples.payroll.service.PayrollChatService;
import io.agentscope.examples.payroll.service.PayrollEngine;
import io.agentscope.examples.payroll.skill.ModelConnectionRegistry;
import io.agentscope.examples.payroll.skill.NacosAgentRegistry;
import io.agentscope.examples.payroll.skill.NacosFlowRegistry;
import io.agentscope.examples.payroll.team.ActiveTeamService;
import io.agentscope.examples.payroll.team.ShadowTeamService;
import io.agentscope.examples.payroll.team.TeamLlmClient;
import io.agentscope.examples.payroll.team.TeamSettings;
import io.agentscope.examples.payroll.web.NodeRoleFilter;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;

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

    /** 模型连接注册表与凭证服务注入静态工厂；本节点所有会话共用同一解析结果。 */
    @Bean
    public ApplicationRunner modelFactoryConfigurer(
            ModelConnectionRegistry registry,
            ModelCredentialService credentials,
            ModelOutboundPolicy policy) {
        return args -> {
            PayrollAgentFactory.configureModels(registry, credentials, policy);
            log.info(
                    "[Model] 模型注册表已就绪：{}，页面密钥={}",
                    registry.location(),
                    credentials.secretKeyAvailable() ? "可用" : "不可用");
        };
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

    /**
     * 开发域两维权限（A1）：启动即校验组合，非法配置直接启动失败；
     * 编码模型不可用时 workspace-write 降级为 read-only，并在审计里留下原因。
     */
    @Bean
    public DevTeamSettings devTeamSettings(
            @Value("${payroll.dev-team.mode:read-only}") String mode,
            @Value("${payroll.dev-team.approval:}") String approval) {
        DevTeamSettings settings = DevTeamSettings.fromEnvironment(mode, approval);
        log.info(
                "[开发域] 模式={}（配置={}），审批={}，写权限={}，编码模型={}，上下文={}",
                settings.mode().wireName(),
                settings.requestedMode().wireName(),
                settings.approval().wireName(),
                settings.writeEnabled() ? "已启用" : "关闭",
                settings.model().configured() ? settings.model().modelId() : "未配置",
                settings.model().numCtx());
        if (settings.degraded()) {
            log.warn("[开发域] {}；写模式已关闭", settings.disabledReason());
        }
        return settings;
    }

    @Bean
    public ApplicationRunner devTeamAuditRunner(
            AuditService auditService, DevTeamSettings settings) {
        return args -> {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("mode", settings.mode().wireName());
            snapshot.put("requested_mode", settings.requestedMode().wireName());
            snapshot.put("approval", settings.approval().wireName());
            snapshot.put("write_enabled", settings.writeEnabled());
            snapshot.put("model", settings.model().modelId());
            snapshot.put("num_ctx", settings.model().numCtx());
            snapshot.put("degraded_reason", settings.disabledReason());
            try {
                auditService.record("devteam.config", "dev_team", "startup", null, snapshot);
            } catch (RuntimeException e) {
                log.warn("[开发域] 记录启动配置审计失败：{}", e.getMessage());
            }
        };
    }

    @Bean
    public DevTaskStore devTaskStore(PayrollDatabase database) {
        return new DevTaskStore(database);
    }

    @Bean
    public DevArtifactStore devArtifactStore(PayrollDatabase database) {
        return new DevArtifactStore(database);
    }

    /** 任务面板：状态机与依赖调度都落库，后端与执行节点按同一份数据工作。 */
    @Bean
    public DevTaskBoard devTaskBoard(DevTaskStore devTaskStore) {
        return new DevTaskBoard(devTaskStore);
    }

    /**
     * 受限 runner 配置（P1-3）：默认关闭；启用后构建校验只在 {@code docker run --network none}
     * 中执行，并限制内存/CPU/进程数，缓存目录为空则不挂载。
     */
    @Bean
    public DevRunnerSettings devRunnerSettings(
            @Value("${payroll.dev-team.runner.enabled:false}") boolean enabled,
            @Value("${payroll.dev-team.runner.docker:docker}") String dockerBinary,
            @Value("${payroll.dev-team.runner.image:}") String defaultImage,
            @Value("${payroll.dev-team.runner.memory-mb:2048}") long memoryMb,
            @Value("${payroll.dev-team.runner.cpus:2}") double cpus,
            @Value("${payroll.dev-team.runner.pids-limit:512}") long pidsLimit,
            @Value("${payroll.dev-team.runner.maven-cache:}") String mavenCacheDir,
            @Value("${payroll.dev-team.runner.npm-cache:}") String npmCacheDir,
            @Value("${payroll.dev-team.runner.max-output-chars:8000}") int maxOutputChars) {
        DevRunnerSettings settings =
                new DevRunnerSettings(
                        enabled,
                        dockerBinary,
                        defaultImage,
                        memoryMb,
                        cpus,
                        pidsLimit,
                        mavenCacheDir,
                        npmCacheDir,
                        maxOutputChars);
        log.info(
                "[开发域] 受限 runner：{}，镜像默认={}，内存={}MB，CPU={}，pids={}，m2 缓存={}，npm 缓存={}",
                settings.enabled() ? "启用" : "关闭",
                settings.defaultImage().isBlank() ? "未配置" : settings.defaultImage(),
                settings.memoryMb(),
                settings.cpus(),
                settings.pidsLimit(),
                settings.hasMavenCache() ? settings.mavenCacheDir() : "不挂载",
                settings.hasNpmCache() ? settings.npmCacheDir() : "不挂载");
        return settings;
    }

    @Bean
    public DevBuildRunner devBuildRunner(DevRunnerSettings runnerSettings) {
        return new DevDockerBuildRunner(
                runnerSettings, new ProcessCommandLauncher(runnerSettings.maxOutputChars()));
    }

    @Bean
    public ApplicationRunner devRunnerAuditRunner(
            AuditService auditService, DevRunnerSettings runnerSettings) {
        return args -> {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("enabled", runnerSettings.enabled());
            snapshot.put("docker", runnerSettings.dockerBinary());
            snapshot.put("image", runnerSettings.defaultImage());
            snapshot.put("memory_mb", runnerSettings.memoryMb());
            snapshot.put("cpus", runnerSettings.cpus());
            snapshot.put("pids_limit", runnerSettings.pidsLimit());
            snapshot.put("maven_cache", runnerSettings.mavenCacheDir());
            snapshot.put("npm_cache", runnerSettings.npmCacheDir());
            try {
                auditService.record("devteam.runner", "dev_team", "startup", null, snapshot);
            } catch (RuntimeException e) {
                log.warn("[开发域] 记录受限 runner 配置审计失败：{}", e.getMessage());
            }
        };
    }

    /** 编码面串行执行（P1-2/P1-6）：单线程 + 无界队列，每实例并发 1，避免多任务争抢本机模型与工作区。 */
    @Bean(name = "devTaskExecutorPool", destroyMethod = "shutdown")
    public ExecutorService devTaskExecutorPool() {
        log.info("[开发域] 编码面并发=1（单线程执行器），与控制面 10s/150s 预算隔离");
        return Executors.newSingleThreadExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable, "dev-task-executor");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Bean
    public DevTaskExecutor devTaskExecutor(
            DevTeamSettings settings,
            DevTaskBoard board,
            DevArtifactStore artifacts,
            DevBuildRunner buildRunner,
            AuditService auditService,
            @Qualifier("devTaskExecutorPool") ExecutorService pool,
            @Value("${payroll.dev-team.project-root:}") String projectRoot,
            @Value("${payroll.dev-team.workspace-root:}") String workspaceRoot) {
        Path root =
                projectRoot == null || projectRoot.isBlank()
                        ? DevLlmTaskRunner.defaultProjectRoot()
                        : Path.of(projectRoot).toAbsolutePath().normalize();
        if (root == null || !java.nio.file.Files.isDirectory(root)) {
            log.warn(
                    "[开发域] 研发仓库根目录不可用，任务执行会失败；请配置 payroll.dev-team.project-root");
            root =
                    root == null
                            ? Path.of(System.getProperty("user.dir"))
                                    .toAbsolutePath()
                                    .normalize()
                            : root;
        }
        Path workRoot =
                workspaceRoot == null || workspaceRoot.isBlank()
                        ? DevLlmTaskRunner.defaultWorkRoot()
                        : Path.of(workspaceRoot).toAbsolutePath().normalize();
        DevTaskRunner runner =
                new DevLlmTaskRunner(
                        settings, artifacts, root, workRoot, auditService, buildRunner);
        log.info(
                "[开发域] 任务中心已装配：仓库根={}，工作区根={}，写权限={}",
                root,
                workRoot,
                settings.writeEnabled() ? "已启用" : "关闭");
        return new DevTaskExecutor(board, runner, pool, auditService);
    }

    /**
     * 仓库路径 profile（P0-6）：未配置时使用内置 payroll 范围；配置缺失或非法直接启动失败，
     * 避免带着错误的写白名单执行任务。
     */
    @Bean
    public ApplicationRunner devTeamPathProfileRunner(
            @Value("${payroll.dev-team.paths-file:}") String profileFile,
            AuditService auditService) {
        return args -> {
            String configured = profileFile;
            if (configured == null || configured.isBlank()) {
                configured = System.getenv(DevPathPolicy.ENV_PROFILE);
            }
            if (configured == null || configured.isBlank()) {
                log.info("[开发域] 路径 profile：内置 payroll 范围");
                return;
            }
            Path file = Path.of(configured).toAbsolutePath().normalize();
            DevPathPolicy.loadProfile(file);
            log.info("[开发域] 路径 profile 已加载：{}", file);
            try {
                auditService.record(
                        "devteam.path-profile",
                        "dev_team",
                        "startup",
                        null,
                        Map.of(
                                "file", file.toString(),
                                "implementer", DevPathPolicy.describeScope(DevPathPolicy.ROLE_IMPLEMENTER),
                                "frontend", DevPathPolicy.describeScope(DevPathPolicy.ROLE_FRONTEND)));
            } catch (RuntimeException e) {
                log.warn("[开发域] 记录路径 profile 审计失败：{}", e.getMessage());
            }
        };
    }

    /** 上一实例遗留的 RUNNING 任务统一置为失败，等待人工重试，绝不自动续跑。 */
    @Bean
    public ApplicationRunner devTaskRecoveryRunner(DevTaskExecutor devTaskExecutor) {
        return args -> devTaskExecutor.recoverInterrupted();
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

    /** 语义路由设置：KB 语义与薪酬语义共用嵌入/ES 配置，仅索引与种子不同。 */
    @Bean
    public SemanticRouteSettings routeSemanticSettings(
            @Value("${payroll.router.semantic.enabled:false}") boolean enabled,
            @Value("${payroll.router.semantic.min-score:0.55}") double minScore,
            @Value("${payroll.router.semantic.margin:0.05}") double margin,
            @Value("${payroll.router.semantic.index:payroll-route-intents}") String index,
            @Value("${payroll.router.semantic.timeout-ms:150}") int timeoutMs,
            @Value("${payroll.router.semantic.seed-timeout-seconds:60}") int seedTimeoutSeconds,
            @Value("${payroll.router.semantic.es-url:http://localhost:9200}") String esUrl,
            @Value("${payroll.router.semantic.es-username:}") String esUsername,
            @Value("${payroll.router.semantic.es-password:}") String esPassword,
            @Value("${payroll.router.semantic.embedding-base-url:http://localhost:11434}")
                    String embeddingBaseUrl,
            @Value("${payroll.router.semantic.embedding-model:qwen3-embedding:0.6b}")
                    String embeddingModel,
            @Value("${payroll.router.semantic.embedding-dim:1024}") int embeddingDim) {
        return new SemanticRouteSettings(
                enabled,
                minScore,
                margin,
                index,
                timeoutMs,
                seedTimeoutSeconds,
                esUrl,
                esUsername,
                esPassword,
                embeddingBaseUrl,
                embeddingModel,
                embeddingDim);
    }

    /** L1 语义路由：关闭或初始化失败时返回禁用实例，调用方按 L0 + L2 继续。 */
    @Bean(destroyMethod = "close")
    public SemanticRouteClassifier routeSemanticClassifier(SemanticRouteSettings settings) {
        return SemanticRouteClassifier.start(settings);
    }

    /** 规格 7：薪酬语义桶独立索引；off 档不初始化，避免无谓的嵌入与 ES 连接。 */
    @Bean(destroyMethod = "close")
    public SemanticRouteClassifier payrollRouteSemanticClassifier(
            SemanticRouteSettings settings,
            @Value("${payroll.router.semantic.payroll-mode:off}") String payrollMode,
            @Value("${payroll.router.semantic.payroll-index:payroll-route-intents-payroll}")
                    String payrollIndex) {
        PayrollRouteMode mode = PayrollRouteMode.parse(payrollMode);
        if (!mode.semanticEnabled()) {
            log.info("[薪酬语义] 未启用（payroll.router.semantic.payroll-mode=off）");
            return SemanticRouteClassifier.disabled("薪酬语义未启用");
        }
        SemanticRouteSettings payrollSettings =
                new SemanticRouteSettings(
                        settings.enabled(),
                        settings.minScore(),
                        settings.margin(),
                        payrollIndex,
                        settings.timeoutMs(),
                        settings.seedTimeoutSeconds(),
                        settings.esUrl(),
                        settings.esUsername(),
                        settings.esPassword(),
                        settings.embeddingBaseUrl(),
                        settings.embeddingModel(),
                        settings.embeddingDim());
        return SemanticRouteClassifier.start(
                payrollSettings,
                "/routing/payroll-route-intents.json",
                payrollIndex,
                PayrollRouteBucket.ALL);
    }

    /** 规格 7.3：多实例部署必须共享 Redis；无 Redis 时 cache 档自动退化为每次调用模型。 */
    @Bean
    public PayrollRouteCache payrollRouteCache(
            @Value("${payroll.router.semantic.payroll-cache-ttl-days:7}") int ttlDays,
            ObjectProvider<StringRedisTemplate> redisProvider) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            log.warn("[薪酬语义] 未启用 Redis，决策缓存不可用；cache 档将始终调用模型路由");
            return PayrollRouteCache.disabled();
        }
        return PayrollRouteCache.redis(redis, java.time.Duration.ofDays(Math.max(1, ttlDays)));
    }

    @Bean
    public PayrollRouteAdvisor payrollRouteAdvisor(
            @Value("${payroll.router.semantic.payroll-mode:off}") String payrollMode,
            @Value("${payroll.chat.kb-policy.follow-up-max-chars:20}") int cacheUnsafeMaxChars,
            @Qualifier("payrollRouteSemanticClassifier") SemanticRouteClassifier payrollSemantic,
            PayrollRouteCache payrollRouteCache) {
        PayrollRouteMode mode = PayrollRouteMode.parse(payrollMode);
        log.info(
                "[薪酬语义] 档位={}，索引已就绪={}，缓存={}，不缓存问句上限={}字",
                mode,
                payrollSemantic != null && payrollSemantic.ready(),
                payrollRouteCache == null ? "未配置" : "Redis",
                Math.max(1, cacheUnsafeMaxChars));
        return new PayrollRouteAdvisor(
                mode,
                payrollSemantic,
                payrollRouteCache,
                PayrollAgentFactory.modelId(),
                cacheUnsafeMaxChars);
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
            @Qualifier("routeSemanticClassifier") SemanticRouteClassifier semantic,
            @Qualifier("payrollRouteAdvisor") PayrollRouteAdvisor payrollRouteAdvisor,
            @Value("${payroll.orchestrator:legacy}") String orchestrator,
            @Value("${payroll.routing.enabled:true}") boolean routingEnabled,
            @Value("${payroll.chat.action-confirm.enabled:false}") boolean actionConfirmEnabled,
            @Value("${payroll.chat.action-confirm.ttl-minutes:15}") int actionProposalTtlMinutes,
            @Value("${payroll.chat.action-confirm.always-confirm-tools:}")
                    String alwaysConfirmToolsCsv,
            @Value("${payroll.chat.kb-policy.enabled:false}") boolean kbPolicyEnabled,
            @Value("${payroll.chat.kb-policy.follow-up:true}") boolean kbPolicyFollowUp,
            @Value("${payroll.chat.kb-policy.bound-candidate:false}") boolean kbPolicyBoundCandidate,
            @Value("${payroll.chat.kb-policy.action-guard:true}") boolean kbPolicyActionGuard,
            @Value("${payroll.chat.kb-policy.follow-up-max-chars:20}") int kbPolicyFollowUpMaxChars) {
        Set<String> alwaysConfirmTools = parseTools(alwaysConfirmToolsCsv);
        KbRoutingPolicy.Options kbPolicy =
                new KbRoutingPolicy.Options(
                        kbPolicyEnabled,
                        kbPolicyFollowUp,
                        kbPolicyBoundCandidate,
                        kbPolicyActionGuard,
                        kbPolicyFollowUpMaxChars);
        log.info(
                "[知识库][路由] L0 规则：enabled={}，follow-up={}，bound-candidate={}，action-guard={}，max-chars={}",
                kbPolicyEnabled,
                kbPolicyFollowUp,
                kbPolicyBoundCandidate,
                kbPolicyActionGuard,
                kbPolicyFollowUpMaxChars);
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
                        alwaysConfirmTools,
                        kbPolicy,
                        semantic,
                        payrollRouteAdvisor);
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
