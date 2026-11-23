package io.agentscope.examples.payroll.devteam;

import java.nio.file.Path;
import java.util.List;

/**
 * 构建校验端口（P1-3）：在隔离工作区上执行路径 profile 声明的校验命令。
 *
 * <p>返回空列表表示该角色未声明校验命令；任一条失败直接抛出 {@link
 * io.agentscope.examples.payroll.domain.PayrollException}，由任务执行器置 FAILED，
 * 不允许把失败或未执行当成通过。
 */
@FunctionalInterface
public interface DevBuildRunner {

    List<DevBuildResult> run(String role, Path workspaceRoot);
}
