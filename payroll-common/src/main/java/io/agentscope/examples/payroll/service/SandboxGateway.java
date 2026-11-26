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

    /**
     * 携带“是否依赖初始化数据”的执行入口；实现方需把该标志传给沙箱，
     * 未实现 7 参重载时回落到 6 参版本（不检查数据状态）。
     */
    default Execution execute(
            long userId,
            String skillId,
            String scriptPath,
            List<String> args,
            int timeoutMs,
            List<Script> scripts,
            boolean requireData) {
        return execute(userId, skillId, scriptPath, args, timeoutMs, scripts);
    }

    /** 异步启动技能初始化；返回提交后的工作区状态（通常为 INITIALIZING）。 */
    SkillData initSkill(InitRequest request);

    SkillData skillData(String skillId);

    SkillData deleteSkillData(String skillId);

    /**
     * 读取技能数据目录树；未实现该能力的客户端抛 {@link UnsupportedOperationException}，
     * 由 Web 层翻译成明确错误，避免静默返回空树。
     */
    default DataTree dataTree(String skillId, String path, int depth) {
        throw new UnsupportedOperationException("当前沙箱客户端不支持技能数据浏览");
    }

    /** 读取技能数据中的单个文本文件（按 maxBytes 截断）；未实现时同上。 */
    default DataFile dataFile(String skillId, String path, int maxBytes) {
        throw new UnsupportedOperationException("当前沙箱客户端不支持技能数据浏览");
    }

    record DataEntry(
            String name, String path, String type, long size, long modifiedAt, Boolean isText) {
    }

    record DataTree(
            String skillId,
            String path,
            List<DataEntry> entries,
            boolean truncated,
            int entryLimit) {
    }

    record DataFile(
            String path,
            long size,
            long modifiedAt,
            boolean isText,
            String encoding,
            boolean truncated,
            String content,
            String mime) {
    }

    record Script(String path, String content) {
    }

    record InitRequest(
            String skillId,
            String setupPath,
            String setupContent,
            List<Script> files,
            Integer timeoutMs,
            Long maxBytes) {
    }

    record SkillData(
            String status,
            String skillId,
            Integer exitCode,
            long startedAt,
            long finishedAt,
            long sizeBytes,
            String logTail,
            String message) {

        public static final String INITIALIZING = "INITIALIZING";
        public static final String READY = "READY";
        public static final String FAILED = "FAILED";
        public static final String UNKNOWN = "UNKNOWN";
        public static final String BUSY = "BUSY";
        public static final String UNAVAILABLE = "UNAVAILABLE";
        public static final String INVALID = "INVALID";

        public boolean ready() {
            return READY.equals(status);
        }

        public boolean initialized() {
            return READY.equals(status) || INITIALIZING.equals(status);
        }
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
        /** 技能声明了 setup，但数据未初始化或初始化失败。 */
        public static final String DATA_NOT_READY = "DATA_NOT_READY";

        public boolean succeeded() {
            return OK.equals(status) && exitCode == 0;
        }
    }
}
