package io.agentscope.payroll.sandbox;

import io.agentscope.examples.payroll.sandbox.SkillInitProtocol;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 技能工作区：把包复制到 {@code <root>/<id>/pkg}，执行初始化后只保留 {@code data/}。
 *
 * <p>初始化异步执行，全局并发与同技能单实例都在提交时占用；状态先落盘再启动容器，
 * 这样请求返回后立刻查询也能看到 INITIALIZING。沙箱重启导致的残留 INITIALIZING
 * 会在下一次查询时标记为 FAILED。
 */
@Service
public class SkillWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(SkillWorkspaceService.class);
    private static final String PACKAGE_DIR = "pkg";
    private static final String DATA_DIR = "data";

    private final SandboxSettings settings;
    private final SkillInitRunner runner;
    private final Semaphore slots;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor;

    public SkillWorkspaceService(SandboxSettings settings, SkillInitRunner runner) {
        this.settings = settings;
        this.runner = runner;
        AtomicInteger counter = new AtomicInteger();
        this.executor =
                Executors.newFixedThreadPool(
                        settings.initConcurrency(),
                        task -> {
                            Thread thread =
                                    new Thread(task, "skill-init-" + counter.incrementAndGet());
                            thread.setDaemon(true);
                            return thread;
                        });
        this.slots = new Semaphore(settings.initConcurrency(), true);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public SkillInitStatus start(SkillInitRequest request) {
        if (!settings.configured()) {
            return SkillInitStatus.notConfigured("沙箱服务未配置内部令牌");
        }
        if (request == null) {
            return SkillInitStatus.invalid("请求体为空");
        }
        String skillId;
        String setupPath;
        Map<String, String> contents;
        long timeoutMs;
        long maxBytes;
        try {
            skillId = SkillInitProtocol.requireSkillId(request.skillId());
            setupPath = SkillInitProtocol.requireSetupPath(request.setupPath());
            contents = contents(request, setupPath);
            timeoutMs = SkillInitProtocol.requireTimeout(request.timeoutMs(), settings.initTimeoutMs());
            maxBytes = SkillInitProtocol.requireMaxBytes(request.maxBytes(), settings.initMaxBytes());
        } catch (IllegalArgumentException e) {
            return SkillInitStatus.invalid(e.getMessage());
        }
        if (contents.get(setupPath).isBlank()) {
            return SkillInitStatus.invalid("初始化脚本内容为空：" + setupPath);
        }
        if (!running.add(skillId)) {
            return SkillInitStatus.busy(skillId, "该技能正在初始化，请等待完成");
        }
        if (!slots.tryAcquire()) {
            running.remove(skillId);
            return SkillInitStatus.busy(skillId, "初始化并发已满，请稍后重试");
        }
        long startedAt = System.currentTimeMillis();
        SkillInitStatus initializing = SkillInitStatus.initializing(skillId, setupPath, startedAt);
        Path skillDir = skillDir(skillId);
        try {
            SkillInitState.write(skillDir, initializing);
        } catch (IOException e) {
            running.remove(skillId);
            slots.release();
            log.warn("[技能初始化] 状态写入失败：{}", skillDir, e);
            return SkillInitStatus.invalid("无法写入技能工作区：" + e.getMessage());
        }
        executor.submit(
                () ->
                        execute(
                                skillId,
                                setupPath,
                                contents,
                                skillDir,
                                timeoutMs,
                                maxBytes,
                                startedAt));
        return initializing;
    }

    public SkillInitStatus status(String skillId) {
        String id;
        try {
            id = SkillInitProtocol.requireSkillId(skillId);
        } catch (IllegalArgumentException e) {
            return SkillInitStatus.invalid(e.getMessage());
        }
        Path skillDir = skillDir(id);
        SkillInitStatus state = SkillInitState.read(skillDir);
        if (state == null) {
            return SkillInitStatus.unknown(id);
        }
        if (!SkillInitStatus.INITIALIZING.equals(state.status())) {
            return state;
        }
        long liveSize = directoryBytes(skillDir.resolve(DATA_DIR));
        if (running.contains(id)) {
            return new SkillInitStatus(
                    SkillInitStatus.INITIALIZING,
                    id,
                    state.setupPath(),
                    null,
                    state.startedAt(),
                    state.finishedAt(),
                    liveSize,
                    state.logTail(),
                    state.message());
        }
        // 与收尾写入竞态时以最新状态为准；刚启动的任务在超时窗口内不判为中断。
        SkillInitStatus fresh = SkillInitState.read(skillDir);
        if (fresh != null && !SkillInitStatus.INITIALIZING.equals(fresh.status())) {
            return fresh;
        }
        long staleAfterMs = settings.initTimeoutMs() + 60_000L;
        if (System.currentTimeMillis() - state.startedAt() < staleAfterMs) {
            return new SkillInitStatus(
                    SkillInitStatus.INITIALIZING,
                    id,
                    state.setupPath(),
                    null,
                    state.startedAt(),
                    state.finishedAt(),
                    liveSize,
                    state.logTail(),
                    state.message());
        }
        SkillInitStatus stale =
                new SkillInitStatus(
                        SkillInitStatus.FAILED,
                        id,
                        state.setupPath(),
                        null,
                        state.startedAt(),
                        System.currentTimeMillis(),
                        liveSize,
                        state.logTail(),
                        "初始化任务已中断（沙箱重启或进程退出）");
        writeStateQuietly(skillDir, stale);
        return stale;
    }

    /**
     * 删除初始化产物：清理 {@code data/}、临时包与状态文件，技能目录空了才一起删除。
     *
     * <p>人工上传也写入 {@code data/}，随重建/删除一起清空；目录内其他残留按普通文件保留。
     */
    public SkillInitStatus delete(String skillId) {
        String id;
        try {
            id = SkillInitProtocol.requireSkillId(skillId);
        } catch (IllegalArgumentException e) {
            return SkillInitStatus.invalid(e.getMessage());
        }
        if (running.contains(id)) {
            return SkillInitStatus.busy(id, "该技能正在初始化，无法删除工作区");
        }
        Path skillDir = skillDir(id);
        deleteRecursively(skillDir.resolve(DATA_DIR));
        deleteRecursively(skillDir.resolve(PACKAGE_DIR));
        deleteQuietly(skillDir.resolve(SkillInitState.STATE_FILE));
        deleteQuietly(skillDir.resolve(SkillInitState.STATE_FILE + ".tmp"));
        deleteIfEmpty(skillDir);
        return new SkillInitStatus(
                SkillInitStatus.UNKNOWN,
                id,
                null,
                null,
                0,
                System.currentTimeMillis(),
                0,
                null,
                "工作区已删除");
    }

    private void execute(
            String skillId,
            String setupPath,
            Map<String, String> contents,
            Path skillDir,
            long timeoutMs,
            long maxBytes,
            long startedAt) {
        Path packageDir = skillDir.resolve(PACKAGE_DIR);
        Path dataDir = skillDir.resolve(DATA_DIR);
        SkillInitStatus result;
        try {
            deleteRecursively(packageDir);
            writePackage(packageDir, contents);
            Files.createDirectories(dataDir);
            makeWorldWritable(dataDir);
            SkillInitRunner.Outcome outcome =
                    runner.run(setupPath, packageDir, dataDir, timeoutMs, maxBytes);
            result = toStatus(skillId, setupPath, startedAt, outcome);
        } catch (IOException | RuntimeException e) {
            log.warn("[技能初始化] 技能 {} 执行失败：{}", skillId, e.toString());
            result = failed(
                    skillId,
                    setupPath,
                    startedAt,
                    "初始化失败：" + e.getMessage(),
                    directoryBytes(dataDir),
                    null);
        } finally {
            deleteRecursively(packageDir);
        }
        writeStateQuietly(skillDir, result);
        running.remove(skillId);
        slots.release();
    }

    private SkillInitStatus toStatus(
            String skillId,
            String setupPath,
            long startedAt,
            SkillInitRunner.Outcome outcome) {
        long finishedAt = System.currentTimeMillis();
        String logTail = truncateTail(outcome.logTail());
        if (outcome.sizeExceeded()) {
            return new SkillInitStatus(
                    SkillInitStatus.FAILED,
                    skillId,
                    setupPath,
                    outcome.exitCode(),
                    startedAt,
                    finishedAt,
                    outcome.sizeBytes(),
                    logTail,
                    outcome.message());
        }
        if (outcome.timedOut()) {
            return new SkillInitStatus(
                    SkillInitStatus.FAILED,
                    skillId,
                    setupPath,
                    outcome.exitCode(),
                    startedAt,
                    finishedAt,
                    outcome.sizeBytes(),
                    logTail,
                    outcome.message() == null ? "初始化超时" : outcome.message());
        }
        if (outcome.exitCode() != 0) {
            String message =
                    outcome.message() == null
                            ? "初始化脚本退出码 " + outcome.exitCode()
                            : outcome.message();
            return new SkillInitStatus(
                    SkillInitStatus.FAILED,
                    skillId,
                    setupPath,
                    outcome.exitCode(),
                    startedAt,
                    finishedAt,
                    outcome.sizeBytes(),
                    logTail,
                    message);
        }
        return new SkillInitStatus(
                SkillInitStatus.READY,
                skillId,
                setupPath,
                outcome.exitCode(),
                startedAt,
                finishedAt,
                outcome.sizeBytes(),
                logTail,
                "初始化完成");
    }

    private static SkillInitStatus failed(
            String skillId,
            String setupPath,
            long startedAt,
            String message,
            long sizeBytes,
            String logTail) {
        return new SkillInitStatus(
                SkillInitStatus.FAILED,
                skillId,
                setupPath,
                null,
                startedAt,
                System.currentTimeMillis(),
                sizeBytes,
                logTail,
                message);
    }

    /** 文件内容以 setup 为准；setup 也可以只放在 files 里。 */
    private static Map<String, String> contents(SkillInitRequest request, String setupPath) {
        Map<String, String> contents = new LinkedHashMap<>();
        List<SkillInitRequest.File> files = request.files();
        if (files != null) {
            for (SkillInitRequest.File file : files) {
                if (file == null) {
                    throw new IllegalArgumentException("包内文件不能为空");
                }
                String path = SkillInitProtocol.requireFilePath(file.path());
                contents.put(path, file.content() == null ? "" : file.content());
            }
        }
        String explicit = request.setupContent();
        if (explicit == null || explicit.isEmpty()) {
            explicit = contents.getOrDefault(setupPath, "");
        }
        contents.put(setupPath, explicit);
        List<SkillInitProtocol.PathAndBytes> sizes = new ArrayList<>(contents.size());
        contents.forEach(
                (path, content) ->
                        sizes.add(
                                new SkillInitProtocol.PathAndBytes(
                                        path, content.getBytes(StandardCharsets.UTF_8).length)));
        SkillInitProtocol.validateFiles(sizes);
        return contents;
    }

    private static void writePackage(Path packageDir, Map<String, String> contents)
            throws IOException {
        Files.createDirectories(packageDir);
        makeWorldReadable(packageDir, true);
        for (Map.Entry<String, String> entry : contents.entrySet()) {
            Path target = packageDir.resolve(entry.getKey()).normalize();
            if (!target.startsWith(packageDir)) {
                throw new IOException("包内文件路径越界：" + entry.getKey());
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
                makeWorldReadable(target.getParent(), true);
            }
            Files.write(
                    target,
                    entry.getValue().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            makeWorldReadable(target, false);
        }
    }

    private Path skillDir(String skillId) {
        Path root = Paths.get(settings.skillRoot()).toAbsolutePath().normalize();
        Path dir = root.resolve(skillId).normalize();
        if (!dir.startsWith(root)) {
            throw new IllegalArgumentException("非法技能工作区：" + skillId);
        }
        return dir;
    }

    private void writeStateQuietly(Path skillDir, SkillInitStatus status) {
        try {
            SkillInitState.write(skillDir, status);
        } catch (IOException e) {
            log.warn("[技能初始化] 状态写入失败：{}", skillDir, e);
        }
    }

    private static String truncateTail(String tail) {
        if (tail == null || tail.isEmpty()) {
            return "";
        }
        byte[] bytes = tail.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= SkillInitProtocol.MAX_LOG_BYTES) {
            return tail;
        }
        int start = bytes.length - SkillInitProtocol.MAX_LOG_BYTES;
        while (start < bytes.length && (bytes[start] & 0xC0) == 0x80) {
            start++;
        }
        return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    }

    private static long directoryBytes(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return 0;
        }
        long[] total = {0};
        try {
            Files.walkFileTree(
                    root,
                    new java.nio.file.SimpleFileVisitor<>() {
                        @Override
                        public java.nio.file.FileVisitResult visitFile(
                                Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                            if (attrs.isRegularFile()) {
                                total[0] += attrs.size();
                            }
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult visitFileFailed(
                                Path file, IOException exc) {
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            return total[0];
        }
        return total[0];
    }

    private static void makeWorldReadable(Path path, boolean directory) {
        try {
            Files.setPosixFilePermissions(
                    path,
                    PosixFilePermissions.fromString(directory ? "rwxr-xr-x" : "r--r--r--"));
        } catch (IOException | UnsupportedOperationException e) {
            // Windows 磁盘不支持 POSIX 权限，容器挂载仍可读取
        }
    }

    private static void makeWorldWritable(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxrwxrwx"));
        } catch (IOException | UnsupportedOperationException e) {
            // Windows 磁盘不支持 POSIX 权限
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("[技能初始化] 状态文件清理失败：{}", file);
        }
    }

    /** 导入区为空时连技能目录一起收掉，保持“删除即回到未知”的语义。 */
    private static void deleteIfEmpty(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> children = Files.list(dir)) {
            if (children.findAny().isPresent()) {
                return;
            }
        } catch (IOException e) {
            return;
        }
        deleteQuietly(dir);
    }

    static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                    // 初始化容器可能仍占用文件，交给系统回收
                                }
                            });
        } catch (IOException ignored) {
            // 目录已不存在
        }
    }
}
