package io.agentscope.examples.payroll.team;

import io.agentscope.examples.payroll.domain.FlowEdge;
import java.util.List;
import java.util.Locale;

/**
 * 确定性团队拓扑。模型只能在模板槽位内选人，不能自由生成 DAG。
 *
 * <p>涉及金额时模板强制包含 VALIDATOR，且 VALIDATOR 位于 CALCULATOR 下游。
 */
public enum TeamTemplate {
    CALC_VALIDATE(
            "CALC_VALIDATE",
            "核算 → 复核",
            List.of(TeamRole.CALCULATOR, TeamRole.VALIDATOR),
            List.of(
                    Link.startTo(TeamRole.CALCULATOR),
                    Link.between(TeamRole.CALCULATOR, TeamRole.VALIDATOR),
                    Link.toEnd(TeamRole.VALIDATOR))),
    CALC_VALIDATE_POST(
            "CALC_VALIDATE_POST",
            "核算 → 复核 → 入账建议",
            List.of(TeamRole.CALCULATOR, TeamRole.VALIDATOR, TeamRole.ACCOUNTANT),
            List.of(
                    Link.startTo(TeamRole.CALCULATOR),
                    Link.between(TeamRole.CALCULATOR, TeamRole.VALIDATOR),
                    Link.between(TeamRole.VALIDATOR, TeamRole.ACCOUNTANT),
                    Link.toEnd(TeamRole.ACCOUNTANT))),
    QUERY_ONLY(
            "QUERY_ONLY",
            "查询",
            List.of(TeamRole.QUERY),
            List.of(Link.startTo(TeamRole.QUERY), Link.toEnd(TeamRole.QUERY))),
    KNOWLEDGE_ONLY(
            "KNOWLEDGE_ONLY",
            "知识问答",
            List.of(TeamRole.KNOWLEDGE),
            List.of(Link.startTo(TeamRole.KNOWLEDGE), Link.toEnd(TeamRole.KNOWLEDGE))),
    KB_CONFIG(
            "KB_CONFIG",
            "知识库配置",
            List.of(TeamRole.KB_CONFIG),
            List.of(Link.startTo(TeamRole.KB_CONFIG), Link.toEnd(TeamRole.KB_CONFIG))),
    QUERY_THEN_CALC(
            "QUERY_THEN_CALC",
            "查询 + 核算 → 复核",
            List.of(TeamRole.QUERY, TeamRole.CALCULATOR, TeamRole.VALIDATOR),
            List.of(
                    Link.startTo(TeamRole.QUERY),
                    Link.startTo(TeamRole.CALCULATOR),
                    Link.between(TeamRole.QUERY, TeamRole.VALIDATOR),
                    Link.between(TeamRole.CALCULATOR, TeamRole.VALIDATOR),
                    Link.toEnd(TeamRole.VALIDATOR))),
    FALLBACK(
            "FALLBACK",
            "静态链路",
            List.of(),
            List.of());

    public static final int MAX_AGENT_NODES = 5;
    public static final int MAX_DEPTH = 4;

    private final String id;
    private final String label;
    private final List<TeamRole> slots;
    private final List<Link> links;

    TeamTemplate(String id, String label, List<TeamRole> slots, List<Link> links) {
        this.id = id;
        this.label = label;
        this.slots = List.copyOf(slots);
        this.links = List.copyOf(links);
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public List<TeamRole> slots() {
        return slots;
    }

    public List<Link> links() {
        return links;
    }

    public boolean fallback() {
        return this == FALLBACK;
    }

    public boolean requiresValidation() {
        return slots.contains(TeamRole.VALIDATOR);
    }

    public static TeamTemplate select(TeamIntent intent) {
        if (intent == null || !intent.eligible()) {
            return FALLBACK;
        }
        return switch (intent.taskType()) {
            case TeamIntent.TASK_CALCULATE -> {
                if (intent.requires(TeamIntent.CAP_PAYROLL_QUERY)) {
                    yield QUERY_THEN_CALC;
                }
                yield intent.requires(TeamIntent.CAP_ACCOUNTING)
                        ? CALC_VALIDATE_POST
                        : CALC_VALIDATE;
            }
            case TeamIntent.TASK_PAYSLIP_QUERY, TeamIntent.TASK_BATCH_QUERY -> QUERY_ONLY;
            case TeamIntent.TASK_POLICY_EXPLAIN -> KNOWLEDGE_ONLY;
            case TeamIntent.TASK_KB_CONFIG -> KB_CONFIG;
            default -> FALLBACK;
        };
    }

    public static TeamTemplate from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (TeamTemplate template : values()) {
            if (template.id.equals(normalized)) {
                return template;
            }
        }
        return null;
    }

    public static boolean known(String value) {
        return from(value) != null;
    }

    /** 模板连线：source/target 为 start、end 或角色名。 */
    public record Link(String source, String target, String sourceHandle) {

        public Link {
            source = source == null ? "" : source;
            target = target == null ? "" : target;
            sourceHandle =
                    sourceHandle == null || sourceHandle.isBlank()
                            ? FlowEdge.HANDLE_TRUE
                            : sourceHandle.toUpperCase(Locale.ROOT);
        }

        public static Link startTo(TeamRole target) {
            return new Link("start", target.name(), FlowEdge.HANDLE_TRUE);
        }

        public static Link between(TeamRole source, TeamRole target) {
            return new Link(source.name(), target.name(), FlowEdge.HANDLE_TRUE);
        }

        public static Link toEnd(TeamRole source) {
            return new Link(source.name(), "end", FlowEdge.HANDLE_TRUE);
        }
    }
}
