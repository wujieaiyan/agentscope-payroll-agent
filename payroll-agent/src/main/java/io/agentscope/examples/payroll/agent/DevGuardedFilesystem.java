package io.agentscope.examples.payroll.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.examples.payroll.devteam.DevContentGuard;
import io.agentscope.examples.payroll.devteam.DevGuardMonitor;
import io.agentscope.examples.payroll.devteam.DevPathPolicy;
import io.agentscope.examples.payroll.devteam.DevResultLimiter;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 研发域只读文件系统护栏（P0-3/A11）：委托给仓库根目录的 ROOTED {@code LocalFilesystem}，
 * 每次调用按角色白名单与黑名单复核；写操作与越界读取一律 fail-closed。
 *
 * <p>P0-6 加了三层：词法路径校验之后的 realpath 符号链接复核、读取/检索/列表结果截断，
 * 以及把读取内容标记为不可信数据并拦截高置信度越权指令行。
 */
final class DevGuardedFilesystem implements AbstractFilesystem {

    private static final Logger log = LoggerFactory.getLogger(DevGuardedFilesystem.class);
    private static final int MAX_FILE_SIZE_MB = 10;

    private final String role;
    private final Path projectRoot;
    private final Path realRoot;
    private final AbstractFilesystem delegate;
    private final DevGuardMonitor guard;

    DevGuardedFilesystem(String role, Path projectRoot, AbstractFilesystem delegate) {
        this(role, projectRoot, delegate, null);
    }

    DevGuardedFilesystem(
            String role, Path projectRoot, AbstractFilesystem delegate, DevGuardMonitor guard) {
        this.role = role;
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.realRoot = realPathOrSelf(this.projectRoot);
        this.delegate = delegate;
        this.guard = guard;
    }

    String role() {
        return role;
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        if (!allowed(path) && !listablePath(path)) {
            return LsResult.fail(denial("ls", path, true).orElse("拒绝访问"));
        }
        Optional<String> denied = realPathDenial("ls", path, true);
        if (denied.isPresent()) {
            return LsResult.fail(denied.get());
        }
        LsResult result = delegate.ls(runtimeContext, path);
        if (!result.isSuccess() || result.entries() == null) {
            return result;
        }
        List<FileInfo> visible = new ArrayList<>();
        int omitted = 0;
        for (FileInfo entry : result.entries()) {
            if (!allowed(entry.path()) && !(entry.isDirectory() && listablePath(entry.path()))) {
                continue;
            }
            if (visible.size() >= DevResultLimiter.MAX_LIST_ENTRIES) {
                omitted++;
                continue;
            }
            if (realPathDenialQuiet(entry.path()).isPresent()) {
                continue;
            }
            visible.add(entry);
        }
        logOmitted("ls", path, omitted);
        return LsResult.success(visible);
    }

    @Override
    public ReadResult read(
            RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        Optional<String> denied = denyAll("read", filePath);
        if (denied.isPresent()) {
            return ReadResult.fail(denied.get());
        }
        ReadResult result = delegate.read(runtimeContext, filePath, offset, limit);
        if (!result.isSuccess() || result.fileData() == null) {
            return result;
        }
        String content = result.fileData().content();
        String limited = DevResultLimiter.truncateRead(content == null ? "" : content);
        String guarded = DevContentGuard.protect("read_file:" + filePath, limited);
        return ReadResult.success(result.fileData().withContent(guarded));
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String path, String content) {
        return WriteResult.fail(refuseReadOnly("write", path));
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String path,
            String oldString,
            String newString,
            boolean replaceAll) {
        return EditResult.fail(refuseReadOnly("edit", path));
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        Optional<String> denied = denyAll("grep", path);
        if (denied.isPresent()) {
            return GrepResult.fail(denied.get());
        }
        GrepResult result;
        try {
            result =
                    delegate.grep(
                            runtimeContext,
                            pattern,
                            delegatePath(path),
                            delegateGlob(path, glob));
        } catch (RuntimeException e) {
            return GrepResult.fail("检索路径不合法：" + path + "（请传目录，如 backend/）");
        }
        if (!result.isSuccess() || result.matches() == null) {
            return result;
        }
        List<GrepMatch> visible = new ArrayList<>();
        int omitted = 0;
        for (GrepMatch match : result.matches()) {
            if (!readable(match.path())) {
                continue;
            }
            if (visible.size() >= DevResultLimiter.MAX_GREP_MATCHES) {
                omitted++;
                continue;
            }
            if (realPathDenialQuiet(match.path()).isPresent()) {
                continue;
            }
            String text =
                    DevResultLimiter.truncateLine(DevContentGuard.sanitizeLine(match.text()));
            visible.add(new GrepMatch(match.path(), match.line(), text));
        }
        if (omitted > 0) {
            log.warn("[开发域] grep 结果截断：角色={}，路径={}，隐藏 {} 条", role, path, omitted);
            visible.add(new GrepMatch("[护栏]", 0, DevResultLimiter.omittedNote(omitted)));
        }
        return GrepResult.success(visible);
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        Optional<String> denied = denyAll("glob", path);
        if (denied.isPresent()) {
            return GlobResult.fail(denied.get());
        }
        if (containsTraversal(pattern)) {
            if (guard != null) {
                guard.breach("glob", pattern, "glob 模式包含 .. 穿越");
            }
            return GlobResult.fail("glob 模式不允许包含 .. 穿越：" + pattern);
        }
        GlobResult result;
        try {
            result = delegate.glob(runtimeContext, pattern, delegatePath(path));
        } catch (RuntimeException e) {
            return GlobResult.fail("检索路径不合法：" + path + "（请传目录，如 backend/）");
        }
        if (!result.isSuccess() || result.matches() == null) {
            return result;
        }
        List<FileInfo> visible = new ArrayList<>();
        int omitted = 0;
        for (FileInfo match : result.matches()) {
            if (!readable(match.path())) {
                continue;
            }
            if (visible.size() >= DevResultLimiter.MAX_LIST_ENTRIES) {
                omitted++;
                continue;
            }
            if (realPathDenialQuiet(match.path()).isPresent()) {
                continue;
            }
            visible.add(match);
        }
        logOmitted("glob", path, omitted);
        return GlobResult.success(visible);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        List<FileUploadResponse> responses = new ArrayList<>();
        if (files != null) {
            files.forEach(
                    file ->
                            responses.add(
                                    FileUploadResponse.fail(
                                            file.getKey(), refuseReadOnly("upload", file.getKey()))));
        }
        return responses;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        List<FileDownloadResponse> responses = new ArrayList<>();
        if (paths != null) {
            paths.forEach(path -> responses.add(FileDownloadResponse.fail(path, refuseReadOnly("download", path))));
        }
        return responses;
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        return WriteResult.fail(refuseReadOnly("delete", path));
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String from, String to) {
        return WriteResult.fail(refuseReadOnly("move", from + " -> " + to));
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        return denyAllQuiet(path).isEmpty() && delegate.exists(runtimeContext, path);
    }

