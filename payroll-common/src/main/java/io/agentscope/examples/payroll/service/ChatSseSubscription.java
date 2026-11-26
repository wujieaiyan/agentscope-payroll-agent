package io.agentscope.examples.payroll.service;

/** 推送模式下的一次 SSE 订阅；关闭只影响该订阅，不阻塞轮次执行。 */
public interface ChatSseSubscription extends AutoCloseable {

    /** 最后一次已投递（或入队）的事件序号。 */
    long lastSeq();

    boolean active();

    @Override
    void close();
}
