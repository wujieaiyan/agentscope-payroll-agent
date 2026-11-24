package io.agentscope.examples.payroll.team;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.examples.payroll.agent.PromptLibrary;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 影子模式的轻量模型调用：无工具、单轮、10 秒超时，只接受 JSON。
 *
 * <p>意图与计划各占一次调用，失败不重试；调用方必须提供规则/确定性兜底。
 *
 * <p>页面切换轻量面连接后，这里按连接版本重建计划器，无需重启运行期。
 */
public final class TeamLlmClient {

    private static final Logger log = LoggerFactory.getLogger(TeamLlmClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int PREVIEW_LIMIT = 600;

    /** 轻量调用并发闸门：拿不到许可即规则兜底，避免模型慢时把计划线程串行堆死。 */
    private static final Duration PERMIT_WAIT = Duration.ofSeconds(2);

    /** 默认计划器标识与系统提示；由编排层的配置类据此构建模型，平台包不直接依赖工厂。 */
    public static final String AGENT_NAME = "PayrollTeamPlanner";

    public static final String SYSTEM_PROMPT = PromptLibrary.get("team-planner-system");

    private volatile HarnessAgent agent;
    private volatile String agentVersion;
    private final Supplier<String> versionSupplier;
    private final Supplier<HarnessAgent> agentSupplier;
    private final Semaphore permits;
    private final Object rebuildLock = new Object();

    public TeamLlmClient() {
        this(null, null, 8);
    }

    /**
     * 版本与构建器为空表示固定模型（测试或禁用场景），不参与热切换。
     *
     * <p>不在此处构建：Spring 的模型注册表与凭证服务由启动后的 {@code ApplicationRunner}
     * 注入静态工厂，Bean 构造期解析会拿不到页面密钥。装配在编排层完成，保持平台包不依赖
     * 薪酬业务工厂（ArchUnit R6）。
     */
    public TeamLlmClient(
            Supplier<String> versionSupplier, Supplier<HarnessAgent> agentSupplier, int maxConcurrent) {
        this.versionSupplier = versionSupplier;
        this.agentSupplier = agentSupplier;
        this.permits = new Semaphore(Math.max(1, maxConcurrent));
    }

    TeamLlmClient(Supplier<String> versionSupplier, Supplier<HarnessAgent> agentSupplier) {
        this(versionSupplier, agentSupplier, 8);
    }

    TeamLlmClient(HarnessAgent agent) {
        this(agent, 8);
    }

    /** 固定模型 + 指定并发上限，供并发回归测试使用。 */
    TeamLlmClient(HarnessAgent agent, int maxConcurrent) {
        this(null, null, maxConcurrent);
        this.agent = agent;
    }

    public static TeamLlmClient disabled() {
        return new TeamLlmClient((HarnessAgent) null);
    }

    public boolean enabled() {
        return (versionSupplier != null && agentSupplier != null) || agent != null;
    }

    /**
     * 执行一次 JSON 抽取；模型不可用、超时或返回空内容时返回 null，由调用方兜底。
     *
     * @param instruction 只描述输出 schema 与判断规则
     * @param question 用户原始问题
     * @param sessionKey 用于隔离本次调用的上下文
     */
    public String completeJson(String instruction, String question, String sessionKey) {
        return completeJson(instruction, question, sessionKey, TIMEOUT);
    }

    /** 指定超时的 JSON 抽取；路由等调用方可以按配置收紧等待时间。 */
    public String completeJson(
            String instruction, String question, String sessionKey, Duration timeout) {
        if (!enabled()) {
            return null;
        }
        Duration wait =
                timeout == null || timeout.isZero() || timeout.isNegative() ? TIMEOUT : timeout;
        String prompt =
                instruction
                        + "\n\n【用户问题】\n"
                        + (question == null ? "" : question)
                        + "\n\n只输出 JSON。";
        if (!acquirePermit(sessionKey)) {
            return null;
        }
        try {
            HarnessAgent active = activeAgent();
            if (active == null) {
                return null;
            }
            RuntimeContext context =
                    RuntimeContext.builder()
                            .userId("team-shadow")
                            .sessionId("team-shadow:" + safeKey(sessionKey))
                            .build();
            try {
                active.clearContext(context);
                Msg reply =
                        active.call(List.of(new UserMessage(prompt)), context).block(wait);
                if (reply == null) {
                    log.warn("[动态团队] 轻量模型返回空结果：session={}", sessionKey);
                    return null;
                }
                String text = reply.getTextContent();
                String json = extractJson(text);
                if (json == null) {
                    log.warn(
                            "[动态团队] 轻量模型未返回 JSON：session={}，输出={}",
                            sessionKey,
                            preview(text));
                    return null;
                }
                log.debug(
                        "[动态团队] 轻量模型输出：session={}，json={}", sessionKey, preview(json));
                return json;
            } catch (RuntimeException e) {
                log.warn(
                        "[动态团队] 轻量模型调用失败，使用规则/确定性兜底：session={}，原因={}",
                        sessionKey,
                        rootMessage(e));
                return null;
            } finally {
                try {
                    active.clearContext(context);
                } catch (RuntimeException e) {
                    log.debug("[动态团队] 清理轻量模型上下文失败", e);
                }
            }
        } finally {
            permits.release();
        }
    }

    /** 并发闸门：超出等待时间拿不到许可即视为本次轻量调用失败，交回规则兜底。 */
    private boolean acquirePermit(String sessionKey) {
        try {
            if (permits.tryAcquire(PERMIT_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[动态团队] 轻量模型许可等待被中断，使用规则兜底：session={}", sessionKey);
            return false;
        }
        log.warn("[动态团队] 轻量模型并发已满，本轮跳过模型调用：session={}", sessionKey);
        return false;
    }

    /** 轻量面连接版本变化时重建计划器；重建失败沿用旧实例，不中断本轮兜底。 */
    private HarnessAgent activeAgent() {
        if (versionSupplier == null || agentSupplier == null) {
            return agent;
        }
        String version;
        try {
            version = versionSupplier.get();
        } catch (RuntimeException e) {
            log.warn("[动态团队] 读取轻量模型版本失败，沿用当前模型：{}", rootMessage(e));
            return agent;
        }
        if (version == null) {
            return agent;
        }
        HarnessAgent current = agent;
        String currentVersion = agentVersion;
        if (current != null && version.equals(currentVersion)) {
            return current;
        }
        synchronized (rebuildLock) {
            current = agent;
            if (current != null && version.equals(agentVersion)) {
                return current;
            }
            try {
                HarnessAgent rebuilt = agentSupplier.get();
                boolean first = agentVersion == null;
                agent = rebuilt;
                agentVersion = version;
                if (first) {
                    log.info("[动态团队] 轻量面模型已建立：版本={}", version);
                } else {
                    log.info("[动态团队] 轻量面模型已随配置切换：版本={}", version);
                }
                return rebuilt;
            } catch (RuntimeException e) {
                log.warn("[动态团队] 轻量面模型重建失败，沿用当前模型：{}", rootMessage(e));
                return current;
            }
        }
    }

    /** 从可能带代码围栏或前后解释的文本中截取第一个 JSON 对象。 */
    static String extractJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return trimmed.substring(start, end + 1);
    }

    private static String safeKey(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^A-Za-z0-9._:-]", "_");
    }

    private static String preview(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= PREVIEW_LIMIT
                ? trimmed
                : trimmed.substring(0, PREVIEW_LIMIT) + "…";
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }
}
