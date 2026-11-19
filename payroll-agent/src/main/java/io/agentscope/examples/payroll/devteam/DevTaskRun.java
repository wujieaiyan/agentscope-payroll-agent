package io.agentscope.examples.payroll.devteam;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一次研发任务执行（P1-2）：携带任务快照与取消信号，供运行器中途自检。
 *
 * <p>取消由执行器触发：先置位并回调运行器注册的取消钩子（例如中断模型调用），
 * 运行器在耗时步骤前后调用 {@link #checkpoint()}，以异常方式尽快退出。
 */
public final class DevTaskRun {

    private static final Logger log = LoggerFactory.getLogger(DevTaskRun.class);

    private final String planId;
    private final DevTask task;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<Runnable> cancelHooks = new CopyOnWriteArrayList<>();

    public DevTaskRun(String planId, DevTask task) {
        if (planId == null || planId.isBlank()) {
            throw new IllegalArgumentException("任务执行缺少计划 ID");
        }
        if (task == null) {
            throw new IllegalArgumentException("任务执行缺少任务定义");
        }
        this.planId = planId.trim();
        this.task = task;
    }

    public String planId() {
        return planId;
    }

    public DevTask task() {
        return task;
    }

    public boolean cancelled() {
        return cancelled.get();
    }

    /** 注册取消回调；重复注册按调用顺序各执行一次。 */
    public void onCancel(Runnable hook) {
        if (hook != null) {
            cancelHooks.add(hook);
        }
    }

    /** 在耗时步骤前后调用；已取消时抛出 {@link CancelledException} 让执行器收敛到 CANCELLED。 */
    public void checkpoint() {
        if (cancelled()) {
            throw new CancelledException(task.taskId());
        }
    }

    /** 仅执行器调用：置位取消并触发钩子，钩子失败不阻断其他钩子。 */
    void requestCancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        for (Runnable hook : cancelHooks) {
            try {
                hook.run();
            } catch (RuntimeException e) {
                log.warn(
                        "[开发域] 任务取消钩子执行失败：plan={}，task={}，原因={}",
                        planId,
                        task.taskId(),
                        e.getMessage());
            }
        }
    }

    /** 运行器用于提前退出的受控异常；执行器据此落 CANCELLED 而非 FAILED。 */
    public static final class CancelledException extends RuntimeException {

        public CancelledException(String taskId) {
            super("任务已取消：" + taskId);
        }
    }
}
