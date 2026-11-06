package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.agent.PayrollAgentFactory;
import java.util.List;

/** 管理台对话入口，便于在测试中替换为固定实现。 */
public interface PayrollChat extends AutoCloseable {

    Reply chat(String message);

    default String modelId() {
        return PayrollAgentFactory.modelId();
    }

    default void reset() {
    }

    @Override
    default void close() {
    }

    /** 一次对话结果；tools 为本次回答调用过的工具名，便于界面展示。 */
    record Reply(String reply, List<String> tools, String model) {
    }
}
