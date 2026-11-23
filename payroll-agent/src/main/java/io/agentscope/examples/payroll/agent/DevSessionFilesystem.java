package io.agentscope.examples.payroll.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.examples.payroll.devteam.DevAccessDeniedException;
import io.agentscope.examples.payroll.devteam.DevEdit;
import io.agentscope.examples.payroll.devteam.DevEditSession;
import io.agentscope.examples.payroll.devteam.DevGuardMonitor;
import io.agentscope.examples.payroll.devteam.DevSandboxEditor;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 研发域可写文件系统（P1-1）：读取沿用只读护栏，写入与替换交给任务编辑会话，
 * 只在隔离工作区内生效；取消替换全部、上传、下载、删除与移动，保持最小写面。
 */
final class DevSessionFilesystem implements AbstractFilesystem {

    private static final Logger log = LoggerFactory.getLogger(DevSessionFilesystem.class);
    private static final int MAX_FILE_SIZE_MB = 10;

    private final DevEditSession session;
    private final DevGuardedFilesystem reader;
    private final DevGuardMonitor guard;

    DevSessionFilesystem(String role, Path sandboxRoot, DevEditSession session) {
        this(role, sandboxRoot, session, null);
    }

    DevSessionFilesystem(
            String role, Path sandboxRoot, DevEditSession session, DevGuardMonitor guard) {
        if (session == null) {
            throw new PayrollException("可写文件系统需要编辑会话");
        }
        this.session = session;
        this.guard = guard;
        this.reader =
                new DevGuardedFilesystem(
                        role,
                        sandboxRoot,
                        new LocalFilesystem(
                                sandboxRoot,
                                LocalFsMode.ROOTED,
                                PathPolicy.of(sandboxRoot),
                                MAX_FILE_SIZE_MB,
                                null),
                        guard);
    }

    DevEditSession session() {
        return session;
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        return reader.ls(runtimeContext, path);
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        return reader.read(runtimeContext, filePath, offset, limit);
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String path, String content) {
        try {
            DevSandboxEditor.Outcome outcome = session.apply(DevEdit.write(path, content));
            return WriteResult.ok(outcome.path());
        } catch (DevAccessDeniedException e) {
            return WriteResult.fail(breach("write", path, e));
        } catch (PayrollException e) {
            log.warn("[开发域] write_file 被拒绝：路径={}，原因={}", path, e.getMessage());
            return WriteResult.fail(e.getMessage());
        }
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String path,
            String oldString,
            String newString,
            boolean replaceAll) {
        if (replaceAll) {
            return EditResult.fail("研发域不支持替换全部（replaceAll），请提供唯一上下文分批修改");
        }
        try {
            DevSandboxEditor.Outcome outcome =
                    session.apply(DevEdit.replace(path, oldString, newString));
            return EditResult.ok(outcome.path(), 1);
        } catch (DevAccessDeniedException e) {
            return EditResult.fail(breach("edit", path, e));
        } catch (PayrollException e) {
            log.warn("[开发域] edit_file 被拒绝：路径={}，原因={}", path, e.getMessage());
            return EditResult.fail(e.getMessage());
        }
    }

    private String breach(String action, String path, DevAccessDeniedException e) {
        log.warn("[开发域] {} 触发护栏：路径={}，原因={}", action, path, e.getMessage());
        if (guard != null) {
            guard.breach(action, path, e.getMessage());
        }
        return e.getMessage();
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        return reader.grep(runtimeContext, pattern, path, glob);
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        return reader.glob(runtimeContext, pattern, path);
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
                                            file.getKey(), "研发域不支持上传文件")));
        }
        return responses;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        List<FileDownloadResponse> responses = new ArrayList<>();
        if (paths != null) {
            paths.forEach(path -> responses.add(FileDownloadResponse.fail(path, "研发域不支持下载文件")));
        }
        return responses;
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        return WriteResult.fail("研发域不支持删除文件，请在补丁中体现删除意图");
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String from, String to) {
        return WriteResult.fail("研发域不支持移动文件，请写入新路径并在补丁说明中标注");
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        return reader.exists(runtimeContext, path);
    }
}
