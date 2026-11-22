package io.agentscope.examples.payroll.config;

import io.agentscope.examples.payroll.db.H2AdminRepository;
import io.agentscope.examples.payroll.db.H2ActionProposalStore;
import io.agentscope.examples.payroll.db.H2AgentTeamStore;
import io.agentscope.examples.payroll.db.H2ApprovalStore;
import io.agentscope.examples.payroll.db.H2AuditStore;
import io.agentscope.examples.payroll.db.H2ChatFeedbackStore;
import io.agentscope.examples.payroll.db.ChatAttachmentStore;
import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.db.H2ChatTurnStore;
import io.agentscope.examples.payroll.db.H2LlmUsageStore;
import io.agentscope.examples.payroll.db.H2UserProfileStore;
import io.agentscope.examples.payroll.db.H2PayrollRepository;
import io.agentscope.examples.payroll.db.H2PayrollRunStore;
import io.agentscope.examples.payroll.db.ModelCredentialStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.PayrollPolicy;
import io.agentscope.examples.payroll.health.PayrollReadinessHealthIndicator;
import io.agentscope.examples.payroll.health.RedisHealthProbe;
import io.agentscope.examples.payroll.memory.AnswerCache;
import io.agentscope.examples.payroll.model.ModelCredentialCipher;
import io.agentscope.examples.payroll.agent.ConfirmationTools;
import io.agentscope.examples.payroll.agent.QuestionTools;
import io.agentscope.examples.payroll.model.ModelOutboundPolicy;
import io.agentscope.examples.payroll.profile.ProfileMemory;
import io.agentscope.examples.payroll.profile.UserProfileAdminService;
import io.agentscope.examples.payroll.profile.UserProfileMaintenance;
import io.agentscope.examples.payroll.profile.UserProfileStore;
import io.agentscope.examples.payroll.service.ModelCredentialService;
import io.agentscope.examples.payroll.skill.ModelConnectionRegistry;
import io.agentscope.examples.payroll.skill.NacosSkillSettings;
import io.agentscope.examples.payroll.memory.SemanticCacheService;
import io.agentscope.examples.payroll.memory.SemanticCacheSettings;
import io.agentscope.examples.payroll.memory.VectorMemoryService;
import io.agentscope.examples.payroll.memory.VectorMemorySettings;
import io.agentscope.examples.payroll.service.ActionContributor;
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
import io.agentscope.examples.payroll.service.InMemoryTurnEventBus;
import io.agentscope.examples.payroll.service.LlmUsageService;
import io.agentscope.examples.payroll.service.LoginRateLimiter;
import io.agentscope.examples.payroll.service.PayrollChatFactory;
import io.agentscope.examples.payroll.service.PayrollEngine;
import io.agentscope.examples.payroll.service.PayrollRunService;
import io.agentscope.examples.payroll.service.RedisTurnEventBus;
import io.agentscope.examples.payroll.service.StaticRunRecorder;
import io.agentscope.examples.payroll.service.TeamRunQueryService;
import io.agentscope.examples.payroll.service.TurnEventBus;
import io.agentscope.examples.payroll.service.TurnLeaseService;
import io.agentscope.examples.payroll.service.TurnMetrics;
import io.agentscope.examples.payroll.skill.NacosAgentRegistry;
import io.agentscope.examples.payroll.skill.NacosFlowRegistry;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry;
import io.agentscope.examples.payroll.skill.SkillFileSettings;
import io.agentscope.examples.payroll.skill.SkillImportService;
import io.agentscope.examples.payroll.skill.SkillImportSettings;
import io.agentscope.examples.payroll.team.ActiveTeamCoordinator;
import io.agentscope.examples.payroll.team.ShadowEvaluator;
import io.agentscope.examples.payroll.team.ShadowReportService;
import io.agentscope.examples.payroll.team.TeamAdminService;
import io.agentscope.examples.payroll.team.TeamMode;
import io.agentscope.examples.payroll.team.TeamSettings;
import io.agentscope.examples.payroll.usage.LlmUsageStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 后端与智能体运行期共享的基础设施装配。
 *
 * <p>这里不出现任何智能体实现类：对话实例由 {@link PayrollChatFactory} 注入，
 * 影子/ACTIVE 团队由 {@link ShadowEvaluator}、{@link ActiveTeamCoordinator} 注入；
 * Web 节点没有这些 Bean 时自动退化为“只落轮次、不执行模型”。
 */
