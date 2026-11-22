package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.chat.ChatAttachmentService;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.support.Permissions;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 对话附件上传、预览、下载与待发送附件删除；所有接口都校验会话归属。 */
@RestController
@RequestMapping("/api/chat/sessions/{sessionId}/attachments")
@RequirePermission(Permissions.CHAT_USE)
public class ChatAttachmentController {

    private final ChatAttachmentService attachments;
    private final AuthService authService;

    public ChatAttachmentController(
            ChatAttachmentService attachments, AuthService authService) {
        this.attachments = attachments;
        this.authService = authService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse upload(
            @PathVariable long sessionId,
            @RequestParam(name = "files", required = false) List<MultipartFile> files,
            HttpServletRequest request) {
        return ApiResponse.ok(attachments.upload(userId(request), sessionId, files));
    }

    @GetMapping
    public ApiResponse pending(@PathVariable long sessionId, HttpServletRequest request) {
        return ApiResponse.ok(attachments.pending(userId(request), sessionId));
    }

    @GetMapping("/{attachmentId}/text")
    public ApiResponse preview(
            @PathVariable long sessionId,
            @PathVariable long attachmentId,
            HttpServletRequest request) {
        return ApiResponse.ok(
                attachments.preview(userId(request), sessionId, attachmentId));
    }

    @GetMapping("/{attachmentId}/file")
    public ResponseEntity<Resource> file(
            @PathVariable long sessionId,
            @PathVariable long attachmentId,
            HttpServletRequest request) {
        ChatAttachmentService.AttachmentFile file =
                attachments.download(userId(request), sessionId, attachmentId);
        ContentDisposition disposition =
                ContentDisposition.attachment()
                        .filename(file.name(), StandardCharsets.UTF_8)
                        .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file.path()));
    }

    @PostMapping("/{attachmentId}/reparse")
    public ApiResponse reparse(
            @PathVariable long sessionId,
            @PathVariable long attachmentId,
            HttpServletRequest request) {
        return ApiResponse.ok(
                attachments.reparse(userId(request), sessionId, attachmentId));
    }

    @DeleteMapping("/{attachmentId}")
    public ApiResponse delete(
            @PathVariable long sessionId,
            @PathVariable long attachmentId,
            HttpServletRequest request) {
        attachments.deletePending(userId(request), sessionId, attachmentId);
        return ApiResponse.ok(java.util.Map.of("deleted", attachmentId));
    }

    private long userId(HttpServletRequest request) {
        return authService.currentUser(request).id();
    }
}
