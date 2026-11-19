package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.DevTaskStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DevTaskExecutorTest {

    private static final String PLAN = "plan-exec";

    private final DevTaskStore store =
            new DevTaskStore(PayrollDatabase.inMemory("dev-exec-" + System.nanoTime()));
    private final DevTaskBoard board = new DevTaskBoard(store);
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("提交后异步执行：落 DONE、记录起止时间并自动解锁下游")
    void completesTaskAndUnlocksDependents() {
        publishPlan();
        DevTaskExecutor executor = new DevTaskExecutor(board, run -> "改动完成", pool);

        DevTask submitted = executor.submit(PLAN, "n1");
        assertEquals(DevTaskStatus.RUNNING, submitted.status());
        assertTrue(executor.awaitIdle(Duration.ofSeconds(10)));

        DevTask done = board.task(PLAN, "n1");
        assertEquals(DevTaskStatus.DONE, done.status());
        assertEquals("改动完成", done.note());
        assertEquals("", done.error());
        assertNotNull(done.startedAt());
        assertNotNull(done.finishedAt());
        assertEquals(DevTaskStatus.READY, board.task(PLAN, "n2").status());
        assertEquals(0, executor.activeCount());
    }

    @Test
    @DisplayName("运行器异常落 FAILED 并记录原因，下游保持阻塞")
    void failureMarksTaskFailed() {
        publishPlan();
        DevTaskExecutor executor =
                new DevTaskExecutor(
                        board,
                        run -> {
                            throw new IllegalStateException("模拟编译失败");
                        },
                        pool);

        executor.submit(PLAN, "n1");
        assertTrue(executor.awaitIdle(Duration.ofSeconds(10)));

        DevTask failed = board.task(PLAN, "n1");
        assertEquals(DevTaskStatus.FAILED, failed.status());
        assertTrue(failed.note().startsWith("执行失败："), failed.note());
        assertEquals("模拟编译失败", failed.error());
        assertEquals(DevTaskStatus.BLOCKED, board.task(PLAN, "n2").status());
    }

    @Test
    @DisplayName("取消运行中任务通过信号收敛为 CANCELLED")
    void cancelRunningTask() throws Exception {
        publishPlan();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DevTaskExecutor executor =
                new DevTaskExecutor(
                        board,
                        run -> {
                            run.onCancel(release::countDown);
                            started.countDown();
                            try {
                                release.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            run.checkpoint();
                            return "不应返回";
                        },
                        pool);

        executor.submit(PLAN, "n1");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        executor.cancel(PLAN, "n1");

        assertTrue(executor.awaitIdle(Duration.ofSeconds(10)));
        DevTask cancelled = board.task(PLAN, "n1");
        assertEquals(DevTaskStatus.CANCELLED, cancelled.status());
        assertEquals(DevTaskStatus.BLOCKED, board.task(PLAN, "n2").status());
    }

    @Test
    @DisplayName("取消排队任务：执行线程接手时直接按取消收敛，不执行运行器")
    void cancelQueuedTask() throws Exception {
        store.replacePlan(
                PLAN,
                List.of(
                        task("n1", "实现", List.of()),
                        task("n2", "验证", List.of("n1")),
                        task("n3", "文档", List.of())));
        board.dispatch(PLAN);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        DevTaskExecutor executor =
                new DevTaskExecutor(
                        board,
                        run -> {
                            runs.incrementAndGet();
                            if ("n1".equals(run.task().taskId())) {
                                run.onCancel(release::countDown);
                                started.countDown();
                                release.await(5, TimeUnit.SECONDS);
                                run.checkpoint();
                            }
                            return "完成";
                        },
                        pool);

        executor.submit(PLAN, "n1");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        executor.submit(PLAN, "n3");
        executor.cancel(PLAN, "n3");

        release.countDown();
        assertTrue(executor.awaitIdle(Duration.ofSeconds(10)));

        assertEquals(DevTaskStatus.CANCELLED, board.task(PLAN, "n3").status());
        assertEquals(1, runs.get());
        assertEquals(DevTaskStatus.DONE, board.task(PLAN, "n1").status());
    }

    @Test
    @DisplayName("人工重试次数递增，第二次执行成功后落 DONE")
    void retryIncrementsAttemptAndRunsAgain() {
        publishPlan();
        AtomicInteger calls = new AtomicInteger();
        DevTaskExecutor executor =
                new DevTaskExecutor(
                        board,
                        run -> {
                            if (calls.incrementAndGet() == 1) {
                                throw new IllegalStateException("第一次失败");
                            }
                            return "第二次成功";
                        },
                        pool);

        executor.submit(PLAN, "n1");
        assertTrue(executor.awaitIdle(Duration.ofSeconds(10)));
        assertEquals(DevTaskStatus.FAILED, board.task(PLAN, "n1").status());

        executor.retry(PLAN, "n1");
        DevTask ready = board.task(PLAN, "n1");
        assertEquals(DevTaskStatus.READY, ready.status());
        assertEquals(1, ready.attempt());

        executor.submit(PLAN, "n1");
        assertTrue(executor.awaitIdle(Duration.ofSeconds(10)));
        DevTask done = board.task(PLAN, "n1");
        assertEquals(DevTaskStatus.DONE, done.status());
        assertEquals("第二次成功", done.note());
        assertEquals(1, done.attempt());
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("同一任务重复提交拒绝，非 READY 任务不可提交")
    void rejectsDuplicateSubmit() throws Exception {
        publishPlan();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DevTaskExecutor executor =
                new DevTaskExecutor(
                        board,
                        run -> {
                            run.onCancel(release::countDown);
                            started.countDown();
                            release.await(5, TimeUnit.SECONDS);
                            return "完成";
                        },
                        pool);

        executor.submit(PLAN, "n1");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertThrows(PayrollConflictException.class, () -> executor.submit(PLAN, "n1"));
        assertThrows(
                io.agentscope.examples.payroll.domain.PayrollException.class,
                () -> executor.submit(PLAN, "n2"));

        release.countDown();
        assertTrue(executor.awaitIdle(Duration.ofSeconds(10)));
    }

    @Test
    @DisplayName("启动恢复把遗留 RUNNING 置为失败，等待人工重试")
    void recoversInterruptedOnStartup() {
        publishPlan();
        store.start(PLAN, "n1", "执行中");

        assertEquals(1, new DevTaskExecutor(board, run -> "完成", pool).recoverInterrupted());

        DevTask recovered = board.task(PLAN, "n1");
        assertEquals(DevTaskStatus.FAILED, recovered.status());
        assertEquals("task-interrupted", recovered.error());
    }

    private void publishPlan() {
        store.replacePlan(
                PLAN,
                List.of(
                        task("n1", "实现", List.of()),
                        task("n2", "验证", List.of("n1"))));
        board.dispatch(PLAN);
    }

    private static DevTask task(String id, String title, List<String> dependsOn) {
        return DevTask.pending(
                PLAN,
                id,
                title,
                "project-implementer",
                "dev-agent",
                1,
                dependsOn,
                List.of(),
                List.of());
    }
}
