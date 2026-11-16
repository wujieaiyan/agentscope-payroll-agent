package io.agentscope.payroll.sandbox;

import io.agentscope.examples.payroll.sandbox.SkillInitProtocol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 一次性初始化容器：允许联网下载数据，工作区可写，其余隔离参数与执行容器一致。
 *
 * <p>每 5 秒统计 {@code data/} 体积，超过技能声明上限立即 kill；超时由 CLI 销毁后
 * 再按容器名强制回收，日志只保留末尾 8KB 供管理台展示。
 */
@Component
public class DockerSkillInitRunner implements SkillInitRunner {

    private static final Logger log = LoggerFactory.getLogger(DockerSkillInitRunner.class);
    private static final String NAME_PREFIX = "payroll-skill-init-";
    private static final int DOCKER_ERROR_EXIT = 125;
    private static final int KILLED_EXIT = 137;
    private static final int PROJECTED_FAILURE_SIGNAL = -1;
    private static final Duration GRACE = Duration.ofSeconds(5);
    private static final Duration WATCHDOG_INTERVAL = Duration.ofSeconds(5);
    private static final int STDERR_MESSAGE_MAX = 200;

    private final SandboxSettings settings;
    private final CommandExecutor executor;

    public DockerSkillInitRunner(SandboxSettings settings, CommandExecutor executor) {
        this.settings = settings;
        this.executor = executor;
    }

    @Override
    public Outcome run(
            String setupPath, Path packageDir, Path dataDir, long timeoutMs, long maxBytes) {
        String containerName = NAME_PREFIX + UUID.randomUUID();
        AtomicBoolean sizeExceeded = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        Path logFile;
        try {
            logFile = Files.createTempFile("payroll-skill-init-", ".log");
        } catch (IOException e) {
            return new Outcome(
                    PROJECTED_FAILURE_SIGNAL, false, false, 0, "", "无法创建初始化日志文件");
        }
        Thread watchdog =
                startWatchdog(containerName, dataDir, maxBytes, sizeExceeded, finished);
        try {
            CommandExecutor.Outcome outcome =
                    executor.exec(
                            command(containerName, packageDir, dataDir, setupPath),
                            Duration.ofMillis(timeoutMs).plus(GRACE),
                            logFile);
            if (outcome.timedOut() || sizeExceeded.get()) {
                killContainer(containerName);
            }
            long sizeBytes = directoryBytes(dataDir);
            String logTail = outcome.stdout();
            if (sizeExceeded.get()) {
                return new Outcome(
                        outcome.exitCode(),
                        false,
                        true,
                        sizeBytes,
                        logTail,
                        "数据目录超过上限 " + maxBytes + " 字节，初始化已终止");
            }
            if (outcome.timedOut()) {
                return new Outcome(
                        outcome.exitCode(),
                        true,
                        false,
                        sizeBytes,
                        logTail,
                        "初始化超时，容器已被强制终止");
            }
            if (outcome.exitCode() == DOCKER_ERROR_EXIT) {
                return new Outcome(
                        outcome.exitCode(),
                        false,
                        false,
                        sizeBytes,
                        logTail,
                        "初始化容器启动失败：" + firstLine(outcome.stdout()));
            }
            if (outcome.exitCode() == KILLED_EXIT) {
                return new Outcome(
                        outcome.exitCode(),
                        false,
                        sizeExceeded.get(),
                        sizeBytes,
                        logTail,
                        "初始化容器被强制终止（可能超出资源限额）");
            }
            return new Outcome(
                    outcome.exitCode(), false, false, sizeBytes, logTail, null);
        } catch (IOException e) {
            return new Outcome(
                    PROJECTED_FAILURE_SIGNAL, false, false, 0, "", "初始化调用失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killContainer(containerName);
            return new Outcome(PROJECTED_FAILURE_SIGNAL, false, false, 0, "", "初始化被中断");
        } finally {
            finished.set(true);
            watchdog.interrupt();
            try {
                Files.deleteIfExists(logFile);
            } catch (IOException e) {
                log.debug("[技能初始化] 日志临时文件清理失败：{}", logFile);
            }
        }
    }

    private Thread startWatchdog(
            String containerName,
            Path dataDir,
            long maxBytes,
            AtomicBoolean sizeExceeded,
            AtomicBoolean finished) {
        Thread thread =
                new Thread(
                        () -> {
                            while (!finished.get()) {
                                try {
                                    TimeUnit.MILLISECONDS.sleep(WATCHDOG_INTERVAL.toMillis());
                                } catch (InterruptedException e) {
                                    return;
                                }
                                if (finished.get()) {
                                    return;
                                }
                                if (directoryBytes(dataDir) > maxBytes) {
                                    sizeExceeded.set(true);
                                    killContainer(containerName);
                                    return;
                                }
                            }
                        },
                        "skill-init-watchdog");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private List<String> command(
            String containerName, Path packageDir, Path dataDir, String setupPath) {
        List<String> command = new ArrayList<>();
        command.add(settings.dockerBinary());
        command.addAll(
                List.of(
                        "run",
                        "--rm",
                        "--name",
                        containerName,
                        "--network",
                        "bridge",
                        "--read-only",
                        "--tmpfs",
                        "/work:rw,noexec,nosuid,nodev,size=16m",
                        "--tmpfs",
                        "/tmp:rw,nosuid,nodev,size=32m",
                        "--memory",
                        "1g",
                        "--memory-swap",
                        "1g",
                        "--cpus",
                        "1",
                        "--pids-limit",
                        "128",
                        "--user",
                        "65534:65534",
                        "--cap-drop",
                        "ALL",
                        "--security-opt",
                        "no-new-privileges",
                        "--env",
                        "HOME=/work",
                        "--env",
                        "GIT_TERMINAL_PROMPT=0",
                        "--mount",
                        "type=bind,source=" + packageDir.toAbsolutePath() + ",target=/skill",
                        "--mount",
                        "type=bind,source=" + dataDir.toAbsolutePath()
                                + ",target=/skill/data",
                        "-w",
                        "/skill",
                        settings.initImage()));
        command.addAll(
                SkillInitProtocol.interpreters(setupPath, setupContent(packageDir, setupPath)));
        command.add("/skill/" + setupPath);
        return command;
    }

    private static String setupContent(Path packageDir, String setupPath) {
        Path script = packageDir.resolve(setupPath).normalize();
        if (!script.startsWith(packageDir) || !Files.isRegularFile(script)) {
            return null;
        }
        try {
            return Files.readString(script, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private void killContainer(String containerName) {
        try {
            executor.exec(
                    List.of(settings.dockerBinary(), "kill", containerName),
                    Duration.ofSeconds(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // --rm 可能已回收容器
        }
    }

    private static long directoryBytes(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return 0;
        }
        long[] total = {0};
        try {
            Files.walkFileTree(
                    root,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(
                                Path file, BasicFileAttributes attrs) {
                            if (attrs.isRegularFile()) {
                                total[0] += attrs.size();
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            return total[0];
        }
        return total[0];
    }

    private static String firstLine(String output) {
        if (output == null || output.isBlank()) {
            return "未知错误";
        }
        String line = output.strip().lines().findFirst().orElse("未知错误");
        return line.length() > STDERR_MESSAGE_MAX
                ? line.substring(0, STDERR_MESSAGE_MAX) + "…"
                : line;
    }
}
