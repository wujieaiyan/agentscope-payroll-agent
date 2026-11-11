package io.agentscope.examples.payroll.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * 外部（MCP）工具的确认闸门包装：只读声明保持直通，其余在 {@link ActionGuard} 生成提案。
 *
 * <p>包装保留原工具的名称、参数与只读声明，执行仍委托原实现，不改变 MCP 传输语义。
 */
final class GuardedAgentTool implements AgentTool {

    private final AgentTool delegate;

    GuardedAgentTool(AgentTool delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public Map<String, Object> getParameters() {
        return delegate.getParameters();
    }

    @Override
    public Boolean getStrict() {
        return delegate.getStrict();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return delegate.getOutputSchema();
    }

    @Override
    public boolean isReadOnly() {
        return delegate.isReadOnly();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        RuntimeContext context = param.getRuntimeContext();
        ActionGuard guard = context == null ? null : context.get(ActionGuard.class);
        if (guard != null) {
            McpToolHints.Hints hints = McpToolHints.get(delegate.getName());
            boolean readOnly = hints == null ? delegate.isReadOnly() : hints.readOnly();
            boolean destructive = hints != null && hints.destructive();
            String pending =
                    guard.intercept(
                            delegate.getName(),
                            param.getInput(),
                            true,
                            readOnly,
                            destructive);
            if (pending != null) {
                ToolUseBlock use = param.getToolUseBlock();
                String id = use == null ? null : use.getId();
                return Mono.just(
                        ToolResultBlock.of(
                                id, delegate.getName(), TextBlock.builder().text(pending).build()));
            }
        }
        return delegate.callAsync(param);
    }
}
