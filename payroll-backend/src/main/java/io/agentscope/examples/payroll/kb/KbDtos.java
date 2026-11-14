package io.agentscope.examples.payroll.kb;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.agentscope.examples.payroll.kb.KbConfigModels.ChunkConfig;
import io.agentscope.examples.payroll.kb.KbConfigModels.ParseConfig;
import io.agentscope.examples.payroll.kb.KbConfigModels.RetrievalConfig;
import io.agentscope.examples.payroll.kb.KbEnums.ChunkLevel;
import io.agentscope.examples.payroll.kb.KbEnums.DocStatus;
import io.agentscope.examples.payroll.kb.KbEnums.IndexStrategy;
import io.agentscope.examples.payroll.kb.KbEnums.KbRole;
import io.agentscope.examples.payroll.kb.KbEnums.SeriesStatus;
import io.agentscope.examples.payroll.kb.KbEnums.SubjectType;
import io.agentscope.examples.payroll.kb.KbEnums.TaskStatus;
import io.agentscope.examples.payroll.kb.KbEnums.TaskType;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 知识库接口 DTO；显式保持 camelCase，避免全局 SNAKE_CASE 影响前端契约。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@JacksonAnnotationsInside
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
@interface KbCamel {
}

public final class KbDtos {

    private KbDtos() {
    }

    @KbCamel
    public record PageResult<T>(List<T> items, long total, int page, int size) {
    }

    @KbCamel
    public record SeriesView(
            long id,
            String name,
            String description,
            long deptId,
            String deptName,
            long authorId,
            String authorName,
            IndexStrategy indexStrategy,
            SeriesStatus status,
            String displayStatus,
            Long activeConfigVersionId,
            int docCount,
            int readyDocCount,
            int failedDocCount,
            long chunkCount,
            long rowVersion,
            KbRole role,
            Instant createdAt,
            Instant updatedAt) {
    }

    @KbCamel
    public record CreateSeriesRequest(
            String name,
            Long deptId,
            String description,
            IndexStrategy indexStrategy,
            Boolean draft,
            KbConfigModels.ConfigPayload config) {
    }

    @KbCamel
    public record UpdateSeriesRequest(String name, String description, Long deptId, Long rowVersion) {
    }

    @KbCamel
    public record ConfigView(
            long versionId,
            int versionNo,
            IndexStrategy indexStrategy,
            ParseConfig parseConfig,
            ChunkConfig chunkConfig,
            RetrievalConfig retrievalConfig,
            String changeNote,
            long createdBy,
            String createdByName,
            boolean active,
            Instant createdAt) {
    }

    @KbCamel
    public record SaveConfigRequest(
            IndexStrategy indexStrategy,
            ParseConfig parseConfig,
            ChunkConfig chunkConfig,
            RetrievalConfig retrievalConfig,
            String changeNote,
            String rebuildMode) {
    }

    @KbCamel
    public record MemberView(
            long id,
            long seriesId,
            SubjectType subjectType,
            long subjectId,
            String subjectName,
            String subjectExtra,
            KbRole role,
            long grantedBy,
            String grantedByName,
            Instant createdAt) {
    }

    @KbCamel
    public record GrantMembersRequest(SubjectType subjectType, List<Long> subjectIds, KbRole role) {
    }

    @KbCamel
    public record UpdateMemberRoleRequest(KbRole role) {
    }

    @KbCamel
    public record DirectoryView(
            long id,
            long seriesId,
            Long parentId,
            String name,
            String path,
            int depth,
            boolean root,
            int sortOrder,
            long documentCount,
            List<DirectoryView> children) {
    }

    @KbCamel
    public record CreateDirectoryRequest(String name, Long parentId, Integer sortOrder) {
    }

    @KbCamel
    public record RenameDirectoryRequest(String name, Integer sortOrder) {
    }

    @KbCamel
    public record MoveDirectoryRequest(Long parentId, Integer sortOrder) {
    }

