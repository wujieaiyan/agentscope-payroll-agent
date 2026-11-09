package io.agentscope.examples.payroll.team;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.examples.payroll.agent.PayrollAgentFactory;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 影子模式的轻量模型调用：无工具、单轮、10 秒超时，只接受 JSON。
 *
 * <p>意图与计划各占一次调用，失败不重试；调用方必须提供规则/确定性兜底。
 */
public final class TeamLlmClient {

    private static final Logger log = LoggerFactory.getLogger(TeamLlmClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int PREVIEW_LIMIT = 600;
    private static final String SYSTEM_PROMPT =
            """
            你是薪酬管理台团队编排的结构化决策器。
            只输出一个合法 JSON 对象，不要 Markdown 代码围栏，不要解释，不要调用工具。
            字段值必须来自用户消息中给出的枚举与候选列表，禁止编造 ID。
            """;

    private final HarnessAgent agent;
    private final Object lock = new Object();

    public TeamLlmClient() {
        this(PayrollAgentFactory.createUtilityAgent("PayrollTeamPlanner", SYSTEM_PROMPT, 1));
    }

    TeamLlmClient(HarnessAgent agent) {
        this.agent = agent;
    }

    public static TeamLlmClient disabled() {
        return new TeamLlmClient(null);
    }

    public boolean enabled() {
        return agent != null;
    }

    /**
     * 执行一次 JSON 抽取；模型不可用、超时或返回空内容时返回 null，由调用方兜底。
     *
     * @param instruction 只描述输出 schema 与判断规则
     * @param question 用户原始问题
     * @param sessionKey 用于隔离本次调用的上下文
     */
    public String completeJson(String instruction, String question, String sessionKey) {
        if (!enabled()) {
            return null;
        }
        String prompt =
                instruction
                        + "\n\n【用户问题】\n"
                        + (question == null ? "" : question)
                        + "\n\n只输出 JSON。";
        synchronized (lock) {
            RuntimeContext context =
                    RuntimeContext.builder()
                            .userId("team-shadow")
                            .sessionId("team-shadow:" + safeKey(sessionKey))
                            .build();
            try {
                agent.clearContext(context);
                Msg reply =
                        agent.call(List.of(new UserMessage(prompt)), context).block(TIMEOUT);
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
                    agent.clearContext(context);
                } catch (RuntimeException e) {
                    log.debug("[动态团队] 清理轻量模型上下文失败", e);
                }
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
