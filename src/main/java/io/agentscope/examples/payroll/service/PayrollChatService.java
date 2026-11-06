package io.agentscope.examples.payroll.service;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.examples.payroll.agent.PayrollAgentFactory;
import io.agentscope.examples.payroll.agent.PayrollTools;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 管理台对话实现：复用与 CLI 相同的 ReActAgent 与只读工具。
 *
 * <p>单机管理台只维护一个会话，串行处理请求；模型调用失败时转成可读的业务异常，
 * 不影响管理台其他功能。
 */
public final class PayrollChatService implements PayrollChat {

    private static final Duration TIMEOUT = Duration.ofSeconds(180);

    private final PayrollEngine engine;
    private final Object lock = new Object();
    private ReActAgent agent;

    public PayrollChatService(PayrollEngine engine) {
        this.engine = engine;
    }

    @Override
    public Reply chat(String message) {
        if (message == null || message.isBlank()) {
            throw new PayrollException("请输入要咨询的问题");
        }
        synchronized (lock) {
            ReActAgent current = agent();
            StringBuilder streamed = new StringBuilder();
            String[] finalText = {null};
            Set<String> tools = new LinkedHashSet<>();
            try {
                current.streamEvents(new UserMessage(message.trim()))
                        .doOnNext(
                                event -> {
                                    if (event instanceof ToolCallStartEvent call) {
                                        tools.add(call.getToolCallName());
                                    } else if (event instanceof TextBlockDeltaEvent delta) {
                                        streamed.append(delta.getDelta());
                                    } else if (event instanceof AgentResultEvent result
                                            && result.getResult() != null) {
                                        finalText[0] = result.getResult().getTextContent();
                                    }
                                })
                        .blockLast(TIMEOUT);
            } catch (PayrollException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new PayrollException(
                        "对话失败："
                                + rootMessage(e)
                                + "。请确认模型服务可用：默认需要先执行 ollama serve，"
                                + "并已拉取 PAYROLL_MODEL 指定的模型；也可配置 DashScope。",
                        e);
            }
            String reply =
                    finalText[0] != null && !finalText[0].isBlank()
                            ? finalText[0].trim()
                            : streamed.toString().trim();
            if (reply.isBlank()) {
                throw new PayrollException("模型未返回内容，请重试或检查模型服务状态。");
            }
            return new Reply(reply, List.copyOf(tools), modelId());
        }
    }

    @Override
    public String modelId() {
        return PayrollAgentFactory.modelId();
    }

    @Override
    public void reset() {
        synchronized (lock) {
            if (agent != null) {
                agent.close();
                agent = null;
            }
        }
    }

    @Override
    public void close() {
        reset();
    }

    private ReActAgent agent() {
        if (agent == null) {
            agent = PayrollAgentFactory.create(new PayrollTools(engine));
        }
        return agent;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message == null ? error.getClass().getSimpleName() : message;
    }
}
