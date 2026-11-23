package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.db.DevTaskStore;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.AuditService;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 研发任务中心（P1-2）：脱离聊天轮次的异步执行、取消、人工重试与启动恢复。
 *
 * <p>状态机沿用 {@link DevTaskBoard} 的约束：仅 READY 任务可提交，执行中置 RUNNING，
 * 运行器返回后落 DONE/FAILED/CANCELLED 并触发依赖重算。取消对运行中任务先置信号再等
 * 运行器自行收敛；对排队中的任务直接落 CANCELLED。上一实例遗留的 RUNNING 行在启动时
 * 统一置失败，等待人工重试，绝不自动续跑。
 */
public final class DevTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(DevTaskExecutor.class);
    private static final String RECOVER_NOTE = "实例重启，任务中断，请人工重试";
    private static final String RECOVER_ERROR = "task-interrupted";

    private final DevTaskBoard board;
    private final DevTaskStore store;
    private final DevTaskRunner runner;
    private final ExecutorService executor;
    private final AuditService audit;
    private final Map<String, DevTaskRun> active = new ConcurrentHashMap<>();

    public DevTaskExecutor(
            DevTaskBoard board, DevTaskRunner runner, ExecutorService executor) {
        this(board, runner, executor, null);
    }

    public DevTaskExecutor(
            DevTaskBoard board,
            DevTaskRunner runner,
            ExecutorService executor,
            AuditService audit) {
        if (board == null) {
            throw new IllegalArgumentException("DevTaskBoard 不能为空");
        }
        if (runner == null) {
            throw new IllegalArgumentException("DevTaskRunner 不能为空");
        }
        if (executor == null) {
            throw new IllegalArgumentException("执行线程池不能为空");
        }
        this.board = board;
        this.store = board.store();
        this.runner = runner;
        this.executor = executor;
        this.audit = audit;
    }

    /** 提交 READY 任务；同一任务同一时刻只允许一个实例执行。 */
    public DevTask submit(String planId, String taskId) {
        DevTask task = board.task(planId, taskId);
        String key = key(planId, taskId);
        if (active.containsKey(key)) {
            throw new PayrollConflictException("任务已在执行中：" + taskId);
        }
        if (task.status() != DevTaskStatus.READY) {
            throw new PayrollException(
                    "只有依赖就绪的任务可以执行：当前=" + task.status().wireName());
        }
        DevTaskRun run = new DevTaskRun(planId, task);
        if (active.putIfAbsent(key, run) != null) {
            throw new PayrollConflictException("任务已在执行中：" + taskId);
        }
        try {
            store.start(planId, taskId, "执行中");
            audit("devteam.task.start", planId, taskId, null, taskSnapshot(task));
        } catch (RuntimeException e) {
            active.remove(key, run);
            throw e;
        }
        try {
            executor.submit(() -> execute(run));
        } catch (RejectedExecutionException e) {
            active.remove(key, run);
            finishQuietly(planId, taskId, DevTaskStatus.FAILED, "执行队列已满", "rejected");
            throw new PayrollConflictException("研发任务队列已满，请稍后重试");
        }
        return board.task(planId, taskId);
    }

    /**
     * 取消任务：运行中只投递取消信号，由执行线程落 CANCELLED；
     * 排队/阻塞/失败前的任务直接落 CANCELLED。
     */
    public DevTask cancel(String planId, String taskId) {
        DevTask task = board.task(planId, taskId);
        if (task.status().terminal()) {
            throw new PayrollException("任务已结束，无法取消：" + taskId);
        }
        if (task.status() == DevTaskStatus.RUNNING) {
            DevTaskRun run = active.get(key(planId, taskId));
            if (run != null) {
                run.requestCancel();
            } else {
                // 执行线程已消失但状态未收敛（例如跨实例接管），直接落取消，避免永久 RUNNING。
                finishQuietly(planId, taskId, DevTaskStatus.CANCELLED, "已取消（无执行线程）", "");
            }
            audit("devteam.task.cancel", planId, taskId, task.status().wireName(), "cancelling");
            return board.task(planId, taskId);
        }
        store.finish(planId, taskId, DevTaskStatus.CANCELLED, "已取消", "");
        board.dispatch(planId);
        audit("devteam.task.cancel", planId, taskId, task.status().wireName(), "cancelled");
        return board.task(planId, taskId);
    }

    /** 人工重试：仅失败/阻塞/已取消可重试，重试次数 +1；执行实例不得重复提交。 */
    public List<DevTask> retry(String planId, String taskId) {
        if (active.containsKey(key(planId, taskId))) {
            throw new PayrollConflictException("任务仍在执行，无法重试：" + taskId);
        }
        DevTask before = board.task(planId, taskId);
        List<DevTask> tasks = board.retry(planId, taskId);
        DevTask after = board.task(planId, taskId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("attempt", after.attempt());
        detail.put("status", after.status().wireName());
        audit("devteam.task.retry", planId, taskId, before.status().wireName(), detail);
        return tasks;
    }

    /** 启动恢复：把上一实例遗留的 RUNNING 行置为 FAILED，等待人工重试。 */
    public int recoverInterrupted() {
        int recovered = store.recoverInterrupted(RECOVER_NOTE, RECOVER_ERROR);
        if (recovered > 0) {
            log.warn("[开发域] 启动恢复：{} 个执行中任务已置为失败，等待人工重试", recovered);
            audit("devteam.task.recover", null, "*", null, Map.of("recovered", recovered));
        }
        return recovered;
    }

    /** 当前执行中的任务数，用于配额与运维观测。 */
    public int activeCount() {
        return active.size();
    }

    /** 等待执行中的任务全部收敛；超时返回 false。 */
    public boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + Math.max(0L, timeout.toNanos());
        while (!active.isEmpty()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return active.isEmpty();
            }
        }
        return true;
    }

    private void execute(DevTaskRun run) {
        String planId = run.planId();
        String taskId = run.task().taskId();
        try {
            run.checkpoint();
            String summary = runner.run(run);
            if (run.cancelled()) {
                finishQuietly(planId, taskId, DevTaskStatus.CANCELLED, "已取消", "");
            } else {
                finishQuietly(
                        planId,
                        taskId,
                        DevTaskStatus.DONE,
                        summary == null || summary.isBlank() ? "执行完成" : summary,
                        "");
            }
        } catch (DevTaskRun.CancelledException e) {
            finishQuietly(planId, taskId, DevTaskStatus.CANCELLED, "已取消", "");
        } catch (RuntimeException e) {
            fail(planId, taskId, run, e);
        } catch (Exception e) {
            fail(planId, taskId, run, e);
        } finally {
            active.remove(key(planId, taskId), run);
        }
    }

    private void fail(String planId, String taskId, DevTaskRun run, Exception error) {
        if (run.cancelled() || error instanceof DevTaskRun.CancelledException) {
            finishQuietly(planId, taskId, DevTaskStatus.CANCELLED, "已取消", "");
            return;
        }
        String message =
                error.getMessage() == null || error.getMessage().isBlank()
                        ? error.getClass().getSimpleName()
                        : error.getMessage();
        log.warn(
                "[开发域] 任务执行失败：plan={}，task={}，原因={}",
                planId,
                taskId,
                message);
        finishQuietly(planId, taskId, DevTaskStatus.FAILED, "执行失败：" + message, message);
    }

    /**
     * 收敛终态：仅当任务仍为 RUNNING 时写入，避免与取消/其他实例抢占后产生非法流转。
     */
    private void finishQuietly(
            String planId, String taskId, DevTaskStatus status, String note, String error) {
        try {
            DevTask current = board.task(planId, taskId);
            if (current.status() != DevTaskStatus.RUNNING) {
                log.debug(
                        "[开发域] 任务状态已收敛，跳过终态写入：task={}，当前={}",
                        taskId,
                        current.status().wireName());
                return;
            }
            store.finish(planId, taskId, status, note, error);
            board.dispatch(planId);
            audit(
                    "devteam.task." + status.wireName(),
                    planId,
                    taskId,
                    DevTaskStatus.RUNNING.wireName(),
                    Map.of("status", status.wireName(), "note", note));
        } catch (RuntimeException e) {
            log.warn(
                    "[开发域] 写入任务终态失败：plan={}，task={}，status={}，原因={}",
                    planId,
                    taskId,
                    status.wireName(),
                    e.getMessage());
        }
    }

    private void audit(
            String action, String planId, String taskId, String before, Object after) {
        if (audit == null) {
            return;
        }
        try {
            audit.record(
                    action,
                    "dev-task",
                    planId == null ? taskId : planId + "/" + taskId,
                    before,
                    after);
        } catch (RuntimeException e) {
            log.warn("[开发域] 记录任务审计失败：action={}，原因={}", action, e.getMessage());
        }
    }

    private static Map<String, Object> taskSnapshot(DevTask task) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("plan_id", task.planId());
        detail.put("task_id", task.taskId());
        detail.put("role", task.role());
        detail.put("owner", task.ownerAgent());
        detail.put("priority", task.priority());
        detail.put("attempt", task.attempt());
        return detail;
    }

    private static String key(String planId, String taskId) {
        return planId + "|" + taskId;
    }
}
