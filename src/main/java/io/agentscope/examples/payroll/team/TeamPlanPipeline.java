package io.agentscope.examples.payroll.team;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.FlowDefinition;
import io.agentscope.examples.payroll.service.AgentStatsService;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 影子与正式编排共用的确定性组队流水线：意图 → 模板 → 短名单 → 计划 → 编译。
 *
 * <p>任何一层失败都返回带层级前缀（L0/L2）的回退，由调用方决定落库方式。
 */
public final class TeamPlanPipeline {

    private static final Logger log = LoggerFactory.getLogger(TeamPlanPipeline.class);

    private final TeamSettings settings;
    private final Supplier<List<AgentDefinition>> catalogSupplier;
    private final AgentStatsService statsService;
    private final TeamIntentExtractor intentExtractor;
    private final TeamLlmClient llm;

    public TeamPlanPipeline(
            TeamSettings settings,
            Supplier<List<AgentDefinition>> catalogSupplier,
            AgentStatsService statsService,
            TeamLlmClient llm) {
        this.settings =
                settings == null ? new TeamSettings(TeamMode.OFF, 0, 0, 0D) : settings;
        this.catalogSupplier = catalogSupplier == null ? List::of : catalogSupplier;
        this.statsService = statsService;
        this.llm = llm == null ? TeamLlmClient.disabled() : llm;
        this.intentExtractor = new TeamIntentExtractor(this.llm);
    }

    public Attempt prepare(String question, String planId) {
        TeamIntent intent;
        try {
            intent = intentExtractor.extract(question);
        } catch (RuntimeException e) {
            log.warn("[动态团队] 意图抽取失败：{}", rootMessage(e));
            return Fallback.of(
                    null, null, null, "{}", "L0: 意图抽取失败：" + rootMessage(e));
        }
        if (!intent.eligible()) {
            return Fallback.of(intent, null, null, "{}", "L0: " + ineligibleReason(intent));
        }

        TeamTemplate template = TeamTemplate.select(intent);
        if (template.fallback()) {
            return Fallback.of(intent, null, null, "{}", "L2: 没有匹配的任务模板");
        }

        CapabilityIndex index;
        TeamShortlist shortlist;
        try {
            List<AgentDefinition> loaded = catalogSupplier.get();
            index = CapabilityIndex.from(loaded == null ? List.of() : loaded);
            shortlist =
                    new TeamCandidateSelector(index, statsService, settings)
                            .shortlist(intent, template.id(), template.slots());
        } catch (RuntimeException e) {
            log.warn("[动态团队] 候选筛选失败：{}", rootMessage(e));
            return Fallback.of(
                    intent,
                    template.id(),
                    null,
                    "{}",
                    "L2: 候选筛选失败：" + rootMessage(e));
        }

        PlanResult planResult;
        try {
            planResult =
                    new TeamPlanGenerator(llm, new PlanValidator(index))
                            .generate(intent, template, shortlist, planId);
        } catch (RuntimeException e) {
            planResult = PlanResult.fallback("计划生成异常：" + rootMessage(e), List.of());
        }
        if (!planResult.ok()) {
            return Fallback.of(
                    intent,
                    template.id(),
                    catalogJson(index, null),
                    shortlist.scoreJson(),
                    "L2: " + planResult.fallbackReason());
        }

        TeamPlan plan = planResult.plan();
        FlowDefinition flow = TeamPlanCompiler.compile(plan, "dynamic-" + planId);
        Map<String, AgentDefinition> agents = new LinkedHashMap<>();
        for (String agentId : plan.agentIds()) {
            AgentDefinition definition = index.definition(agentId);
            if (definition != null) {
                agents.put(agentId, definition);
            }
        }
        return new Prepared(
                intent,
                template,
                plan,
                flow,
                agents,
                catalogJson(index, plan),
                shortlist.scoreJson());
    }

    /** 组队成功的完整产物；执行阶段只使用这里解析好的定义与流程。 */
    public record Prepared(
            TeamIntent intent,
            TeamTemplate template,
            TeamPlan plan,
            FlowDefinition flow,
            Map<String, AgentDefinition> agents,
            String catalogJson,
            String scoreJson)
            implements Attempt {

        public Prepared {
            agents = agents == null ? Map.of() : Map.copyOf(agents);
        }
    }

    /** 组队失败；intent/templateId 可能为空，用于审计与回退记录。 */
    public record Fallback(
            TeamIntent intent,
            String templateId,
            String catalogJson,
            String scoreJson,
            String reason)
            implements Attempt {

        public static Fallback of(
                TeamIntent intent,
                String templateId,
                String catalogJson,
                String scoreJson,
                String reason) {
            return new Fallback(intent, templateId, catalogJson, scoreJson, reason);
        }
    }

    public sealed interface Attempt permits Prepared, Fallback {
    }

    public static String catalogJson(CapabilityIndex index, TeamPlan plan) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Map<String, Object>> agents = new ArrayList<>();
        List<AgentCapabilityProfile> profiles =
                plan == null
                        ? index.profiles()
                        : plan.agentIds().stream()
                                .map(index::profile)
                                .filter(java.util.Objects::nonNull)
                                .toList();
        for (AgentCapabilityProfile profile : profiles) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("agent_id", profile.agentId());
            item.put("name", profile.name());
            item.put("capabilities", profile.capabilities());
            item.put("source", profile.source());
            item.put("latency_class", profile.latencyClass());
            agents.add(item);
        }
        root.put("agents", agents);
        return JsonSupport.toJson(root);
    }

    private static String ineligibleReason(TeamIntent intent) {
        if (intent.writeAction()) {
            return "写操作直接走静态链路";
        }
        if (TeamIntent.TASK_OTHER.equals(intent.taskType())) {
            return "无法归类任务";
        }
        if (intent.confidence() < 0.6D) {
            return "意图置信度低于 0.6";
        }
        if (intent.requiredCapabilities().isEmpty()) {
            return "未识别到所需能力";
        }
        return "不满足动态组队条件";
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
