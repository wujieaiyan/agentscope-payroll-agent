package io.agentscope.examples.payroll.service;

import java.util.concurrent.Executor;

/** SSE 推送模式运行参数：写池、心跳、空闲检查、写超时、看门狗节拍与订阅缓冲。 */
public record SseFanoutSettings(
        Executor writer,
        long pingMillis,
        long idleCheckMillis,
        long writeTimeoutMillis,
        long watchdogTickMillis,
        int subscriberBuffer) {

    public SseFanoutSettings {
        writer = writer == null ? Runnable::run : writer;
        pingMillis = pingMillis <= 0 ? 15_000L : pingMillis;
        idleCheckMillis = idleCheckMillis <= 0 ? 10_000L : idleCheckMillis;
        writeTimeoutMillis = writeTimeoutMillis <= 0 ? 5_000L : writeTimeoutMillis;
        watchdogTickMillis = watchdogTickMillis <= 0 ? 1_000L : watchdogTickMillis;
        subscriberBuffer = subscriberBuffer <= 0 ? 512 : subscriberBuffer;
    }

    public static SseFanoutSettings defaults() {
        return new SseFanoutSettings(null, 15_000L, 10_000L, 5_000L, 1_000L, 512);
    }
}
