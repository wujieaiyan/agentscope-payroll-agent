package io.agentscope.payroll.sandbox;

import org.springframework.stereotype.Component;

/** 沙箱运行配置；令牌缺失时接口直接拒绝，不提供匿名执行。 */
@Component
public class SandboxSettings {

    private final String token;
    private final String dockerBinary;
    private final String scriptImage;
    private final int maxConcurrency;
    private final String workDir;

    public SandboxSettings() {
        this(
                env("PAYROLL_SANDBOX_TOKEN", ""),
                env("PAYROLL_SANDBOX_DOCKER", "docker"),
                env("PAYROLL_SANDBOX_SCRIPT_IMAGE", "payroll-sandbox-runtime:local"),
                intEnv("PAYROLL_SANDBOX_MAX_CONCURRENCY", 4),
                env("PAYROLL_SANDBOX_WORK_DIR", ""));
    }

    public SandboxSettings(
            String token,
            String dockerBinary,
            String scriptImage,
            int maxConcurrency,
            String workDir) {
        this.token = token == null ? "" : token.trim();
        this.dockerBinary =
                dockerBinary == null || dockerBinary.isBlank() ? "docker" : dockerBinary.trim();
        this.scriptImage =
                scriptImage == null || scriptImage.isBlank()
                        ? "payroll-sandbox-runtime:local"
                        : scriptImage.trim();
        this.maxConcurrency = Math.max(1, maxConcurrency);
        this.workDir = workDir == null ? "" : workDir.trim();
    }

    public boolean configured() {
        return !token.isBlank();
    }

    public String token() {
        return token;
    }

    public String dockerBinary() {
        return dockerBinary;
    }

    public String scriptImage() {
        return scriptImage;
    }

    public int maxConcurrency() {
        return maxConcurrency;
    }

    /**
     * 临时脚本目录；容器内经 docker.sock 启动兄弟容器时，必须指向与宿主机相同的绝对路径，
     * 否则宿主机 Docker 看不到 bind 源目录。留空时使用 JVM 临时目录（本机直跑场景）。
     */
    public String workDir() {
        return workDir;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
