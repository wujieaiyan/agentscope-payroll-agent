package io.agentscope.examples.payroll.service;

/** 为每个对话窗口创建独立的对话实例，避免多个窗口共享模型上下文。 */
@FunctionalInterface
public interface PayrollChatFactory {

    PayrollChat create();
}
