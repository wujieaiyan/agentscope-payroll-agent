package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.service.TurnMetrics;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 对话执行器拒绝时的统一记录：指标与队列长度，具体响应由调用方决定。 */
public final class ChatStreamRejection {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamRejection.class);

    private ChatStreamRejection() {}

    public static void record(Executor executor) {
        record(executor, "executor");
    }

    /** reason 区分 executor / tail / runtime，指标与日志都按执行器归属。 */
    public static void record(Executor executor, String reason) {
        TurnMetrics.streamRejected(reason);
        int queued = -1;
        int active = -1;
        if (executor instanceof ThreadPoolExecutor pool) {
            queued = pool.getQueue().size();
            active = pool.getActiveCount();
        }
        log.warn("[SSE] 对话执行队列已满：reason={}，queued={}，active={}", reason, queued, active);
    }
}
