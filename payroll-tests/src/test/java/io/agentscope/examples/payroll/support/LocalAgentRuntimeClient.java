package io.agentscope.examples.payroll.support;

import io.agentscope.examples.payroll.service.AgentRuntimeClient;
import io.agentscope.examples.payroll.service.ChatSessionManager;
import io.agentscope.examples.payroll.web.ChatSse;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** 同进程运行期替身：代替 HTTP 提交，复用运行期的事件总线与落库路径。 */
public class LocalAgentRuntimeClient extends AgentRuntimeClient {

    private final ChatSessionManager manager;
    private final Executor executor;

    public LocalAgentRuntimeClient(ChatSessionManager manager, Executor executor) {
        super(List.of("http://local-runtime.invalid"), "local-runtime-token");
        this.manager = manager;
        this.executor = executor;
    }

    @Override
    public void submit(ChatSessionManager.TurnContext turn, String message) {
        if (turn.chat() == null) {
            throw new IllegalStateException("测试替身需要在会话管理器上配置对话工厂");
        }
        executor.execute(() -> execute(turn, message));
    }

    private void execute(ChatSessionManager.TurnContext turn, String message) {
        AtomicBoolean finished = new AtomicBoolean(false);
        try {
            turn.chat()
                    .stream(
                            message,
                            ChatSse.busOnly(
                                    manager,
                                    turn,
                                    reply -> {
                                        try {
                                            return manager.completeTurn(turn, reply);
                                        } finally {
                                            finished.set(true);
                                        }
                                    }));
        } catch (RuntimeException e) {
            if (finished.compareAndSet(false, true)) {
                manager.failTurn(
                        turn, e.getMessage() == null ? "测试替身执行失败" : e.getMessage());
            }
        } finally {
            if (finished.compareAndSet(false, true)) {
                manager.releaseTurn(turn, "测试替身提前结束");
            }
            manager.finishRemoteTurn(turn.turnId());
        }
    }
}
