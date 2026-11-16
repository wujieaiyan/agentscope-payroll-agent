package io.agentscope.payroll.sandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** 进程调用抽象；超时后由实现销毁本地 docker CLI，容器由调用方再执行 kill。 */
public interface CommandExecutor {

    Outcome exec(List<String> command, Duration timeout) throws IOException, InterruptedException;

    /**
     * 合并标准输出与错误写入文件并只回读末尾 8KB；初始化输出可能很长，
     * 失败原因通常在尾部而不是开头。默认实现忽略文件，保持旧执行器兼容。
     */
    default Outcome exec(List<String> command, Duration timeout, Path logFile)
            throws IOException, InterruptedException {
        return exec(command, timeout);
    }

    record Outcome(
            int exitCode,
            String stdout,
            String stderr,
            boolean timedOut,
            boolean stdoutTruncated,
            boolean stderrTruncated) {
    }
}
