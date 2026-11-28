package io.agentscope.payroll.sandbox;

import io.agentscope.examples.payroll.sandbox.SkillDataProtocol;
import io.agentscope.examples.payroll.sandbox.SkillInitProtocol;
import io.agentscope.examples.payroll.service.InternalAuth;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 技能数据内部接口：目录树、预览与人工上传/删除，全部要求 HMAC（含 query）校验。 */
@RestController
public class SandboxDataController {

    private static final long MAX_SKEW_SECONDS = 120;

    private final SandboxSettings settings;
    private final SandboxDataService data;
    private final SandboxDataWriteService writer;

    public SandboxDataController(
            SandboxSettings settings, SandboxDataService data, SandboxDataWriteService writer) {
        this.settings = settings;
        this.data = data;
        this.writer = writer;
    }

    @GetMapping(SkillInitProtocol.STATUS_PATH_PREFIX + "{id}" + SkillDataProtocol.TREE_SUFFIX)
    public ResponseEntity<?> tree(
            @PathVariable("id") String id,
            @RequestParam(name = SkillDataProtocol.QUERY_PATH, required = false) String path,
            @RequestParam(name = SkillDataProtocol.QUERY_DEPTH, required = false) Integer depth,
            @RequestHeader(value = "X-Payroll-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Payroll-Internal", required = false) String signature) {
        if (!settings.configured()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "NOT_CONFIGURED", "沙箱服务未启用");
        }
        String canonical;
        try {
            canonical = SkillDataProtocol.treePath(id, path, depth);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID", e.getMessage());
        }
        if (!verify("GET", canonical, timestamp, signature)) {
            return unauthorized();
        }
        try {
            return ResponseEntity.ok(data.tree(id, path, depth));
        } catch (SandboxDataService.DataNotReadyException e) {
            return error(HttpStatus.CONFLICT, "BUSY", e.getMessage());
        } catch (NoSuchFileException e) {
            return error(HttpStatus.NOT_FOUND, "NOT_FOUND", message(e, "技能数据不存在"));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID", e.getMessage());
        } catch (IOException e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR", "读取技能数据失败");
        }
    }

    @GetMapping(SkillInitProtocol.STATUS_PATH_PREFIX + "{id}" + SkillDataProtocol.FILE_SUFFIX)
    public ResponseEntity<?> file(
            @PathVariable("id") String id,
            @RequestParam(name = SkillDataProtocol.QUERY_PATH, required = false) String path,
            @RequestParam(name = SkillDataProtocol.QUERY_MAX_BYTES, required = false)
                    Integer maxBytes,
            @RequestHeader(value = "X-Payroll-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Payroll-Internal", required = false) String signature) {
        if (!settings.configured()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "NOT_CONFIGURED", "沙箱服务未启用");
        }
        String canonical;
        try {
            canonical = SkillDataProtocol.filePath(id, path, maxBytes);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID", e.getMessage());
        }
        if (!verify("GET", canonical, timestamp, signature)) {
            return unauthorized();
        }
        try {
            return ResponseEntity.ok(data.file(id, path, maxBytes));
        } catch (SandboxDataService.DataNotReadyException e) {
            return error(HttpStatus.CONFLICT, "BUSY", e.getMessage());
        } catch (NoSuchFileException e) {
            return error(HttpStatus.NOT_FOUND, "NOT_FOUND", message(e, "数据文件不存在"));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID", e.getMessage());
        } catch (IOException e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR", "读取数据文件失败");
        }
    }

    /** 上传文件到 data/：签名覆盖路径、大小、摘要与覆盖标志，正文由摘要二次校验。 */
    @PostMapping(SkillInitProtocol.STATUS_PATH_PREFIX + "{id}" + SkillDataProtocol.FILE_SUFFIX)
    public ResponseEntity<?> upload(
            @PathVariable("id") String id,
            @RequestParam(name = SkillDataProtocol.QUERY_PATH, required = false) String path,
            @RequestParam(name = SkillDataProtocol.QUERY_OVERWRITE, required = false)
                    String overwrite,
            @RequestParam(name = SkillDataProtocol.QUERY_SIZE, required = false) Long size,
            @RequestParam(name = SkillDataProtocol.QUERY_SHA256, required = false) String sha256,
            @RequestHeader(value = "X-Payroll-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Payroll-Internal", required = false) String signature,
            HttpServletRequest request) {
        if (!settings.configured()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "NOT_CONFIGURED", "沙箱服务未启用");
        }
        boolean replace = Boolean.parseBoolean(overwrite);
        long declaredSize = size == null ? 0 : size;
        String canonical;
        try {
            canonical = SkillDataProtocol.uploadPath(id, path, replace, declaredSize, sha256);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID", e.getMessage());
        }
        if (!verify("POST", canonical, timestamp, signature)) {
            return unauthorized();
        }
        try {
            return ResponseEntity.ok(
                    writer.upload(
                            id,
                            path,
                            replace,
                            declaredSize,
                            sha256,
                            request.getInputStream()));
        } catch (SandboxDataService.DataNotReadyException e) {
            return error(HttpStatus.CONFLICT, "BUSY", e.getMessage());
        } catch (SandboxDataWriteService.DataWriteConflictException e) {
            return error(HttpStatus.CONFLICT, "CONFLICT", e.getMessage());
        } catch (SandboxDataWriteService.DataWriteLimitException e) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "LIMIT", e.getMessage());
        } catch (NoSuchFileException e) {
            return error(HttpStatus.NOT_FOUND, "NOT_FOUND", message(e, "数据目录不存在"));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID", e.getMessage());
        } catch (IOException e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR", "写入数据文件失败");
        }
    }

    /** 删除 data/ 中的单个文件；目录与符号链接一律拒绝。 */
    @DeleteMapping(SkillInitProtocol.STATUS_PATH_PREFIX + "{id}" + SkillDataProtocol.FILE_SUFFIX)
    public ResponseEntity<?> delete(
            @PathVariable("id") String id,
            @RequestParam(name = SkillDataProtocol.QUERY_PATH, required = false) String path,
            @RequestHeader(value = "X-Payroll-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Payroll-Internal", required = false) String signature) {
        if (!settings.configured()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "NOT_CONFIGURED", "沙箱服务未启用");
        }
        String canonical;
        try {
            canonical = SkillDataProtocol.deletePath(id, path);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID", e.getMessage());
        }
        if (!verify("DELETE", canonical, timestamp, signature)) {
            return unauthorized();
        }
        try {
            return ResponseEntity.ok(writer.delete(id, path));
        } catch (SandboxDataService.DataNotReadyException e) {
            return error(HttpStatus.CONFLICT, "BUSY", e.getMessage());
        } catch (NoSuchFileException e) {
            return error(HttpStatus.NOT_FOUND, "NOT_FOUND", message(e, "数据文件不存在"));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID", e.getMessage());
        } catch (IOException e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR", "删除数据文件失败");
        }
    }

    private boolean verify(
            String method, String canonicalPath, String timestamp, String signature) {
        return InternalAuth.verify(
                settings.token(), method, canonicalPath, timestamp, signature, null, MAX_SKEW_SECONDS);
    }

    private static ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    private static ResponseEntity<Map<String, Object>> error(
            HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", code);
        body.put("message", message == null ? "" : message);
        return ResponseEntity.status(status).body(body);
    }

    private static String message(Exception error, String fallback) {
        String text = error.getMessage();
        return text == null || text.isBlank() ? fallback : text;
    }
}
