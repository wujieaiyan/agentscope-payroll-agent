package io.agentscope.examples.payroll.kb;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.agentscope.examples.payroll.kb.KbEnums.ChunkLevel;
import io.agentscope.examples.payroll.kb.KbEnums.DocStatus;
import io.agentscope.examples.payroll.kb.KbEnums.IndexStrategy;
import io.agentscope.examples.payroll.kb.KbEnums.KbRole;
import io.agentscope.examples.payroll.kb.KbEnums.SeriesStatus;
import io.agentscope.examples.payroll.kb.KbEnums.SubjectType;
import io.agentscope.examples.payroll.kb.KbEnums.TaskStatus;
import io.agentscope.examples.payroll.kb.KbEnums.TaskType;
import java.time.Instant;

/** 知识库数据库行模型，字段与 kb_* 表一一对应。 */
public final class KbModels {

    private KbModels() {
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Series(
            long id,
            String name,
            String description,
            long deptId,
            long authorId,
            IndexStrategy indexStrategy,
            SeriesStatus status,
            Long activeConfigVersionId,
            int docCount,
            int readyDocCount,
            int failedDocCount,
            long chunkCount,
            long rowVersion,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt) {
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Member(
            long id,
            long seriesId,
            SubjectType subjectType,
            long subjectId,
            KbRole role,
            long grantedBy,
            Instant createdAt,
            Instant updatedAt) {
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record ConfigVersion(
            long id,
            long seriesId,
            int versionNo,
            IndexStrategy indexStrategy,
            String parseConfig,
            String chunkConfig,
            String retrievalConfig,
            String changeNote,
            long createdBy,
            Instant createdAt) {
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Directory(
            long id,
            long seriesId,
            Long parentId,
            String name,
            String path,
            int depth,
            boolean root,
            int sortOrder,
            long createdBy,
            Instant createdAt,
            Instant updatedAt) {
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Document(
            long id,
            long seriesId,
            long directoryId,
            String name,
            String ext,
            String mimeType,
            long sizeBytes,
            String sha256,
            String storageKey,
            Integer pageCount,
            Long charCount,
            DocStatus status,
            String errorCode,
            String errorMessage,
            int chunkCount,
            Long configVersionId,
            long uploadedBy,
            long rowVersion,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt) {
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Chunk(
            long id,
            long seriesId,
            long documentId,
            Long parentChunkId,
            ChunkLevel level,
            int seq,
            String content,
            String contentHash,
            int tokenCount,
            Integer charStart,
            Integer charEnd,
            Integer pageNo,
            String bbox,
            String titlePath,
            String metadata,
            String vector,
            String vectorSource,
            String status,
            Instant createdAt) {
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Tag(long id, long seriesId, String name, String color, long createdBy, Instant createdAt) {
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record IngestTask(
            long id,
            long seriesId,
            Long documentId,
            TaskType type,
            TaskStatus status,
            int progress,
            String stage,
            int retryCount,
            Long retryOfTaskId,
            Long configVersionId,
            String idempotencyKey,
            String payload,
            String failedItems,
            String errorCode,
            String errorMessage,
            long operatorId,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record TaskLog(
            long id, long taskId, String stage, String level, String message, Integer costMs, Instant createdAt) {
    }
}
