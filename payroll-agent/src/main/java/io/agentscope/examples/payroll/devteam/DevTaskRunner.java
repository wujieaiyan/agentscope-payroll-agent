package io.agentscope.examples.payroll.devteam;

/**
 * 研发任务运行器（P1-2）：真正干活的一步，执行器只负责状态机与并发。
 *
 * <p>运行器可阻塞；取消通过 {@link DevTaskRun#checkpoint()} 与
 * {@link DevTaskRun#onCancel(Runnable)} 协作完成。运行时创建的工作区由运行器自行释放。
 */
@FunctionalInterface
public interface DevTaskRunner {

    /** 执行任务并返回一句话结果摘要；异常由执行器落为 FAILED。 */
    String run(DevTaskRun run) throws Exception;
}
