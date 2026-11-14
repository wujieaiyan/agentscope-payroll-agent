package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.service.ActionContributor;
import io.agentscope.examples.payroll.service.DefaultActionExecutor;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 把知识库写工具接入确认执行链：提案确认后由 Web 节点在本进程内调用既有服务，
 * 不经过内部 HTTP，也不需要 runtime 持有知识库服务。
 */
@Component
public class KbActionContributor implements ActionContributor {

    private final Map<String, DefaultActionExecutor.LocalAction> actions;
    private final Map<String, String> permissions;

    public KbActionContributor(KbSkillService skillService) {
        Map<String, DefaultActionExecutor.LocalAction> localActions = new LinkedHashMap<>();
        for (String tool : KbSkillService.toolNames()) {
            if (KbSkillService.readOnly(tool)) {
                continue;
            }
            localActions.put(
                    tool,
                    (params, userId) ->
                            skillService.execute(
                                    skillService.resolveUser(userId), tool, params));
        }
        this.actions = Map.copyOf(localActions);
        Map<String, String> toolPermissions = new LinkedHashMap<>();
        actions.keySet()
                .forEach(tool -> toolPermissions.put(tool, KbSkillService.requiredPermission(tool)));
        this.permissions = Map.copyOf(toolPermissions);
    }

    @Override
    public Map<String, DefaultActionExecutor.LocalAction> localActions() {
        return actions;
    }

    @Override
    public Map<String, String> toolPermissions() {
        return permissions;
    }
}