    int maxFileSizeMb() {
        return MAX_FILE_SIZE_MB;
    }

    private boolean readable(String path) {
        return allowed(path);
    }

    /** 角色是否具备任何文件系统读权限。 */
    private boolean roleReadable() {
        return DevPathPolicy.role(role)
                .map(DevPathPolicy.RolePaths::readEnabled)
                .orElse(false);
    }

    /** 可直接读取（白名单内且未命中黑名单）。 */
    private boolean allowed(String path) {
        if (isRoot(path)) {
            return roleReadable();
        }
        String relative = toRelative(path.trim());
        return relative != null && DevPathPolicy.canRead(role, relative);
    }

    /** 可列出目录：白名单内或位于白名单路径的上层，用于逐层导航。 */
    private boolean listablePath(String path) {
        if (isRoot(path)) {
            return roleReadable();
        }
        String relative = toRelative(path.trim());
        return relative != null && DevPathPolicy.canList(role, relative);
    }

    private static boolean isRoot(String path) {
        return path == null || path.isBlank() || ".".equals(path.trim()) || "/".equals(path.trim());
    }

    /** 返回拒绝原因；空表示放行。null/空/`.`/`/` 视为仓库根。 */
    private Optional<String> denial(String action, String path, boolean report) {
        if (!roleReadable()) {
            return deny(action, path, role + " 不授予文件系统访问", report);
        }
        String checked = policyPath(path);
        if (checked == null) {
            return deny(action, path, "路径超出项目根或为非法绝对路径", report);
        }
        if (allowed(checked)) {
            return Optional.empty();
        }
        String relative = toRelative(checked.trim());
        if (relative == null) {
            return deny(action, path, "路径超出项目根或为非法绝对路径", report);
        }
        return deny(action, path, DevPathPolicy.evaluate(role, relative, false).reason(), report);
    }

    private Optional<String> deny(String action, String path, String reason, boolean report) {
        log.warn(
                "[开发域] 拒绝文件访问：角色={}，操作={}，路径={}，原因={}",
                role,
                action,
                path,
                reason);
        if (report && guard != null) {
            guard.breach(action, path, reason);
        }
        return Optional.of("拒绝访问：" + reason);
    }

    private Optional<String> denyAll(String action, String path) {
        Optional<String> denied = denial(action, path, true);
        return denied.isPresent() ? denied : realPathDenial(action, path, true);
    }

    /** exists 是常规探测，被拒时只返回 false，不计入护栏命中。 */
    private Optional<String> denyAllQuiet(String path) {
        Optional<String> denied = denial("exists", path, false);
        return denied.isPresent() ? denied : realPathDenial("exists", path, false);
    }

    private Optional<String> realPathDenialQuiet(String path) {
        return realPathDenial("ls", path, false);
    }

