package io.agentscope.examples.payroll.config;

import io.agentscope.examples.payroll.db.H2AdminRepository;
import io.agentscope.examples.payroll.db.H2ActionProposalStore;
import io.agentscope.examples.payroll.db.H2AgentTeamStore;
import io.agentscope.examples.payroll.db.H2ApprovalStore;
import io.agentscope.examples.payroll.db.H2AuditStore;
import io.agentscope.examples.payroll.db.H2ChatFeedbackStore;
import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.db.H2ChatTurnStore;
import io.agentscope.examples.payroll.db.H2PayrollRepository;
import io.agentscope.examples.payroll.db.H2PayrollRunStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.PayrollPolicy;
import io.agentscope.examples.payroll.memory.AnswerCache;
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
import io.agentscope.examples.payroll.service.LoginRateLimiter;
import io.agentscope.examples.payroll.service.PayrollChatFactory;
import io.agentscope.examples.payroll.service.PayrollEngine;
import io.agentscope.examples.payroll.service.PayrollRunService;
import io.agentscope.examples.payroll.service.RedisTurnEventBus;
import io.agentscope.examples.payroll.service.StaticRunRecorder;
import io.agentscope.examples.payroll.service.TeamRunQueryService;
import io.agentscope.examples.payroll.service.TurnEventBus;
import io.agentscope.examples.payroll.service.TurnLeaseService;
import io.agentscope.examples.payroll.skill.NacosAgentRegistry;
import io.agentscope.examples.payroll.skill.NacosFlowRegistry;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry;
import io.agentscope.examples.payroll.team.ActiveTeamCoordinator;
import io.agentscope.examples.payroll.team.ShadowEvaluator;
import io.agentscope.examples.payroll.team.ShadowReportService;
import io.agentscope.examples.payroll.team.TeamAdminService;
import io.agentscope.examples.payroll.team.TeamMode;
import io.agentscope.examples.payroll.team.TeamSettings;
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
import org.springframework.beans.factory.ObjectProvider;
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

    @Bean
    public ChatQuotaService chatQuotaService(
            ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${payroll.chat.max-concurrent-per-user:2}") int maxConcurrent) {
        return new ChatQuotaService(redisProvider.getIfAvailable(), maxConcurrent);
    }

    @Bean
    public H2ChatTurnStore chatTurnStore(PayrollDatabase database) {
        return new H2ChatTurnStore(database);
    }

    /** 轮次租约：30 秒 TTL + 10 秒心跳，节点崩溃后可被其它实例接管。 */
    @Bean(destroyMethod = "close")
    public TurnLeaseService turnLeaseService(
            ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${payroll.chat.turn-lease-seconds:30}") int ttlSeconds,
            @Value("${payroll.chat.turn-heartbeat-seconds:10}") int heartbeatSeconds) {
        return new TurnLeaseService(
                redisProvider.getIfAvailable(), ttlSeconds, heartbeatSeconds);
    }

    /** 轮次事件：有 Redis 时跨节点续流，否则退化为进程内事件。 */
    @Bean
    public TurnEventBus turnEventBus(
            ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${payroll.chat.turn-event-ttl-hours:24}") int ttlHours,
            @Value("${payroll.chat.turn-event-max:10000}") int maxEvents,
            @Value("${payroll.chat.turn-max-minutes:30}") int turnMaxMinutes) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return new InMemoryTurnEventBus();
        }
        return new RedisTurnEventBus(redis, ttlHours, maxEvents, turnMaxMinutes);
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
    public ActionExecutor actionExecutor(ObjectProvider<ActionContributor> contributors) {
        Map<String, DefaultActionExecutor.LocalAction> actions = new LinkedHashMap<>();
        Map<String, String> permissions = new LinkedHashMap<>();
        contributors.orderedStream()
                .forEach(
                        contributor -> {
                            actions.putAll(contributor.localActions());
                            permissions.putAll(contributor.toolPermissions());
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
                    int progressTimeoutSeconds) {
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
                progressTimeoutSeconds);
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

    /** 流式对话线程池：SSE 与 runtime 内部执行都在独立线程中消费模型事件。 */
    @Bean(name = "chatStreamExecutor", destroyMethod = "shutdown")
    public ExecutorService chatStreamExecutor() {
        return Executors.newFixedThreadPool(
                4,
                runnable -> {
                    Thread thread = new Thread(runnable, "payroll-chat-stream");
                    thread.setDaemon(true);
                    return thread;
                });
    }
}
