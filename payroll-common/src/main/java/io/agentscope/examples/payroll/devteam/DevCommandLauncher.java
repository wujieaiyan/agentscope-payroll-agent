package io.agentscope.examples.payroll.devteam;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/** 受限 runner 的命令执行端口；实现必须按 argv 直接执行，不经过 shell。 */
@FunctionalInterface
public interface DevCommandLauncher {

    DevCommandResult execute(List<String> argv, Duration timeout) throws IOException;
}
