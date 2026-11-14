package io.agentscope.examples.payroll.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.skill.runtime.SkillLoadTool;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 把 harness 的技能加载结果桥接到 core 的工具组激活机制。
 *
 * <p>harness 的 {@code load_skill_through_path} 只返回技能内容，不会像 core {@code SkillBox}
 * 那样调用 {@link Toolkit#updateToolGroups}。业务工具挂在未激活的 {@code role-tools} 组里，
 * 若不在此处激活，模型加载技能后仍然看不到业务工具。
 *
 * <p>激活发生在本轮 acting 结束前，ReActAgent 随后会把当前工具组状态同步到会话状态，
 * 下一轮推理即可看到业务工具，与 core 的时序一致。
 */
@SuppressWarnings("deprecation")
final class SkillToolGroupActivationMiddleware implements MiddlewareBase {

    private static final Logger log =
            LoggerFactory.getLogger(SkillToolGroupActivationMiddleware.class);

    private final AgentSkillRepository repository;

    SkillToolGroupActivationMiddleware(AgentSkillRepository repository) {
        this.repository = repository;
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        Map<String, String> skillNames = skillNamesByCall(input.toolCalls());
        if (skillNames.isEmpty()) {
            return next.apply(input);
        }
        return next.apply(input)
                .doOnNext(
                        event -> {
                            if (event instanceof ToolResultEndEvent result
                                    && result.getState() == ToolResultState.SUCCESS) {
                                String skillName = skillNames.get(result.getToolCallId());
                                if (skillName != null) {
                                    activate(agent, skillName);
                                }
                            }
                        });
    }

    private Map<String, String> skillNamesByCall(List<ToolUseBlock> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        for (ToolUseBlock call : toolCalls) {
            if (!SkillLoadTool.TOOL_NAME.equals(call.getName())) {
                continue;
            }
            String skillId = stringValue(call.getInput().get("skillId"));
            String skillName = resolveSkillName(skillId);
            if (skillName != null) {
                names.put(call.getId(), skillName);
            }
        }
        return names;
    }

    /**
     * 工具组按技能裸名称激活（与 core SkillBox 一致）。模型可能传完整 skillId，
     * 因此先按仓库的 getSkill 解析，再回退到逐个匹配 name/skillId。
     */
    private String resolveSkillName(String skillId) {
        if (skillId == null || skillId.isBlank() || repository == null) {
            return null;
        }
        AgentSkill skill = repository.getSkill(skillId);
        if (skill != null) {
            return skill.getName();
        }
        for (AgentSkill candidate : repository.getAllSkills()) {
            if (skillId.equals(candidate.getSkillId()) || skillId.equals(candidate.getName())) {
                return candidate.getName();
            }
        }
        return null;
    }

    private void activate(Agent agent, String skillName) {
        Toolkit toolkit = agent.getToolkit();
        if (toolkit == null) {
            return;
        }
        List<String> groups = toolkit.findSkillToolGroupsByActivateOnSkill(skillName);
        if (groups.isEmpty()) {
            log.debug("[Skill] 技能 {} 未绑定工具组", skillName);
            return;
        }
        if (toolkit.getActiveGroups().containsAll(groups)) {
            return;
        }
        toolkit.updateToolGroups(groups, true);
        log.info("[Skill] 已激活技能 {} 的业务工具组：{}", skillName, groups);
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
