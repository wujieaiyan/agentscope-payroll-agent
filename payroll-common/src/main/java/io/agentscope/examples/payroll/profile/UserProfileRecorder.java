package io.agentscope.examples.payroll.profile;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 抽取投递入口：单线程有界队列，队列满时丢弃并计数，不阻塞对话线程。
 * 未装配（web 节点或开关关闭）时为空操作。
 */
public final class UserProfileRecorder {

    private static final Logger log = LoggerFactory.getLogger(UserProfileRecorder.class);
    private static final int QUEUE_CAPACITY = 512;
    private static final AtomicLong DROPPED = new AtomicLong();

    private static final ExecutorService WORKER =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                    runnable -> {
                        Thread thread = new Thread(runnable, "user-profile-writer");
                        thread.setDaemon(true);
                        thread.setPriority(Thread.MIN_PRIORITY);
                        return thread;
                    },
                    (runnable, executor) -> {
                        long dropped = DROPPED.incrementAndGet();
                        if (dropped == 1 || dropped % 100 == 0) {
                            log.warn("[工作偏好] 抽取队列已满，累计丢弃 {} 次触发", dropped);
                        }
                    });

    private static volatile UserProfileService service;
    private static volatile boolean async = true;

    private UserProfileRecorder() {
    }

    public static void configure(UserProfileService profileService) {
        configure(profileService, true);
    }

    /** async=false 供单测使用，避免依赖后台线程时序。 */
    public static void configure(UserProfileService profileService, boolean asyncRun) {
        service = profileService;
        async = asyncRun;
    }

    public static void reset() {
        service = null;
        async = true;
    }

    public static void schedule(long userId) {
        if (userId <= 0) {
            return;
        }
        UserProfileService target = service;
        if (target == null) {
            return;
        }
        if (!async) {
            run(target, userId);
            return;
        }
        try {
            WORKER.execute(() -> run(target, userId));
        } catch (RuntimeException e) {
            log.debug("[工作偏好] 提交抽取任务失败：{}", e.getClass().getSimpleName());
        }
    }

    public static long droppedCount() {
        return DROPPED.get();
    }

    private static void run(UserProfileService target, long userId) {
        try {
            target.process(userId);
        } catch (RuntimeException e) {
            log.warn("[工作偏好] 抽取任务失败：userId={}，原因={}", userId, e.getClass().getSimpleName());
        }
    }
}
