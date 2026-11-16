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

    /** 仅追加在非薪酬问题上；薪酬问题继续使用 {@link #INSTRUCTION} 原文，保证 prompt 可比。 */
    private static final String KB_SECTION =
            """

            知识库判定（need_kb / kb_kind，仅非薪酬问题需要填写）：
            - need_kb：本轮是否需要检索知识库。制度、流程、条款、手册、产品资料、技术文档类问题，
              或对上一轮已引用内容的追问填 true；视频制作、写作、闲聊、纯数据查询、执行类指令填 false。
            - kb_kind：需要检索填 "read"；知识库管理操作（建库、解析/分块/检索配置、目录与成员权限、
              入库任务）填 "admin"；其余填 "none"。
            - 输出 JSON 需同时包含这两个字段，缺省按 false / "none" 处理：
              {"target":"agent|team|pipeline","agent_id":"候选 id 或空串","need_kb":false,"kb_kind":"none","reason":"一句话中文理由","confidence":0.0}
            """;

    /** 非薪酬问题的创作类判定：小模型容易把带“写”字的请求一律当办公文书。 */
    private static final String CREATIVE_SECTION =
            """

            创作类判定（仅非薪酬问题）：
            - 诗词、歌词、小说、散文、剧本等文学创作，选名称、描述或 tags 最贴近该领域的创作类智能体；
            - 只有通知、报告、会议纪要、邮件等办公文书才选办公文书类智能体；
              不要因为句中出现“写”“创作”等字样就选办公文书专家。
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
        return decide(question, candidates, teamAvailable, sessionKey, false);
    }

    /**
     * @param payroll 薪酬问题使用原 instruction，不追加知识库判定段
     */
    public RouteDecision decide(
            String question,
            List<AgentDefinition> candidates,
            boolean teamAvailable,
            String sessionKey,
            boolean payroll) {
        return decide(question, candidates, teamAvailable, sessionKey, payroll, null);
    }

    /**
     * @param preferred 管理员配置的当前活跃智能体，作为默认首选写进提示词；
     *     只有问题明显属于其他候选的专长时才允许模型切换，避免小模型按单字误判换人。
     */
    public RouteDecision decide(
            String question,
            List<AgentDefinition> candidates,
            boolean teamAvailable,
            String sessionKey,
            boolean payroll,
            AgentDefinition preferred) {
        if (!enabled) {
            return null;
        }
        List<AgentDefinition> agents = candidates == null ? List.of() : List.copyOf(candidates);
        long startedAt = System.currentTimeMillis();
        String raw;
        try {
            raw =
                    model.complete(
                            instruction(agents, teamAvailable, payroll, preferred),
                            question,
                            sessionKey);
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
                    "[智能体路由] 决策：target={}，agent={}，置信度={}，need_kb={}，kb_kind={}，候选={}，团队可用={}，耗时={}ms，理由={}",
                    decision.target(),
                    decision.agent() == null ? "" : decision.agent().id(),
                    String.format(Locale.ROOT, "%.2f", decision.confidence()),
                    decision.needKb(),
                    decision.kbKind(),
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
        String rawTarget = text(node, "target").toLowerCase(Locale.ROOT);
        String rawAgentId = text(node, "agent_id");
        // 小模型偶发把 agent_id 填进 target；能精确命中候选 id 时按单智能体处理。
        boolean idInTarget =
                rawAgentId.isBlank()
                        && !isTarget(rawTarget)
                        && agents.stream().anyMatch(item -> item.id().equals(rawTarget));
        String target = idInTarget ? RouteDecision.TARGET_AGENT : rawTarget;
        String agentId = idInTarget ? rawTarget : rawAgentId;
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
        boolean needKb = needKb(node);
        String kbKind = kbKind(node, needKb);
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
                                RouteDecision.TARGET_AGENT,
                                agent,
                                reason,
                                confidence,
                                null,
                                needKb,
                                kbKind,
                                RouteDecision.KB_SOURCE_ROUTER);
            }
            case RouteDecision.TARGET_TEAM ->
                    teamAvailable
                            ? new RouteDecision(
                                    RouteDecision.TARGET_TEAM,
                                    null,
                                    reason,
                                    confidence,
                                    null,
                                    needKb,
                                    kbKind,
                                    RouteDecision.KB_SOURCE_ROUTER)
                            : RouteDecision.fallback("路由选择团队，但当前用户/会话未启用动态团队");
            case RouteDecision.TARGET_PIPELINE ->
                    new RouteDecision(
                            RouteDecision.TARGET_PIPELINE,
                            null,
                            reason,
                            confidence,
                            null,
                            needKb,
                            kbKind,
                            RouteDecision.KB_SOURCE_ROUTER);
            default -> RouteDecision.fallback("路由目标非法：" + target);
        };
    }

    /** 字段缺失或类型异常都按 false，不判路由失败。 */
    private static boolean needKb(JsonNode node) {
        JsonNode value = node.get("need_kb");
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return Boolean.parseBoolean(value.asText("").trim());
    }

    /** 非法或缺失值：需要检索时按 read，不检索时按 none。 */
    private static String kbKind(JsonNode node, boolean needKb) {
        String raw = text(node, "kb_kind").toLowerCase(Locale.ROOT);
        return switch (raw) {
            case RouteDecision.KB_READ, RouteDecision.KB_ADMIN -> raw;
            default -> needKb ? RouteDecision.KB_READ : RouteDecision.KB_NONE;
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

    static String instruction(
            List<AgentDefinition> agents, boolean teamAvailable, boolean payroll) {
        return instruction(agents, teamAvailable, payroll, null);
    }

    static String instruction(
            List<AgentDefinition> agents,
            boolean teamAvailable,
            boolean payroll,
            AgentDefinition preferred) {
        StringBuilder text = new StringBuilder(INSTRUCTION);
        if (!payroll) {
            text.append(KB_SECTION);
            text.append(CREATIVE_SECTION);
        }
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
        // 薪酬问题必须优先走核算链路，不注入活跃智能体偏好，保持 prompt 与改造前一致。
        if (!payroll && preferred != null) {
            text.append("当前活跃智能体（默认首选）：")
                    .append(preferred.id())
                    .append(" | ")
                    .append(nullSafe(preferred.name()))
                    .append(" | ")
                    .append(limit(nullSafe(preferred.description()), DESCRIPTION_LIMIT))
                    .append(" | tags=")
                    .append(preferred.tags())
                    .append('\n');
            text.append("默认保留当前活跃智能体；只有用户问题明显属于其他候选的专长")
                    .append("（工资核算、政策制度、数据查询、知识库管理等）时才切换。\n");
        }
        return text.toString();
    }

    private static boolean isTarget(String value) {
        return RouteDecision.TARGET_AGENT.equals(value)
                || RouteDecision.TARGET_TEAM.equals(value)
                || RouteDecision.TARGET_PIPELINE.equals(value);
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
