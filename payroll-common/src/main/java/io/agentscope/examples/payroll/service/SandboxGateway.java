package io.agentscope.examples.payroll.service;

import java.util.List;

/** 沙箱执行抽象：工具侧只提交脚本路径与参数，不接触 docker 或进程。 */
public interface SandboxGateway {

    boolean enabled();

    Execution execute(
            long userId,
            String skillId,
            String scriptPath,
            List<String> args,
            int timeoutMs,
            List<Script> scripts);

    record Script(String path, String content) {
    }

    record Execution(
            String status,
            int exitCode,
            String stdout,
            String stderr,
            long durationMs,
            String scriptSha256,
            boolean truncated,
            String message) {

        public static final String OK = "OK";
        public static final String TIMEOUT = "TIMEOUT";
        public static final String ERROR = "ERROR";
        public static final String BUSY = "BUSY";
        public static final String UNAVAILABLE = "UNAVAILABLE";
        public static final String INVALID = "INVALID";

        public boolean succeeded() {
            return OK.equals(status) && exitCode == 0;
        }
    }
}