    @KbCamel
    public record DeleteDirectoryRequest(String confirmName) {
    }

    @KbCamel
    public record TagView(long id, String name, String color, int documentCount) {
    }

    @KbCamel
    public record SaveTagRequest(String name, String color) {
    }

    @KbCamel
    public record DocumentView(
            long id,
            long seriesId,
            long directoryId,
            String directoryPath,
            String name,
            String ext,
            String mimeType,
            long sizeBytes,
            Integer pageCount,
            Long charCount,
            DocStatus status,
            String errorCode,
            String errorMessage,
            int chunkCount,
            Long configVersionId,
            long uploadedBy,
            String uploadedByName,
            List<TagView> tags,
            Instant createdAt,
            Instant updatedAt) {
    }

    @KbCamel
    public record DocumentStatusSummary(
            int total,
            int pending,
            int parsing,
            int chunking,
            int indexing,
            int ready,
            int failed,
            int disabled) {
    }

    @KbCamel
    public record UpdateDocumentRequest(String name, Long directoryId) {
    }

    @KbCamel
    public record SetDocumentTagsRequest(List<Long> tagIds) {
    }

    @KbCamel
    public record BatchDocumentRequest(
            String action, List<Long> documentIds, Long directoryId, List<Long> tagIds) {
    }

    @KbCamel
    public record BatchDocumentResult(
            int succeeded, int failed, List<Long> failedIds, List<String> messages) {
    }

    @KbCamel
    public record UploadResult(
            int uploaded, int skipped, int failed, List<DocumentView> documents,
            List<String> messages) {
    }

    @KbCamel
    public record ChunkView(
            long id,
            int seq,
            ChunkLevel level,
            Long parentChunkId,
            String content,
            int tokenCount,
            Integer pageNo,
            String titlePath,
            String status) {
    }

    @KbCamel
    public record TaskView(
            long id,
            long seriesId,
            Long documentId,
            String documentName,
            TaskType type,
            TaskStatus status,
            int progress,
            String stage,
            int retryCount,
            Long retryOfTaskId,
            String errorCode,
            String errorMessage,
            long operatorId,
            String operatorName,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    @KbCamel
    public record TaskLogView(
            long id, String stage, String level, String message, Integer costMs, Instant createdAt) {
    }

    @KbCamel
    public record TaskDetailView(TaskView task, List<TaskLogView> logs) {
    }

    @KbCamel
    public record RetrievalTestRequest(
            String query, Integer topK, Double threshold, Boolean withAnswer) {
    }

    @KbCamel
    public record RebuildRequest(String mode, List<Long> documentIds) {
    }

    @KbCamel
    public record RetrievalHit(
            long chunkId,
            long documentId,
            String documentName,
            double score,
            double vectorScore,
            double keywordScore,
            String content,
            String parentContent,
            String titlePath,
            Integer pageNo,
            String pointId,
            List<String> routes,
            Map<String, Integer> routeRanks,
            int mergedChunks) {

        public RetrievalHit {
            routes = routes == null ? List.of() : List.copyOf(routes);
            routeRanks = routeRanks == null ? Map.of() : Map.copyOf(routeRanks);
            mergedChunks = Math.max(1, mergedChunks);
        }

        public RetrievalHit(
                long chunkId,
                long documentId,
                String documentName,
                double score,
                double vectorScore,
                double keywordScore,
                String content,
                String parentContent,
                String titlePath,
                Integer pageNo) {
            this(
                    chunkId,
                    documentId,
                    documentName,
                    score,
                    vectorScore,
                    keywordScore,
                    content,
                    parentContent,
                    titlePath,
                    pageNo,
                    null,
                    List.of(),
                    Map.of(),
                    1);
        }
    }

    @KbCamel
    public record RetrievalResult(
            String query,
            String strategy,
            long tookMs,
            List<RetrievalHit> hits,
            String answer,
            boolean vectorEnabled,
            Map<String, Object> diagnostics) {
    }
}
