package io.agentscope.payroll.sandbox;

import io.agentscope.examples.payroll.sandbox.SkillInitProtocol;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.stereotype.Component;

/** 沙箱运行配置；令牌缺失时接口直接拒绝，不提供匿名执行。 */
@Component
public class SandboxSettings {

    private final String token;
    private final String dockerBinary;
    private final String scriptImage;
    private final int maxConcurrency;
    private final String workDir;
    private final String skillRoot;
    private final String initImage;
    private final int initTimeoutMs;
    private final long initMaxBytes;
    private final int initConcurrency;

    public SandboxSettings() {
        this(
                env("PAYROLL_SANDBOX_TOKEN", ""),
                env("PAYROLL_SANDBOX_DOCKER", "docker"),
                env("PAYROLL_SANDBOX_SCRIPT_IMAGE", "payroll-sandbox-runtime:local"),
                intEnv("PAYROLL_SANDBOX_MAX_CONCURRENCY", 4),
                env("PAYROLL_SANDBOX_WORK_DIR", ""),
                env("PAYROLL_SANDBOX_SKILL_ROOT", ""),
                env("PAYROLL_SANDBOX_INIT_IMAGE", "payroll-sandbox-init:local"),
                intEnv("PAYROLL_SANDBOX_INIT_TIMEOUT_MS", SkillInitProtocol.DEFAULT_TIMEOUT_MS),
                longEnv("PAYROLL_SANDBOX_INIT_MAX_BYTES", SkillInitProtocol.DEFAULT_MAX_BYTES),
                intEnv("PAYROLL_SANDBOX_INIT_CONCURRENCY", 2));
    }

    public SandboxSettings(
            String token,
            String dockerBinary,
            String scriptImage,
            int maxConcurrency,
            String workDir) {
        this(
                token,
                dockerBinary,
                scriptImage,
                maxConcurrency,
                workDir,
                "",
                "payroll-sandbox-init:local",
                SkillInitProtocol.DEFAULT_TIMEOUT_MS,
                SkillInitProtocol.DEFAULT_MAX_BYTES,
                2);
    }

    public SandboxSettings(
            String token,
            String dockerBinary,
            String scriptImage,
            int maxConcurrency,
            String workDir,
            String skillRoot,
            String initImage,
            int initTimeoutMs,
            long initMaxBytes,
            int initConcurrency) {
        this.token = token == null ? "" : token.trim();
        this.dockerBinary =
                dockerBinary == null || dockerBinary.isBlank() ? "docker" : dockerBinary.trim();
        this.scriptImage =
                scriptImage == null || scriptImage.isBlank()
                        ? "payroll-sandbox-runtime:local"
                        : scriptImage.trim();
        this.maxConcurrency = Math.max(1, maxConcurrency);
        this.workDir = workDir == null ? "" : workDir.trim();
        this.skillRoot = resolveSkillRoot(skillRoot, this.workDir);
        this.initImage =
                initImage == null || initImage.isBlank()
                        ? "payroll-sandbox-init:local"
                        : initImage.trim();
        this.initTimeoutMs =
                Math.min(
                        Math.max(1, initTimeoutMs),
                        SkillInitProtocol.HARD_TIMEOUT_MS);
        this.initMaxBytes =
                Math.min(
                        initMaxBytes <= 0
                                ? SkillInitProtocol.DEFAULT_MAX_BYTES
                                : initMaxBytes,
                        SkillInitProtocol.HARD_MAX_BYTES);
        this.initConcurrency = Math.max(1, initConcurrency);
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

    /** 技能工作区根目录：容器挂载要求绝对路径，默认放在临时目录或 workDir 下。 */
    public String skillRoot() {
        return skillRoot;
    }

    public String initImage() {
        return initImage;
    }

    public int initTimeoutMs() {
        return initTimeoutMs;
    }

    public long initMaxBytes() {
        return initMaxBytes;
    }

    public int initConcurrency() {
        return initConcurrency;
    }

    /** 技能工作区目录；ID 需先经协议校验。 */
    public Path skillDir(String skillId) {
        Path root = Paths.get(skillRoot).toAbsolutePath().normalize();
        Path dir = root.resolve(skillId).normalize();
        if (!dir.startsWith(root)) {
            throw new IllegalArgumentException("非法技能工作区：" + skillId);
        }
        return dir;
    }

    /** 技能数据目录，初始化后只读挂载到执行容器。 */
    public Path skillDataDir(String skillId) {
        return skillDir(skillId).resolve("data");
    }

    private static String resolveSkillRoot(String configured, String workDir) {
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured.trim()).toAbsolutePath().normalize().toString();
        }
        if (!workDir.isBlank()) {
            return Paths.get(workDir).toAbsolutePath().normalize().resolve("skills").toString();
        }
        Path fallback =
                Paths.get(System.getProperty("java.io.tmpdir"), "payroll-sandbox-skills");
        return fallback.toAbsolutePath().normalize().toString();
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

    private static long longEnv(String name, long fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
