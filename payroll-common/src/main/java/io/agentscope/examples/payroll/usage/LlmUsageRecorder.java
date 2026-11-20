package io.agentscope.examples.payroll.usage;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用量记录入口：默认交给单线程有界队列异步落库，避免 JDBC 阻塞模型流。
 *
 * <p>队列满时丢弃并累计计数（看板是观测数据，宁可少记也不能拖慢对话）；
 * 未装配存储时为空操作，单测与 CLI 场景无需额外配置。
 */
public final class LlmUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageRecorder.class);
    private static final int QUEUE_CAPACITY = 8_192;
    private static final AtomicLong DROPPED = new AtomicLong();

    private static final ExecutorService WRITER =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                    runnable -> {
                        Thread thread = new Thread(runnable, "llm-usage-writer");
                        thread.setDaemon(true);
                        thread.setPriority(Thread.MIN_PRIORITY);
                        return thread;
                    },
                    (runnable, executor) -> {
                        long dropped = DROPPED.incrementAndGet();
                        if (dropped == 1 || dropped % 1_000 == 0) {
                            log.warn("[用量] 写入队列已满，累计丢弃 {} 条调用记录", dropped);
                        }
                    });

    private static volatile LlmUsageStore store;
    private static volatile boolean async = true;

    private LlmUsageRecorder() {
    }

    public static void configure(LlmUsageStore usageStore) {
        configure(usageStore, true);
    }

    /** async=false 供单测或需要同步落库的嵌入式场景使用。 */
    public static void configure(LlmUsageStore usageStore, boolean asyncWrite) {
        store = usageStore;
        async = asyncWrite;
    }

    public static void reset() {
        store = null;
        async = true;
    }

    public static void record(LlmUsageRecord record) {
        if (record == null) {
            return;
        }
        LlmUsageStore target = store;
        if (target == null) {
            return;
        }
        if (!async) {
            write(target, record);
            return;
        }
        try {
            WRITER.execute(() -> write(target, record));
        } catch (RuntimeException e) {
            log.debug("[用量] 提交写入任务失败：{}", e.getClass().getSimpleName());
        }
    }

    public static long droppedCount() {
        return DROPPED.get();
    }

    private static void write(LlmUsageStore target, LlmUsageRecord record) {
        try {
            target.insert(record);
        } catch (RuntimeException e) {
            log.warn("[用量] 写入失败：{}", e.getMessage());
        }
    }
}
