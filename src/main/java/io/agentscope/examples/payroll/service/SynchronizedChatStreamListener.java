package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.domain.ChatToolTrace;

/** 并行节点共享一个监听器时串行转发事件，避免 SSE 写入交错。 */
final class SynchronizedChatStreamListener implements ChatStreamListener {

    private final ChatStreamListener delegate;
    private final Object lock = new Object();

    SynchronizedChatStreamListener(ChatStreamListener delegate) {
        this.delegate = delegate == null ? ChatStreamListener.NOOP : delegate;
    }

    @Override
    public void onStart(String model) {
        synchronized (lock) {
            delegate.onStart(model);
        }
    }

    @Override
    public void onAgentStart(String agent, String label) {
        synchronized (lock) {
            delegate.onAgentStart(agent, label);
        }
    }

    @Override
    public void onAgentOutput(String agent, String delta) {
        synchronized (lock) {
            delegate.onAgentOutput(agent, delta);
        }
    }

    @Override
    public void onAgentEnd(String agent) {
        synchronized (lock) {
            delegate.onAgentEnd(agent);
        }
    }

    @Override
    public void onThinking(String delta) {
        synchronized (lock) {
            delegate.onThinking(delta);
        }
    }

    @Override
    public void onPhase(String label) {
        synchronized (lock) {
            delegate.onPhase(label);
        }
    }

    @Override
    public void onContextCompressed(int compressed, int kept, int summarizedTotal) {
        synchronized (lock) {
            delegate.onContextCompressed(compressed, kept, summarizedTotal);
        }
    }

    @Override
    public void onToolStart(String toolCallId, String toolName) {
        synchronized (lock) {
            delegate.onToolStart(toolCallId, toolName);
        }
    }

    @Override
    public void onToolArgs(String toolCallId, String delta) {
        synchronized (lock) {
            delegate.onToolArgs(toolCallId, delta);
        }
    }

    @Override
    public void onToolResult(String toolCallId, String toolName, String state) {
        synchronized (lock) {
            delegate.onToolResult(toolCallId, toolName, state);
        }
    }

    @Override
    public void onToolResult(
            String toolCallId, String toolName, String state, ChatToolTrace trace) {
        synchronized (lock) {
            delegate.onToolResult(toolCallId, toolName, state, trace);
        }
    }

    @Override
    public void onText(String delta) {
        synchronized (lock) {
            delegate.onText(delta);
        }
    }

    @Override
    public void onActionProposal(io.agentscope.examples.payroll.domain.ActionProposal proposal) {
        synchronized (lock) {
            delegate.onActionProposal(proposal);
        }
    }

    @Override
    public void onDone(PayrollChat.Reply reply) {
        synchronized (lock) {
            delegate.onDone(reply);
        }
    }
}