    /**
     * 词法白名单之后复核真实路径（P0-6）：沿最近的已存在祖先做 realpath，
     * 符号链接逃逸到项目根之外、或指向角色不可读目录（如 {@code data/}）一律拒绝。
     * 目标不存在且祖先均合法时放行，由委托层返回「不存在」。
     */
    private Optional<String> realPathDenial(String action, String path, boolean report) {
        String checked = policyPath(path);
        if (checked == null) {
            return deny(action, path, "路径超出项目根或为非法绝对路径", report);
        }
        if (isRoot(checked)) {
            return Optional.empty();
        }
        String relative = toRelative(checked.trim());
        if (relative == null) {
            return deny(action, path, "路径超出项目根或为非法绝对路径", report);
        }
        Path target = projectRoot.resolve(relative).normalize();
        if (!target.startsWith(projectRoot)) {
            return deny(action, path, "路径超出项目根：" + relative, report);
        }
        try {
            Path existing = target;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null) {
                return Optional.empty();
            }
            Path real = existing.toRealPath();
            if (!real.startsWith(realRoot)) {
                return deny(action, path, "符号链接指向项目根之外：" + relative, report);
            }
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            String realRelative = realRoot.relativize(real).toString().replace('\\', '/');
            if (!realRelative.isEmpty()
                    && !DevPathPolicy.canRead(role, realRelative)
                    && !DevPathPolicy.canList(role, realRelative)) {
                return deny(action, path, "符号链接指向不可读路径：" + realRelative, report);
            }
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            return deny(action, path, "无法解析真实路径：" + relative, report);
        }
    }

    /** 只读模式下拒绝写面，并按护栏命中登记（越权写）。 */
    private String refuseReadOnly(String action, String path) {
        String reason = readOnly();
        log.warn("[开发域] 拒绝写操作：角色={}，操作={}，路径={}", role, action, path);
        if (guard != null) {
            guard.breach(action, path, reason);
        }
        return reason;
    }

    private static Path realPathOrSelf(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | RuntimeException e) {
            return path;
        }
    }

    private void logOmitted(String tool, String path, int omitted) {
        if (omitted > 0) {
            log.warn(
                    "[开发域] {} 结果截断：角色={}，路径={}，隐藏 {} 条",
                    tool,
                    role,
                    path,
                    omitted);
        }
    }

    /** 绝对路径必须位于项目根内，转为仓库相对路径后交给路径策略复核。 */
    private String toRelative(String path) {
        try {
            Path target = Path.of(path);
            if (!target.isAbsolute()) {
                return path;
            }
            Path normalized = target.normalize();
            if (!normalized.startsWith(projectRoot)) {
                return null;
            }
            return projectRoot.relativize(normalized).toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * grep/glob 的 path 常带通配符（Windows 下非法路径字符），只校验通配符前的字面目录。
     */
    private static String policyPath(String path) {
        if (path == null) {
            return "";
        }
        String value = path.trim();
        if (!hasWildcard(value)) {
            return value;
        }
        String unified = value.replace('\\', '/');
        if (unified.startsWith("/")
                || unified.startsWith("~")
                || unified.indexOf(':') >= 0
                || containsTraversal(unified)) {
            return null;
        }
        StringBuilder prefix = new StringBuilder();
        for (String segment : unified.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if (hasWildcard(segment)) {
                break;
            }
            if (!prefix.isEmpty()) {
                prefix.append('/');
            }
            prefix.append(segment);
        }
        return prefix.isEmpty() ? "" : prefix.toString();
    }

    private static boolean hasWildcard(String value) {
        return value != null
                && (value.indexOf('*') >= 0
                        || value.indexOf('?') >= 0
                        || value.indexOf('[') >= 0
                        || value.indexOf('{') >= 0);
    }

    /** 委托层只接受目录：通配符路径退化为通配符之前的字面目录。 */
    private static String delegatePath(String path) {
        String checked = policyPath(path);
        if (checked == null) {
            return path == null ? "." : path;
        }
        return checked.isEmpty() ? "." : checked;
    }

    /** 把 {@code backend/*} 这类路径里的通配符段并入 glob 过滤，保留调用方意图。 */
    private static String delegateGlob(String path, String glob) {
        String remainder = wildcardRemainder(path);
        if (remainder.isEmpty()) {
            return glob;
        }
        return glob == null || glob.isBlank() ? remainder : remainder + "/" + glob;
    }

    private static String wildcardRemainder(String path) {
        if (path == null || !hasWildcard(path)) {
            return "";
        }
        String[] segments = path.trim().replace('\\', '/').split("/");
        int start = segments.length;
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].isEmpty() || ".".equals(segments[i])) {
                continue;
            }
            if (hasWildcard(segments[i])) {
                start = i;
                break;
            }
        }
        return start == segments.length
                ? ""
                : String.join("/", Arrays.copyOfRange(segments, start, segments.length));
    }

    private static boolean containsTraversal(String pattern) {
        if (pattern == null) {
            return false;
        }
        for (String segment : pattern.replace('\\', '/').split("/")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static String readOnly() {
        return "研发域当前为只读模式，禁止写入；写能力需 workspace-write + 任务级审批";
    }
}
