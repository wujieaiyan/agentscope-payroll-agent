package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.db.DevTaskStore;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.team.TeamPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目经理任务面板（P0-7）：发布 tasks.json 对应的任务行、按依赖推进状态、跟踪负责人。
 *
 * <p>分发是幂等的：每次状态变化后重新计算待开始/阻塞任务，依赖全部完成则提升为
 * 待执行，依赖失败或缺失则标记阻塞。状态流转非法或依赖未完成时直接拒绝执行。
 */
public final class DevTaskBoard {

    private static final String DEPENDENCY_FAILURE = "依赖任务失败或缺失：";

    private final DevTaskStore store;

    public DevTaskBoard(DevTaskStore store) {
        if (store == null) {
            throw new IllegalArgumentException("DevTaskStore 不能为空");
        }
        this.store = store;
    }

    /** 发布/重发计划：先整体替换任务行，再把无依赖任务提升为待执行。 */
    public List<DevTask> publish(TeamPlan plan) {
        store.replacePlan(plan.planId(), DevTaskPlanner.plan(plan));
        return dispatch(plan.planId());
    }

    public List<DevTask> tasks(String planId) {
        return store.list(planId);
    }

    /** 读取单个任务；不存在时直接拒绝，供任务中心与执行器复用。 */
    public DevTask task(String planId, String taskId) {
        return requireTask(planId, taskId);
    }

    /** 同包执行器需要写入执行时间与失败原因，面板仍独占状态流转校验。 */
    DevTaskStore store() {
        return store;
    }

    public List<DevTask> assign(String planId, String taskId, String ownerAgent) {
        DevTask task = requireTask(planId, taskId);
        if (task.status().terminal()) {
            throw new PayrollException("任务已结束，不能再改派：" + taskId);
        }
        store.assign(planId, taskId, ownerAgent);
        return store.list(planId);
    }

    /** 更新任务状态并重新计算依赖就绪情况；返回面板最新快照。 */
    public List<DevTask> update(
            String planId, String taskId, DevTaskStatus target, String note) {
        DevTask task = requireTask(planId, taskId);
        validateTransition(task, target);
        if (target == DevTaskStatus.RUNNING || target == DevTaskStatus.DONE) {
            List<String> pending = blockedBy(planId, taskId);
            if (!pending.isEmpty()) {
                throw new PayrollException(
                        "任务依赖未完成，不能进入 " + target.wireName() + "：" + String.join(",", pending));
            }
        }
        store.updateStatus(planId, taskId, target, note);
        return dispatch(planId);
    }

    /**
     * 人工重试（P1-2）：失败、阻塞或已取消的任务重新入队，重试次数 +1。
     *
     * <p>重试不改依赖，只把任务置回 READY 前先清空上次执行痕迹；若依赖仍未满足，
     * 由随后的 {@link #dispatch(String)} 重新落回阻塞/等待状态。
     */
    public List<DevTask> retry(String planId, String taskId) {
        DevTask task = requireTask(planId, taskId);
        DevTaskStatus status = task.status();
        if (status != DevTaskStatus.FAILED
                && status != DevTaskStatus.BLOCKED
                && status != DevTaskStatus.CANCELLED) {
            throw new PayrollException(
                    "仅失败、阻塞或已取消的任务可以重试：当前=" + status.wireName());
        }
        store.retry(planId, taskId, "人工重试");
        Map<String, DevTask> byId = index(store.list(planId));
        DevTask retried = byId.get(taskId);
        DependencyState state = dependencyState(retried, byId);
        if (state == DependencyState.BROKEN) {
            store.updateStatus(planId, taskId, DevTaskStatus.BLOCKED, state.reason(retried, byId));
        } else if (state == DependencyState.WAITING) {
            store.updateStatus(planId, taskId, DevTaskStatus.PENDING, "等待依赖重试");
        }
        return dispatch(planId);
    }

    /** 依赖中尚未完成的任务 id；依赖缺失也算未完成。 */
    public List<String> blockedBy(String planId, String taskId) {
        DevTask task = requireTask(planId, taskId);
        Map<String, DevTask> byId = index(store.list(planId));
        List<String> blocked = new ArrayList<>();
        for (String dependency : task.dependsOn()) {
            DevTask source = byId.get(dependency);
            if (source == null || source.status() != DevTaskStatus.DONE) {
                blocked.add(dependency);
            }
        }
        return List.copyOf(blocked);
    }