@Configuration(proxyBeanMethods = false)
public class PayrollCoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PayrollCoreConfiguration.class);

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

    /** 部署模式：single 保留本地降级，cluster 对 Redis 能力快速失败。 */
    @Bean
    public DeploymentMode deploymentMode(@Value("${payroll.deployment.mode:single}") String mode) {
        DeploymentMode parsed = DeploymentMode.parse(mode);
        TurnMetrics.configureDeploymentMode(parsed.value());
        log.info(
                "[部署] 模式={}：{}",
                parsed.value(),
                parsed.cluster() ? "租约/事件/配额不回退进程内实现" : "Redis 故障时允许本地降级");
        return parsed;
    }

    /** Redis 健康探针：30s 写探针 + 持久化判定；cluster 下参与 readiness 硬判定。 */
    @Bean(destroyMethod = "close")
    public RedisHealthProbe redisHealthProbe(
            ObjectProvider<StringRedisTemplate> redisProvider,
            DeploymentMode deploymentMode,
            MeterRegistry registry) {
        return new RedisHealthProbe(redisProvider.getIfAvailable(), deploymentMode, registry);
    }

    /** readiness 只查 DB + Redis；ES/Nacos 留在各自详情里，不参与摘流。 */
    @Bean(name = "payrollReadiness")
    public PayrollReadinessHealthIndicator payrollReadiness(
            PayrollDatabase database,
            RedisHealthProbe redisHealthProbe,
            DeploymentMode deploymentMode,
            @Value("${payroll.health.readiness-deps:db,redis}") String readinessDeps,
            MeterRegistry registry) {
        return new PayrollReadinessHealthIndicator(
                database, redisHealthProbe, deploymentMode, readinessDeps, registry);
    }

    @Bean
    public ChatQuotaService chatQuotaService(
            ObjectProvider<StringRedisTemplate> redisProvider,
            DeploymentMode deploymentMode,
            @Value("${payroll.chat.max-concurrent-per-user:2}") int maxConcurrent,
            @Value("${payroll.chat.quota-ttl-minutes:35}") int quotaTtlMinutes,
            @Value("${payroll.chat.turn-max-minutes:30}") int turnMaxMinutes,
            @Value("${payroll.chat.quota-fail-mode:}") String quotaFailMode,
            @Value("${payroll.node-id:local}") String nodeId) {
        if (quotaTtlMinutes <= turnMaxMinutes) {
            log.warn(
                    "[对话] 配额 TTL {} 分钟不大于轮次上限 {} 分钟，已按轮次上限 +1 分钟生效",
                    quotaTtlMinutes,
                    turnMaxMinutes);
        }
        int ttlMinutes = Math.max(quotaTtlMinutes, turnMaxMinutes + 1);
        boolean failFast;
        if (quotaFailMode == null || quotaFailMode.isBlank()) {
            failFast = deploymentMode.cluster();
        } else if ("deny".equalsIgnoreCase(quotaFailMode.trim())) {
            failFast = true;
        } else if ("local".equalsIgnoreCase(quotaFailMode.trim())) {
            failFast = false;
            if (deploymentMode.cluster()) {
                log.warn(
                        "[对话] cluster 模式忽略 quota-fail-mode=local：Redis 故障时仍拒绝新请求");
                failFast = true;
            }
        } else {
            log.warn("[对话] 未知 quota-fail-mode={}，按部署模式默认处理", quotaFailMode);
            failFast = deploymentMode.cluster();
        }
        return new ChatQuotaService(
                redisProvider.getIfAvailable(),
                maxConcurrent,
                Duration.ofMinutes(ttlMinutes),
                deploymentMode,
                nodeId,
                failFast);
    }

    @Bean
    public H2ChatTurnStore chatTurnStore(PayrollDatabase database) {
        return new H2ChatTurnStore(database);
    }

    /** 轮次租约：30 秒 TTL + 10 秒心跳，节点崩溃后可被其它实例接管。 */
    @Bean(destroyMethod = "close")
    public TurnLeaseService turnLeaseService(
            ObjectProvider<StringRedisTemplate> redisProvider,
            DeploymentMode deploymentMode,
            @Value("${payroll.chat.turn-lease-seconds:30}") int ttlSeconds,
            @Value("${payroll.chat.turn-heartbeat-seconds:10}") int heartbeatSeconds) {
        return new TurnLeaseService(
                redisProvider.getIfAvailable(), ttlSeconds, heartbeatSeconds, deploymentMode);
    }

    /** 轮次事件：有 Redis 时跨节点续流，否则退化为进程内事件。 */
    @Bean
    public TurnEventBus turnEventBus(
            ObjectProvider<StringRedisTemplate> redisProvider,
            DeploymentMode deploymentMode,
            @Value("${payroll.chat.turn-event-ttl-hours:24}") int ttlHours,
            @Value("${payroll.chat.turn-event-max:10000}") int maxEvents,
            @Value("${payroll.chat.turn-max-minutes:30}") int turnMaxMinutes) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            if (deploymentMode.cluster()) {
                throw new IllegalStateException(
                        "cluster 模式必须配置 Redis：轮次事件总线不再回退进程内实现");
            }
            return new InMemoryTurnEventBus();
        }
        return new RedisTurnEventBus(
                redis, ttlHours, maxEvents, turnMaxMinutes, deploymentMode);
    }

    /**
     * 语义缓存：默认关闭；连接参数复用向量记忆的 ES / 嵌入服务，索引与阈值独立。
     * 有 Redis 时精确缓存跨节点共享，否则退化为进程内缓存。
     */
    @Bean(destroyMethod = "close")
    public AnswerCache answerCache(
            @Value("${payroll.semantic-cache.enabled:false}") boolean enabled,
            @Value("${payroll.memory.es-url:http://localhost:9200}") String esUrl,
            @Value("${payroll.memory.es-username:}") String esUsername,
            @Value("${payroll.memory.es-password:}") String esPassword,
            @Value("${payroll.memory.embedding-base-url:http://localhost:11434}")
                    String embeddingBaseUrl,
            @Value("${payroll.memory.embedding-model:nomic-embed-text}") String embeddingModel,
            @Value("${payroll.memory.embedding-dim:768}") int embeddingDim,
            @Value("${payroll.memory.embed-timeout:3s}") Duration embedTimeout,
            @Value("${payroll.semantic-cache.index:payroll-semantic-cache-v1}") String index,
            @Value("${payroll.semantic-cache.threshold:0.95}") double threshold,
            @Value("${payroll.semantic-cache.ttl-minutes:30}") int ttlMinutes,
            @Value("${payroll.semantic-cache.max-entries:10000}") int maxEntries,
            ObjectProvider<StringRedisTemplate> redisProvider) {
        return SemanticCacheService.start(
                new SemanticCacheSettings(
                        enabled,
                        esUrl,
                        esUsername,
                        esPassword,
                        index,
                        embeddingBaseUrl,
                        embeddingModel,
                        embeddingDim,
                        embedTimeout,
                        threshold,
                        ttlMinutes,
                        maxEntries),
                redisProvider.getIfAvailable());
    }

    /** 启动时清理过期的语义缓存文档，避免索引无限增长。 */
    @Bean
    public ApplicationRunner semanticCacheCleanupRunner(AnswerCache answerCache) {
        return args -> {
            if (answerCache instanceof SemanticCacheService service) {
                try {
                    service.cleanupExpired();
                } catch (RuntimeException e) {
                    log.warn("[语义缓存] 启动清理失败：{}", e.getMessage());
                }
            }
        };
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
    public ApprovalService approvalService(H2ApprovalStore store, H2PayrollRunStore runStore) {
        return new ApprovalService(store, runStore);
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

    /** 技能项目导入：Git 浅克隆或 ZIP 解压后解析并整体发布到 Nacos。 */
    @Bean
    public SkillImportService skillImportService(NacosSkillRegistry skillRegistry) {
        return new SkillImportService(
                skillRegistry,
                SkillFileSettings.fromEnvironment(),
                SkillImportSettings.fromEnvironment());
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
            @Value("${payroll.memory.cross-session:false}") boolean crossSession,
            @Value("${payroll.memory.recall-budget-ms:600}") int recallBudgetMs,
            @Value("${payroll.memory.embed-breaker.failures:3}") int breakerFailures,
            @Value("${payroll.memory.embed-breaker.window-seconds:60}") long breakerWindowSeconds,
            @Value("${payroll.memory.embed-breaker.open-seconds:60}") long breakerOpenSeconds,
            @Qualifier("memoryRecallExecutor") Executor recallExecutor) {
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
                        crossSession,
                        recallBudgetMs,
                        breakerFailures,
                        breakerWindowSeconds,
                        breakerOpenSeconds),
                recallExecutor);
    }

    /**
     * 召回线程池：与索引线程池隔离，避免慢嵌入拖住写入；队列满直接拒绝，本轮不注入历史。
     */
    @Bean(name = "memoryRecallExecutor", destroyMethod = "shutdown")
    public ExecutorService memoryRecallExecutor(
            @Value("${payroll.memory.recall-threads:2}") int threads,
            @Value("${payroll.memory.recall-queue-capacity:16}") int queueCapacity) {
        int poolSize = Math.max(1, threads);
        int capacity = Math.max(1, queueCapacity);
        return new ThreadPoolExecutor(
                poolSize,
                poolSize,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                payrollThreadFactory("payroll-memory-recall"),
                new ThreadPoolExecutor.AbortPolicy());
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
    public ChatAttachmentStore chatAttachmentStore(PayrollDatabase database) {
        return new ChatAttachmentStore(database);
    }

    @Bean
    public UserProfileStore userProfileStore(PayrollDatabase database) {
        return new H2UserProfileStore(database);
    }

    /** 注入读路径的静态装配；开关关闭时读取一律返回空块，自助接口另行按开关拒绝。 */
    @Bean
    public ApplicationRunner profileMemoryConfigurer(
            UserProfileStore userProfileStore,
            @Value("${payroll.memory.profile.enabled:false}") boolean enabled,
            @Value("${payroll.memory.profile.inject-max-items:10}") int maxItems,
            @Value("${payroll.memory.profile.inject-max-chars:600}") int maxChars) {
        return args -> {
            ProfileMemory.configure(userProfileStore, enabled, maxItems, maxChars);
            log.info(
                    "[工作偏好] 注入读路径：{}，上限 {} 条 / {} 字",
                    enabled ? "已启用" : "已关闭（payroll.memory.profile.enabled=false）",
                    maxItems,
                    maxChars);
        };
    }

    /** 抽取凭证随 chat_turns 同周期清理；只有 web/all 节点执行，runtime 不重复扫库。 */
    @Bean(destroyMethod = "close")
    public UserProfileMaintenance userProfileMaintenance(
            UserProfileStore userProfileStore,
            @Value("${payroll.memory.profile.enabled:false}") boolean enabled,
            @Value("${payroll.chat.turn-retention-days:90}") int retentionDays,
            @Value("${payroll.chat.turn-cleanup-interval-minutes:360}") int intervalMinutes,
            @Value("${payroll.node-role:all}") String nodeRole) {
        return new UserProfileMaintenance(
                userProfileStore,
                retentionDays,
                intervalMinutes,
                enabled && !"runtime".equals(nodeRole));
    }

    /** 自助接口服务：写操作按用户失效注入缓存，审计只落值哈希。 */
    @Bean
    public UserProfileAdminService userProfileAdminService(
            UserProfileStore userProfileStore,
            AuditService auditService,
            @Value("${payroll.memory.profile.enabled:false}") boolean enabled) {
        return new UserProfileAdminService(userProfileStore, auditService, enabled);
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
    public ActionExecutor actionExecutor(ObjectProvider<ActionContributor> contributors) {
        Map<String, DefaultActionExecutor.LocalAction> actions = new LinkedHashMap<>();
        Map<String, String> permissions = new LinkedHashMap<>();
        contributors.orderedStream()
                .forEach(
                        contributor -> {
                            actions.putAll(contributor.localActions());
                            permissions.putAll(contributor.toolPermissions());
                        });
        // 请求确认没有服务端副作用：确认后把 followup 原样交回前端，由前端作为新一轮输入发送。
        actions.put(
                ConfirmationTools.REQUEST_CONFIRMATION,
                (params, userId) -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "CONFIRMED");
                    result.put("followup", params.getOrDefault("followup", ""));
                    return result;
                });
        // 逐项选择同样没有副作用：把卡片问题与用户答案拼成下一轮用户输入。
        actions.put(
                QuestionTools.REQUEST_INPUT,
                (params, userId) -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "CONFIRMED");
                    result.put("answers", params.getOrDefault("answers", Map.of()));
                    result.put("followup", QuestionTools.followupText(params));
                    return result;
                });
        return new DefaultActionExecutor(actions, permissions);
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

    /**
     * 会话管理：chatFactory、影子与 ACTIVE 团队均由运行期提供；
     * Web 节点缺少这些 Bean 时只负责会话、轮次与消息，不执行模型。
     */
    @Bean(destroyMethod = "close")
    public ChatSessionManager chatSessionManager(
            H2ChatStore chatStore,
            H2ChatTurnStore chatTurnStore,
            ObjectProvider<PayrollChatFactory> chatFactoryProvider,
            VectorMemoryService memory,
            H2AgentTeamStore agentTeamStore,
            TeamSettings teamSettings,
            ObjectProvider<ShadowEvaluator> shadowProvider,
            ObjectProvider<ActiveTeamCoordinator> activeTeamProvider,
            TurnLeaseService turnLeaseService,
            TurnEventBus turnEventBus,
            AuditService auditService,
            @Value("${payroll.chat.cache-max-sessions:200}") int cacheMaxSessions,
            @Value("${payroll.chat.cache-idle-minutes:60}") int cacheIdleMinutes,
            @Value("${payroll.node-id:local}") String nodeId,
            @Value("${payroll.chat.turn-heartbeat-seconds:10}") int heartbeatSeconds,
            @Value("${payroll.chat.turn-max-minutes:30}") int turnMaxMinutes,
            @Value("${payroll.chat.turn-progress-timeout-seconds:300}")
                    int progressTimeoutSeconds,
            @Value("${payroll.chat.turn-retention-days:90}") int turnRetentionDays,
            @Value("${payroll.chat.turn-cleanup-interval-minutes:360}")
                    int turnCleanupIntervalMinutes,
            @Value("${payroll.node-role:all}") String nodeRole) {
        return new ChatSessionManager(
                chatStore,
                chatFactoryProvider.getIfAvailable(),
                memory,
                new StaticRunRecorder(agentTeamStore, teamSettings),
                shadowProvider.getIfAvailable(),
                activeTeamProvider.getIfAvailable(),
                cacheMaxSessions,
                cacheIdleMinutes,
                auditService,
                chatTurnStore,
                turnLeaseService,
                turnEventBus,
                nodeId,
                heartbeatSeconds,
                turnMaxMinutes,
                progressTimeoutSeconds,
                // 清理只由 Web/all 节点执行，runtime 节点不重复扫库。
                "runtime".equals(nodeRole) ? 0 : turnRetentionDays,
                turnCleanupIntervalMinutes);
    }

    @Bean
    public AuthService authService(
            H2AdminRepository adminRepository,
            PasswordEncoder passwordEncoder,
            @Value("${payroll.auth.enabled:true}") boolean authEnabled) {
        return new AuthService(adminRepository, passwordEncoder, authEnabled);
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

    /** 用量事实表：runtime 写入、Web 读取；同一份存储两端共享。 */
    @Bean
    public LlmUsageStore llmUsageStore(PayrollDatabase database) {
        return new H2LlmUsageStore(database);
    }

    @Bean
    public LlmUsageService llmUsageService(LlmUsageStore llmUsageStore) {
        return new LlmUsageService(llmUsageStore);
    }

    /** 模型连接整份存 Nacos；Nacos 未启用时只提供环境变量派生的只读连接。 */
    @Bean
    public ModelConnectionRegistry modelConnectionRegistry() {
        return ModelConnectionRegistry.shared();
    }

    @Bean
    public ModelCredentialCipher modelCredentialCipher() {
        ModelCredentialCipher cipher = ModelCredentialCipher.fromEnvironment();
        if (cipher.available()) {
            log.info("[Model] 页面密钥加密已启用（PAYROLL_SECRET_KEY 已配置）");
        } else {
            log.warn("[Model] 页面密钥加密不可用：{}，模型配置页只能使用环境变量密钥", cipher.unavailableReason());
        }
        return cipher;
    }

    @Bean
    public ModelCredentialStore modelCredentialStore(PayrollDatabase database) {
        return new ModelCredentialStore(database);
    }

    @Bean
    public ModelCredentialService modelCredentialService(
            ModelCredentialStore store, ModelCredentialCipher cipher) {
        return new ModelCredentialService(store, cipher);
    }

    /** 出网拒绝同时写审计：原始地址只保留主机，避免把查询串/凭证类片段带进审计库。 */
    @Bean
    public ModelOutboundPolicy modelOutboundPolicy(AuditService auditService) {
        return ModelOutboundPolicy.fromEnvironment()
                .withDenialSink(
                        (uri, reason) ->
                                auditService.recordFailure(
                                        "model.outbound.denied",
                                        "model_connection",
                                        uri.getHost() == null ? "" : uri.getHost(),
                                        403,
                                        reason));
    }

    /** 启动时按「凭证存在但连接不存在」清理孤儿并告警；Nacos 未启用时跳过，避免误删。 */
    @Bean
    public ApplicationRunner modelCredentialCleanupRunner(
            ModelConnectionRegistry registry, ModelCredentialService credentials) {
        return args -> {
            if (!registry.writable()) {
                return;
            }
            try {
                Set<String> valid = new LinkedHashSet<>();
                registry.connections().stream()
                        .filter(connection -> !connection.envDerived())
                        .map(connection -> connection.id())
                        .forEach(valid::add);
                int removed = credentials.cleanupOrphans(valid);
                if (removed > 0) {
                    log.info("[Model] 启动时清理孤儿模型凭证 {} 条", removed);
                }
            } catch (RuntimeException e) {
                log.warn("[Model] 清理孤儿模型凭证失败：{}", e.getMessage());
            }
        };
    }

    /** 启动时清理超过保留期的审计日志，避免数据库无限增长。 */
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

    /** 启动时清理孤儿与失联轮次；运行期由会话管理器的 60s 扫描继续兜底。 */
    @Bean
    public ApplicationRunner turnRecoveryRunner(
            ChatSessionManager chatSessionManager,
            @Value("${payroll.chat.turn-heartbeat-seconds:10}") int heartbeatSeconds) {
        return args -> {
            try {
                int expired =
                        chatSessionManager.expireStaleTurns(
                                Duration.ofSeconds(Math.max(30L, heartbeatSeconds * 3L)),
                                "节点重启后清理失联轮次");
                if (expired > 0) {
                    log.info("[轮次] 启动时标记失联轮次 {} 条", expired);
                }
            } catch (RuntimeException e) {
                log.warn("[轮次] 清理失联轮次失败：{}", e.getMessage());
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

    /**
     * 流式对话线程池：SSE 与 runtime 内部执行都在独立线程中消费模型事件。
     *
     * <p>有界队列 + {@link ThreadPoolExecutor.AbortPolicy}：队列满时快速失败并返回 429，
     * 不用 CallerRunsPolicy，避免 SSE 请求线程被占住拖垮 Tomcat。
     */
    @Bean(name = "chatStreamExecutor", destroyMethod = "shutdown")
    public ExecutorService chatStreamExecutor(
            @Value("${payroll.chat.stream-threads:4}") int threads,
            @Value("${payroll.chat.stream-queue-capacity:32}") int queueCapacity) {
        int poolSize = Math.max(1, threads);
        int capacity = Math.max(1, queueCapacity);
        return new ThreadPoolExecutor(
                poolSize,
                poolSize,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                payrollThreadFactory("payroll-chat-stream"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadFactory payrollThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
