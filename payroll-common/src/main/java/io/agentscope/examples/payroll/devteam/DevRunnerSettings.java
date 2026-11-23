package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.domain.PayrollException;

/**
 * 受限 runner 配置（P1-3）：构建校验一律在容器内执行，网络关闭并限制内存、CPU 与进程数。
 *
 * <p>默认关闭：未启用时不执行任何容器命令；若路径 profile 声明了校验命令却未启用 runner，
 * 任务按配置不一致失败，而不是把「未校验」当成通过。缓存目录为空表示不挂载。
 *
 * @param enabled 是否启用容器内构建校验
 * @param dockerBinary docker CLI 可执行文件
 * @param defaultImage 校验命令未声明镜像时使用的基础镜像；为空表示必须逐条声明
 * @param memoryMb 单容器内存上限（MB）
 * @param cpus 单容器 CPU 上限
 * @param pidsLimit 单容器进程数上限
 * @param mavenCacheDir 宿主机 Maven 缓存目录，挂载到 /root/.m2；空串表示不挂载
 * @param npmCacheDir 宿主机 npm 缓存目录，挂载到 /root/.npm；空串表示不挂载
 * @param maxOutputChars 单条命令保留的输出字符数上限
 */
public record DevRunnerSettings(
        boolean enabled,
        String dockerBinary,
        String defaultImage,
        long memoryMb,
        double cpus,
        long pidsLimit,
        String mavenCacheDir,
        String npmCacheDir,
        int maxOutputChars) {

    public static final long DEFAULT_MEMORY_MB = 2048;
    public static final double DEFAULT_CPUS = 2D;
    public static final long DEFAULT_PIDS_LIMIT = 512;
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 8_000;
    public static final int MIN_MEMORY_MB = 256;
    public static final int MIN_MAX_OUTPUT_CHARS = 1_000;
    public static final int MAX_MAX_OUTPUT_CHARS = 100_000;

    public DevRunnerSettings {
        dockerBinary =
                dockerBinary == null || dockerBinary.isBlank() ? "docker" : dockerBinary.trim();
        defaultImage = defaultImage == null ? "" : defaultImage.trim();
        memoryMb = memoryMb <= 0 ? DEFAULT_MEMORY_MB : memoryMb;
        if (memoryMb < MIN_MEMORY_MB) {
            throw new PayrollException("受限 runner 内存上限不能低于 " + MIN_MEMORY_MB + "MB");
        }
        cpus = cpus <= 0D ? DEFAULT_CPUS : cpus;
        pidsLimit = pidsLimit <= 0 ? DEFAULT_PIDS_LIMIT : pidsLimit;
        mavenCacheDir = mavenCacheDir == null ? "" : mavenCacheDir.trim();
        npmCacheDir = npmCacheDir == null ? "" : npmCacheDir.trim();
        maxOutputChars =
                maxOutputChars <= 0
                        ? DEFAULT_MAX_OUTPUT_CHARS
                        : Math.max(
                                MIN_MAX_OUTPUT_CHARS,
                                Math.min(maxOutputChars, MAX_MAX_OUTPUT_CHARS));
    }

    /** 关闭状态：不执行任何命令，也不要求 docker 与镜像可用。 */
    public static DevRunnerSettings disabled() {
        return new DevRunnerSettings(false, "docker", "", 0, 0D, 0, "", "", 0);
    }

    public boolean hasMavenCache() {
        return !mavenCacheDir.isBlank();
    }

    public boolean hasNpmCache() {
        return !npmCacheDir.isBlank();
    }
}