    /** 重新计算待开始与阻塞任务：依赖完成→待执行，依赖失败/缺失→阻塞。 */
    public List<DevTask> dispatch(String planId) {
        List<DevTask> tasks = store.list(planId);
        Map<String, DevTask> byId = index(tasks);
        for (DevTask task : tasks) {
            if (task.status() != DevTaskStatus.PENDING && task.status() != DevTaskStatus.BLOCKED) {
                continue;
            }
            DependencyState state = dependencyState(task, byId);
            if (state == DependencyState.READY) {
                if (task.status() == DevTaskStatus.PENDING) {
                    store.updateStatus(planId, task.taskId(), DevTaskStatus.READY, "依赖已就绪");
                } else {
                    store.updateStatus(planId, task.taskId(), DevTaskStatus.READY, "依赖已恢复");
                }
            } else if (state == DependencyState.BROKEN && task.status() == DevTaskStatus.PENDING) {
                store.updateStatus(
                        planId, task.taskId(), DevTaskStatus.BLOCKED, state.reason(task, byId));
            } else if (state == DependencyState.WAITING
                    && task.status() == DevTaskStatus.BLOCKED
                    && task.note().startsWith(DEPENDENCY_FAILURE)) {
                store.updateStatus(planId, task.taskId(), DevTaskStatus.PENDING, "等待依赖重试");
            }
        }
        return store.list(planId);
    }

    private DependencyState dependencyState(DevTask task, Map<String, DevTask> byId) {
        boolean waiting = false;
        boolean broken = false;
        for (String dependency : task.dependsOn()) {
            DevTask source = byId.get(dependency);
            if (source == null || source.status().failure() || source.status() == DevTaskStatus.CANCELLED) {
                broken = true;
            } else if (source.status() != DevTaskStatus.DONE) {
                waiting = true;
            }
        }
        if (broken) {
            return DependencyState.BROKEN;
        }
        return waiting ? DependencyState.WAITING : DependencyState.READY;
    }

    private static Map<String, DevTask> index(List<DevTask> tasks) {
        Map<String, DevTask> byId = new LinkedHashMap<>();
        for (DevTask task : tasks) {
            byId.put(task.taskId(), task);
        }
        return byId;
    }

    private DevTask requireTask(String planId, String taskId) {
        return store.find(planId, taskId)
                .orElseThrow(() -> new PayrollException("任务不存在：" + taskId));
    }

    private static void validateTransition(DevTask task, DevTaskStatus target) {
        if (target == null) {
            throw new PayrollException("目标状态不能为空");
        }
        DevTaskStatus current = task.status();
        if (current == target) {
            return;
        }
        boolean allowed =
                switch (current) {
                    case PENDING ->
                            target == DevTaskStatus.READY
                                    || target == DevTaskStatus.BLOCKED
                                    || target == DevTaskStatus.CANCELLED;
                    case READY ->
                            target == DevTaskStatus.RUNNING
                                    || target == DevTaskStatus.BLOCKED
                                    || target == DevTaskStatus.CANCELLED;
                    case RUNNING ->
                            target == DevTaskStatus.DONE
                                    || target == DevTaskStatus.FAILED
                                    || target == DevTaskStatus.BLOCKED
                                    || target == DevTaskStatus.CANCELLED;
                    case BLOCKED, FAILED ->
                            target == DevTaskStatus.READY || target == DevTaskStatus.CANCELLED;
                    case DONE, CANCELLED -> false;
                };
        if (!allowed) {
            throw new PayrollException(
                    "非法任务状态流转：" + current.wireName() + " -> " + target.wireName());
        }
    }

    private enum DependencyState {
        READY,
        WAITING,
        BROKEN;

        String reason(DevTask task, Map<String, DevTask> byId) {
            List<String> broken = new ArrayList<>();
            for (String dependency : task.dependsOn()) {
                DevTask source = byId.get(dependency);
                if (source == null || source.status().failure() || source.status() == DevTaskStatus.CANCELLED) {
                    broken.add(dependency);
                }
            }
            return DEPENDENCY_FAILURE + String.join(",", broken);
        }
    }
}
