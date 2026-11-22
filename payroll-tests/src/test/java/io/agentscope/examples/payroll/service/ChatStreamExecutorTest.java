package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.config.PayrollCoreConfiguration;
import io.agentscope.examples.payroll.web.ChatStreamRejection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatStreamExecutorTest {

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        TurnMetrics.useRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        TurnMetrics.useRegistry(null);
    }

    @Test
    @DisplayName("线程与队列占满后立即拒绝，不阻塞调用线程")
    void rejectsWhenSaturated() throws Exception {
        ExecutorService executor = new PayrollCoreConfiguration().chatStreamExecutor(1, 1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> awaitQuietly(release));
            Thread.sleep(50L);
            executor.execute(() -> {});

            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("线程数与队列容量为 0 时钳制为 1，仍是同一有界实现")
    void clampsNonPositiveSizes() {
        ExecutorService executor = new PayrollCoreConfiguration().chatStreamExecutor(0, 0);
        try {
            ThreadPoolExecutor pool = assertInstanceOf(ThreadPoolExecutor.class, executor);
            assertEquals(1, pool.getMaximumPoolSize());
            ArrayBlockingQueue<?> queue =
                    assertInstanceOf(ArrayBlockingQueue.class, pool.getQueue());
            assertEquals(1, queue.remainingCapacity());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("拒绝时记录队列指标并携带队列长度")
    void rejectionRecordsMetric() throws Exception {
        ExecutorService executor = new PayrollCoreConfiguration().chatStreamExecutor(1, 1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> awaitQuietly(release));
            Thread.sleep(50L);
            executor.execute(() -> {});
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));

            ChatStreamRejection.record(executor);
            ChatStreamRejection.record(executor);

            assertEquals(2.0, registry.counter(TurnMetrics.STREAM_REJECTED).count());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("非线程池执行器也可记录拒绝指标")
    void rejectionRecordsMetricForForeignExecutor() {
        ChatStreamRejection.record(Runnable::run);

        assertEquals(1.0, registry.counter(TurnMetrics.STREAM_REJECTED).count());
        assertTrue(registry.counter(TurnMetrics.STREAM_REJECTED).count() > 0);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
