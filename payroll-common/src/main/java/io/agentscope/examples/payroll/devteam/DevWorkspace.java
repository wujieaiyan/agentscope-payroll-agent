package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 研发域隔离工作区（P0-2/P1-1）：从项目仓库克隆到任务私有目录，记录基线 commit，
 * 任务结束时用 JGit 汇总「基线 → 工作区」的统一 diff，并在关闭时清理临时目录。
 *
 * <p>克隆只带已提交内容，未提交改动不会进入沙箱；diff 时先 {@code git add -A} 再取
 * {@code HEAD → index} 的缓存差异，因此新建文件也会进入补丁，而 {@code .gitignore}
 * 命中的构建产物不会。
 */
public final class DevWorkspace implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DevWorkspace.class);

    private final Path root;
    private final String baselineCommit;
    private final boolean temporary;
    private boolean closed;

    private DevWorkspace(Path root, String baselineCommit, boolean temporary) {
        this.root = root;
        this.baselineCommit = baselineCommit;
        this.temporary = temporary;
    }

    public static DevWorkspace clone(Path projectRoot, Path targetDir) {
        if (projectRoot == null || targetDir == null) {
            throw new PayrollException("克隆工作区需要项目根与目标目录");
        }
        Path source = projectRoot.toAbsolutePath().normalize();
        Path target = targetDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(source.resolve(".git"))) {
            throw new PayrollException("项目根不是 Git 仓库：" + source);
        }
        if (Files.exists(target)) {
            throw new PayrollException("工作区目标目录已存在：" + target);
        }
        try {
            Files.createDirectories(target.getParent());
            try (Git git =
                    Git.cloneRepository()
                            .setURI(source.toUri().toString())
                            .setDirectory(target.toFile())
                            .setCloneAllBranches(false)
                            .setNoCheckout(false)
                            .call()) {
                String baseline = headCommit(git.getRepository());
                log.info("[开发域] 已创建隔离工作区：{}，基线={}", target, baseline);
                return new DevWorkspace(target, baseline, true);
            }
        } catch (IOException | GitAPIException e) {
            deleteRecursively(target);
            throw new PayrollException("创建隔离工作区失败：" + target + "，原因：" + e.getMessage(), e);
        }
    }

    /** 打开已有的沙箱工作区（例如进程重启后恢复任务），基线取当前 HEAD。 */
    public static DevWorkspace open(Path existingRoot) {
        if (existingRoot == null) {
            throw new PayrollException("工作区目录不能为空");
        }
        Path root = existingRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve(".git"))) {
            throw new PayrollException("目录不是 Git 工作区：" + root);
        }
        try (Git git = Git.open(root.toFile())) {
            return new DevWorkspace(root, headCommit(git.getRepository()), false);
        } catch (IOException e) {
            throw new PayrollException("打开工作区失败：" + root + "，原因：" + e.getMessage(), e);
        }
    }

    public Path root() {
        return root;
    }

    public String baselineCommit() {
        return baselineCommit;
    }

    public boolean temporary() {
        return temporary;
    }

    /** 基线 commit 到当前工作区的统一 diff；无变更返回空串。 */
    public String diff() {
        if (closed) {
            throw new PayrollException("工作区已关闭：" + root);
        }
        try (Git git = Git.open(root.toFile())) {
            stageAll(git);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            git.diff()
                    .setCached(true)
                    .setOutputStream(buffer)
                    .setContextLines(DevUnifiedDiff.DEFAULT_CONTEXT)
                    .call();
            return buffer.toString(StandardCharsets.UTF_8);
        } catch (IOException | GitAPIException e) {
            throw new PayrollException("生成工作区 diff 失败：" + root + "，原因：" + e.getMessage(), e);
        }
    }

    /** 只统计变更文件数；用于会话判断是否有可提交内容。 */
    public int changedFileCount() {
        String diff = diff();
        int count = 0;
        for (String line : diff.split("\n")) {
            if (line.startsWith("diff --git ")) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (temporary) {
            deleteRecursively(root);
        }
    }

    @Override
    public String toString() {
        return "DevWorkspace[" + root + ", baseline=" + baselineCommit + "]";
    }

    private static void stageAll(Git git) throws GitAPIException {
        AddCommand add = git.add().addFilepattern(".");
        add.call();
        git.add().setUpdate(true).addFilepattern(".").call();
    }

    private static String headCommit(Repository repository) throws IOException {
        ObjectId head = repository.resolve("HEAD");
        if (head == null) {
            throw new PayrollException("工作区没有 HEAD 提交：" + repository.getWorkTree());
        }
        return head.name();
    }

    /** Windows 上 git 对象默认只读，删除前需清掉只读位。 */
    private static void deleteFile(Path path) throws IOException {
        try {
            Files.deleteIfExists(path);
        } catch (java.nio.file.AccessDeniedException e) {
            if (path.toFile().setWritable(true)) {
                Files.deleteIfExists(path);
            } else {
                throw e;
            }
        }
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Files.walkFileTree(
                    path,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                            deleteFile(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                                throws IOException {
                            deleteFile(dir);
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            log.warn("[开发域] 清理工作区失败：{}，原因={}", path, Objects.toString(e.getMessage(), ""));
        }
    }
}
