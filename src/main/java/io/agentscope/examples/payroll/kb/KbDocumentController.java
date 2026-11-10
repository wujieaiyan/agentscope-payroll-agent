package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.kb.KbDtos.BatchDocumentRequest;
import io.agentscope.examples.payroll.kb.KbDtos.SetDocumentTagsRequest;
import io.agentscope.examples.payroll.kb.KbDtos.UpdateDocumentRequest;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.support.Permissions;
import io.agentscope.examples.payroll.web.ApiResponse;
import io.agentscope.examples.payroll.web.RequirePermission;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 知识库文档接口：上传、列表、状态、分块、标签、批量操作与下载。 */
@RestController
@RequestMapping("/api/kb/series/{seriesId}/documents")
@RequirePermission(Permissions.KB_READ)
public class KbDocumentController {

    private final KbDocumentService documentService;
    private final AuthService authService;

    public KbDocumentController(KbDocumentService documentService, AuthService authService) {
        this.documentService = documentService;
        this.authService = authService;
    }

    @GetMapping
    public ApiResponse list(
            @PathVariable long seriesId,
            @RequestParam(value = "directory_id", required = false) Long directoryId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(documentService.list(seriesId, directoryId, status, keyword, user));
    }

    @GetMapping("/status-summary")
    public ApiResponse statusSummary(@PathVariable long seriesId, HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(documentService.statusSummary(seriesId, user));
    }

    @PostMapping
    public ApiResponse upload(
            @PathVariable long seriesId,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "directory_id", required = false) Long directoryId,
            @RequestParam(value = "dedupe_action", required = false) String dedupeAction,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(
                documentService.upload(seriesId, files, directoryId, dedupeAction, user));
    }

    @GetMapping("/{documentId}")
    public ApiResponse get(
            @PathVariable long seriesId,
            @PathVariable long documentId,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(documentService.get(seriesId, documentId, user));
    }

    @PatchMapping("/{documentId}")
    public ApiResponse update(
            @PathVariable long seriesId,
            @PathVariable long documentId,
            @RequestBody UpdateDocumentRequest body,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(documentService.update(seriesId, documentId, body, user));
    }

    @DeleteMapping("/{documentId}")
    public ApiResponse delete(
            @PathVariable long seriesId,
            @PathVariable long documentId,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        documentService.delete(seriesId, documentId, user);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{documentId}/reparse")
    public ApiResponse reparse(
            @PathVariable long seriesId,
            @PathVariable long documentId,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(documentService.reparse(seriesId, documentId, user));
    }

    @PostMapping("/{documentId}/disable")
    public ApiResponse disable(
            @PathVariable long seriesId,
            @PathVariable long documentId,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(documentService.disable(seriesId, documentId, user));
    }

    @PostMapping("/{documentId}/enable")
    public ApiResponse enable(
            @PathVariable long seriesId,
            @PathVariable long documentId,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(documentService.enable(seriesId, documentId, user));
    }

    @GetMapping("/{documentId}/chunks")
    public ApiResponse chunks(
            @PathVariable long seriesId,
            @PathVariable long documentId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(documentService.chunks(seriesId, documentId, page, size, user));
    }

    @PutMapping("/{documentId}/tags")
    public ApiResponse setTags(
            @PathVariable long seriesId,
            @PathVariable long documentId,
            @RequestBody SetDocumentTagsRequest body,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(
                documentService.setTags(
                        seriesId, documentId, body == null ? null : body.tagIds(), user));
    }

    @PostMapping("/batch")
    public ApiResponse batch(
            @PathVariable long seriesId,
            @RequestBody BatchDocumentRequest body,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        return ApiResponse.ok(documentService.batch(seriesId, body, user));
    }

    @GetMapping("/{documentId}/download")
    public ResponseEntity<Resource> download(
            @PathVariable long seriesId,
            @PathVariable long documentId,
            HttpServletRequest request) {
        AuthUser user = authService.currentUser(request);
        KbDocumentService.DownloadFile file = documentService.download(seriesId, documentId, user);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(file.mimeType());
        } catch (RuntimeException e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.name(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(new FileSystemResource(file.path()));
    }
}
