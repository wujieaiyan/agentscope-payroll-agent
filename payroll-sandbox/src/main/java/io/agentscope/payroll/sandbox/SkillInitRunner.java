package io.agentscope.payroll.sandbox;

import java.nio.file.Path;

/** 初始化执行抽象：工作区准备由调用方完成，实现只负责起停容器与回收日志。 */
public interface SkillInitRunner {

    Outcome run(
            String setupPath,
            Path packageDir,
            Path dataDir,
            long timeoutMs,
            long maxBytes);

    record Outcome(
            int exitCode,
            boolean timedOut,
            boolean sizeExceeded,
            long sizeBytes,
            String logTail,
            String message) {
    }
}
