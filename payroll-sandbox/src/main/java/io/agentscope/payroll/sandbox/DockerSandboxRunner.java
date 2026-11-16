package io.agentscope.payroll.sandbox;

import io.agentscope.examples.payroll.sandbox.SandboxProtocol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * 一次性容器执行：每次调用新建容器，只读挂载当前技能脚本目录，禁网、非 root、限额运行。
 *
 * <p>容器名固定前缀加随机后缀，超时后按名称强制 kill，避免 CLI 被杀而容器残留。
 */
@Component
public class DockerSandboxRunner implements SandboxRunner {

    private static final String NAME_PREFIX = "payroll-sandbox-";
    private static final int DOCKER_ERROR_EXIT = 125;
    private static final int KILLED_EXIT = 137;
    private static final Duration GRACE = Duration.ofSeconds(2);
    private static final int STDERR_MESSAGE_MAX = 200;

    private final SandboxSettings settings;
    private final CommandExecutor executor;

    public DockerSandboxRunner(SandboxSettings settings, CommandExecutor executor) {
        this.settings = settings;
        this.executor = executor;
    }

    @Override
    public SandboxResult run(SandboxRequest request, String scriptSha256) {
        int timeoutMs =
                SandboxProtocol.requireTimeout(
                        request.timeoutMs() == null ? 0 : request.timeoutMs());
        Path root;
        try {
            root = createWorkDir();
        } catch (IOException e) {
            return SandboxResult.unavailable("无法创建沙箱临时目录");
        }
        String containerName = NAME_PREFIX + UUID.randomUUID();
        long startedAt = System.nanoTime();
        try {
            Path src = root.resolve("src");
            writeScripts(src, request.scripts());
            CommandExecutor.Outcome outcome =
                    executor.exec(
                            command(containerName, src, request),
                            Duration.ofMillis(timeoutMs + GRACE.toMillis()));
            long durationMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            if (outcome.timedOut()) {
                killContainer(containerName);
                return SandboxResult.timeout(scriptSha256, durationMs);
            }
            if (outcome.exitCode() == DOCKER_ERROR_EXIT) {
                return SandboxResult.error("沙箱容器启动失败：" + firstLine(outcome.stderr()));
            }
            String message =
                    outcome.exitCode() == KILLED_EXIT ? "容器被强制终止（可能超出资源限额）" : null;
            return new SandboxResult(
                    SandboxResult.OK,
                    outcome.exitCode(),
                    outcome.stdout(),
                    outcome.stderr(),
                    durationMs,
                    scriptSha256,
                    outcome.stdoutTruncated() || outcome.stderrTruncated(),
                    message);
        } catch (IllegalArgumentException e) {
            return SandboxResult.invalid(e.getMessage());
        } catch (IOException e) {
            return SandboxResult.unavailable("沙箱调用失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killContainer(containerName);
            return SandboxResult.unavailable("执行被中断");
        } finally {
            deleteQuietly(root);
        }
    }

    private Path createWorkDir() throws IOException {
        if (settings.workDir().isEmpty()) {
            return Files.createTempDirectory("payroll-sandbox-");
        }
        Path base = Paths.get(settings.workDir()).toAbsolutePath().normalize();
        Files.createDirectories(base);
        return Files.createTempDirectory(base, "run-");
    }

    private static void writeScripts(Path src, List<SandboxRequest.ScriptFile> scripts)
            throws IOException {
        if (scripts == null || scripts.isEmpty()) {
            throw new IllegalArgumentException("请求缺少脚本包");
        }
        Files.createDirectories(src);
        makeReadable(src, true);
        int total = 0;
        for (SandboxRequest.ScriptFile script : scripts) {
            String path = SandboxProtocol.requireScriptPath(script.path());
            byte[] bytes =
                    (script.content() == null ? "" : script.content())
                            .getBytes(StandardCharsets.UTF_8);
            if (bytes.length > SandboxProtocol.MAX_SCRIPT_BYTES) {
                throw new IllegalArgumentException(
                        "脚本超过 " + SandboxProtocol.MAX_SCRIPT_BYTES + " 字节：" + path);
            }
            total += bytes.length;
            if (total > SandboxProtocol.MAX_SCRIPTS_BYTES) {
                throw new IllegalArgumentException(
                        "脚本总量超过 " + SandboxProtocol.MAX_SCRIPTS_BYTES + " 字节");
            }
            Path target = src.resolve(path).normalize();
            if (!target.startsWith(src)) {
                throw new IllegalArgumentException("脚本路径越界：" + path);
            }
            Files.createDirectories(target.getParent());
            makeReadableChain(src, target.getParent());
            Files.write(
                    target,
                    bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            makeReadable(target, false);
        }
    }

    private List<String> command(String containerName, Path src, SandboxRequest request) {
        List<String> command = new ArrayList<>();
        command.add(settings.dockerBinary());
        command.addAll(
                List.of(
                        "run",
                        "--rm",
                        "--name",
                        containerName,
                        "--network",
                        "none",
                        "--read-only",
                        "--tmpfs",
                        "/work:rw,noexec,nosuid,nodev,size=16m",
                        "--memory",
                        "256m",
                        "--memory-swap",
                        "256m",
                        "--cpus",
                        "1",
                        "--pids-limit",
                        "64",
                        "--user",
                        "65534:65534",
                        "--cap-drop",
                        "ALL",
                        "--security-opt",
                        "no-new-privileges",
                        "--mount",
                        "type=bind,source=" + src.toAbsolutePath() + ",target=/src,readonly"));
        Path dataDir = dataDir(request.skillId());
        if (dataDir != null) {
            command.add("--mount");
            command.add(
                    "type=bind,source="
                            + dataDir.toAbsolutePath()
                            + ",target="
                            + SandboxProtocol.DATA_MOUNT
                            + ",readonly");
        }
        command.addAll(List.of("-w", "/work", settings.scriptImage()));
        command.addAll(SandboxProtocol.interpreter(request.scriptPath()));
        command.add("/src/" + request.scriptPath());
        command.addAll(SandboxProtocol.requireArgs(request.args()));
        return command;
    }

    /** 技能数据目录存在才挂载；路径由已校验的技能 ID 推导，不接受请求传入。 */
    private Path dataDir(String skillId) {
        String id = SandboxProtocol.requireSkillId(skillId);
        Path dir = settings.skillDataDir(id);
        return Files.isDirectory(dir) ? dir : null;
    }

    private void killContainer(String containerName) {
        try {
            executor.exec(
                    List.of(settings.dockerBinary(), "kill", containerName),
                    Duration.ofSeconds(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // 容器可能已被 --rm 回收，忽略
        }
    }

    private static void makeReadableChain(Path src, Path directory) {
        Path current = directory;
        while (current != null && current.startsWith(src)) {
            makeReadable(current, true);
            if (current.equals(src)) {
                break;
            }
            current = current.getParent();
        }
    }

    private static void makeReadable(Path path, boolean directory) {
        try {
            Files.setPosixFilePermissions(
                    path,
                    PosixFilePermissions.fromString(
                            directory ? "rwxr-xr-x" : "r--r--r--"));
        } catch (IOException | UnsupportedOperationException e) {
            // Windows 等非 POSIX 文件系统不支持，本机直跑时容器仍可读取
        }
    }

    private static String firstLine(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "未知错误";
        }
        String line = stderr.strip().lines().findFirst().orElse("未知错误");
        return line.length() > STDERR_MESSAGE_MAX
                ? line.substring(0, STDERR_MESSAGE_MAX) + "…"
                : line;
    }

    private static void deleteQuietly(Path root) {
        if (root == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                    // 容器异常退出时文件可能仍被占用，留给系统回收
                                }
                            });
        } catch (IOException ignored) {
            // 目录已不存在
        }
    }
}
