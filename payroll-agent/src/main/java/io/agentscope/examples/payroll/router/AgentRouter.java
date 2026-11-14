package io.agentscope.examples.payroll.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.examples.payroll.agent.PayrollAgentFactory;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.team.TeamLlmClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 纯轻量模型路由：把用户问题分给单个自由智能体、动态团队或内置流水线。
 *
 * <p>不使用规则优先：模型是唯一决策者；模型不可用、输出非法或置信度低于阈值时返回
 * {@link RouteDecision#fallback(String)}，由调用方沿用既有静态链路。调用复用
 * {@link TeamLlmClient}（无工具、单轮、可配置超时），每次只输出一个 JSON 对象。
 */
public final class AgentRouter {

    private static final Logger log = LoggerFactory.getLogger(AgentRouter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int REASON_LIMIT = 200;
    private static final int DESCRIPTION_LIMIT = 120;

    private static final String INSTRUCTION =
            """
            你是薪酬管理台的智能体路由决策器。根据用户问题在三个执行目标中选择一个：
            - "agent"：单个专家智能体直接作答，必须给出候选列表中的 agent_id；
            - "team"：由多个智能体动态组队完成，仅当“团队可用：是”时可选；
            - "pipeline"：内置三段流水线（计算 → 验证 → 核算），始终可选，作为默认。

            只输出一个合法 JSON 对象，不要 Markdown 代码围栏，不要解释：
            {"target":"agent|team|pipeline","agent_id":"候选 id 或空串","reason":"一句话中文理由","confidence":0.0}

            判断规则：
            1. 工资计算、批量核算、工资条明细等需要严格核算的问题，优先 "pipeline"，需要多角色协作且团队可用时选 "team"；
            2. 单一专家即可胜任的制度问答、政策解释、数据查询，选 "agent" 并给出最匹配的 agent_id；
            3. 知识库类问题（建库、解析/分块/检索配置、目录与成员权限、入库任务）不要选 "pipeline"；
               其中建库、保存配置、加/移成员、移动目录、重建索引等写操作只能选 "agent"，
               并优先 tags 含 kb 或 kb-config 的候选智能体；只读问答同样优先该智能体；
            4. 无法确定或候选都不匹配时选 "pipeline"，并给出较低的 confidence。
            """;

    /** 模型调用抽象，便于单测注入固定输出。 */
    @FunctionalInterface
    public interface RouteModel {
        String complete(String instruction, String question, String sessionKey);

        static RouteModel disabled() {
            return (instruction, question, sessionKey) -> null;
        }
    }

    private final RouteModel model;
    private final boolean enabled;
    private final double minConfidence;

    public AgentRouter(
            TeamLlmClient llm, boolean enabled, double minConfidence, Duration timeout) {
        this(
                llm == null
                        ? RouteModel.disabled()
                        : (instruction, question, sessionKey) ->
                                llm.completeJson(instruction, question, sessionKey, timeout),
                enabled,
                minConfidence);
    }

    AgentRouter(RouteModel model, boolean enabled, double minConfidence) {
        this.model = model == null ? RouteModel.disabled() : model;
        this.enabled = enabled;
        this.minConfidence = Math.max(0D, Math.min(1D, minConfidence));
    }

    public static AgentRouter disabled() {
        return new AgentRouter(RouteModel.disabled(), false, 0.55D);
    }

    public boolean enabled() {
        return enabled;
    }

    /** 可路由的单智能体：在线且不是内置流水线（计算技能）的智能体。 */
    public static List<AgentDefinition> candidates(List<AgentDefinition> catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return List.of();
        }
        return catalog.stream()
                .filter(agent -> AgentDefinition.STATUS_ONLINE.equals(agent.status()))
                .filter(agent -> !PayrollAgentFactory.isPipeline(agent))
                .toList();
    }

    /**
     * 决策一次路由；未启用时返回 null，表示调用方不改变既有链路。
     */
    public RouteDecision decide(
            String question,
            List<AgentDefinition> candidates,
            boolean teamAvailable,
            String sessionKey) {
        if (!enabled) {
            return null;
        }
        List<AgentDefinition> agents = candidates == null ? List.of() : List.copyOf(candidates);
        long startedAt = System.currentTimeMillis();
        String raw;
        try {
            raw = model.complete(instruction(agents, teamAvailable), question, sessionKey);
        } catch (RuntimeException e) {
            return logFallback("轻量模型调用失败：" + rootMessage(e), agents, teamAvailable, startedAt);
        }
        if (raw == null || raw.isBlank()) {
            return logFallback("轻量模型不可用或超时", agents, teamAvailable, startedAt);
        }
        RouteDecision decision = parse(raw, agents, teamAvailable);
        long elapsed = System.currentTimeMillis() - startedAt;
        if (decision.routed()) {
            log.info(
                    "[智能体路由] 决策：target={}，agent={}，置信度={}，候选={}，团队可用={}，耗时={}ms，理由={}",
                    decision.target(),
                    decision.agent() == null ? "" : decision.agent().id(),
                    String.format(Locale.ROOT, "%.2f", decision.confidence()),
                    agents.size(),
                    teamAvailable,
                    elapsed,
                    decision.reason());
        } else {
            log.info(
                    "[智能体路由] 未生效：候选={}，团队可用={}，耗时={}ms，原因={}",
                    agents.size(),
                    teamAvailable,
                    elapsed,
                    decision.failureReason());
        }
        return decision;
    }

    /** 解析并校验模型输出；所有异常都折成回退决策。 */
    RouteDecision parse(String raw, List<AgentDefinition> agents, boolean teamAvailable) {
        JsonNode node;
        try {
            node = JSON.readTree(raw);
        } catch (Exception e) {
            return RouteDecision.fallback("路由输出不是合法 JSON");
        }
        if (node == null || !node.isObject()) {
            return RouteDecision.fallback("路由输出不是 JSON 对象");
        }
        String target = text(node, "target").toLowerCase(Locale.ROOT);
        String agentId = text(node, "agent_id");
        String reason = limit(text(node, "reason"), REASON_LIMIT);
        double confidence = node.path("confidence").asDouble(0D);
        if (confidence > 1D) {
            confidence = confidence > 100D ? confidence / 100D : confidence;
        }
        confidence = Math.max(0D, Math.min(1D, confidence));
        if (confidence < minConfidence) {
            return RouteDecision.fallback(
                    String.format(
                            Locale.ROOT,
                            "置信度 %.2f 低于阈值 %.2f",
                            confidence,
                            minConfidence));
        }
        return switch (target) {
            case RouteDecision.TARGET_AGENT -> {
                if (agentId.isBlank()) {
                    yield RouteDecision.fallback("路由选择单智能体但未给出 agent_id");
                }
                AgentDefinition agent =
                        agents.stream().filter(item -> item.id().equals(agentId)).findFirst()
                                .orElse(null);
                yield agent == null
                        ? RouteDecision.fallback("路由给出的智能体不在候选列表：" + agentId)
                        : new RouteDecision(
                                RouteDecision.TARGET_AGENT, agent, reason, confidence, null);
            }
            case RouteDecision.TARGET_TEAM ->
                    teamAvailable
                            ? new RouteDecision(
                                    RouteDecision.TARGET_TEAM, null, reason, confidence, null)
                            : RouteDecision.fallback("路由选择团队，但当前用户/会话未启用动态团队");
            case RouteDecision.TARGET_PIPELINE ->
                    new RouteDecision(
                            RouteDecision.TARGET_PIPELINE, null, reason, confidence, null);
            default -> RouteDecision.fallback("路由目标非法：" + target);
        };
    }

    private RouteDecision logFallback(
            String reason, List<AgentDefinition> agents, boolean teamAvailable, long startedAt) {
        log.info(
                "[智能体路由] 未生效：候选={}，团队可用={}，耗时={}ms，原因={}",
                agents.size(),
                teamAvailable,
                System.currentTimeMillis() - startedAt,
                reason);
        return RouteDecision.fallback(reason);
    }

    private static String instruction(List<AgentDefinition> agents, boolean teamAvailable) {
        StringBuilder text = new StringBuilder(INSTRUCTION);
        text.append("\n候选智能体：\n");
        if (agents.isEmpty()) {
            text.append("- （无可用单智能体）\n");
        } else {
            for (AgentDefinition agent : agents) {
                text.append("- ")
                        .append(agent.id())
                        .append(" | ")
                        .append(nullSafe(agent.name()))
                        .append(" | ")
                        .append(limit(nullSafe(agent.description()), DESCRIPTION_LIMIT))
                        .append(" | tags=")
                        .append(agent.tags())
                        .append('\n');
            }
        }
        text.append("团队可用：").append(teamAvailable ? "是" : "否").append('\n');
        return text.toString();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String limit(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
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
