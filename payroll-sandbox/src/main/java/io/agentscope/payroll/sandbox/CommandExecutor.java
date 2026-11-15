package io.agentscope.payroll.sandbox;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/** 进程调用抽象；超时后由实现销毁本地 docker CLI，容器由调用方再执行 kill。 */
public interface CommandExecutor {

    Outcome exec(List<String> command, Duration timeout) throws IOException, InterruptedException;

    record Outcome(
            int exitCode,
            String stdout,
            String stderr,
            boolean timedOut,
            boolean stdoutTruncated,
            boolean stderrTruncated) {
    }
}
