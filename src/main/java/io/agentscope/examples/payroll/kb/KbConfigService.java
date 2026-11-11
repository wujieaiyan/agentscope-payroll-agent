package io.agentscope.examples.payroll.kb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.examples.payroll.db.H2AdminRepository;
import io.agentscope.examples.payroll.domain.AppUser;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.kb.KbConfigModels.ChunkConfig;
import io.agentscope.examples.payroll.kb.KbConfigModels.ConfigPayload;
import io.agentscope.examples.payroll.kb.KbConfigModels.ParseConfig;
import io.agentscope.examples.payroll.kb.KbConfigModels.RetrievalConfig;
import io.agentscope.examples.payroll.kb.KbDtos.ConfigView;
import io.agentscope.examples.payroll.kb.KbDtos.SaveConfigRequest;
import io.agentscope.examples.payroll.kb.KbEnums.IndexStrategy;
import io.agentscope.examples.payroll.kb.KbModels.ConfigVersion;
import io.agentscope.examples.payroll.kb.KbModels.Series;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 知识库解析/分块/检索配置的版本化与校验。 */
@Service
public class KbConfigService {

    private static final Set<String> PARSE_TYPES =
            Set.of("PDF", "DOCX", "XLSX", "XLS", "MD", "MARKDOWN");
    private static final Set<String> CHUNK_STRATEGIES =
            Set.of("DEFAULT", "BY_LINE", "PARENT_CHILD", "TITLE_ENHANCED", "CUSTOM");

    private final KbSeriesStore seriesStore;
    private final KbConfigStore configStore;
    private final KbPermissionService permissionService;
    private final H2AdminRepository adminRepository;
    private final ObjectMapper objectMapper;

    public KbConfigService(
            KbSeriesStore seriesStore,
            KbConfigStore configStore,
            KbPermissionService permissionService,
            H2AdminRepository adminRepository,
            ObjectMapper objectMapper) {
        this.seriesStore = seriesStore;
        this.configStore = configStore;
        this.permissionService = permissionService;
        this.adminRepository = adminRepository;
        this.objectMapper = objectMapper;
    }

    public Series series(long seriesId, AuthUser user) {
        permissionService.requireRead(seriesId, user);
        return seriesStore
                .findById(seriesId, false)
                .orElseThrow(() -> new PayrollException("知识库不存在或已删除"));
    }

    public ConfigView active(long seriesId, AuthUser user) {
        permissionService.requireRead(seriesId, user);
        ConfigVersion version =
                configStore
                        .findLatest(seriesId)
                        .orElseThrow(() -> new PayrollException("知识库尚未配置解析规则"));
        Series series = seriesStore.findById(seriesId, false).orElseThrow();
        return view(version, series.activeConfigVersionId());
    }

    public List<ConfigView> versions(long seriesId, AuthUser user) {
        permissionService.requireRead(seriesId, user);
        Series series = seriesStore.findById(seriesId, false).orElseThrow();
        return configStore.findBySeries(seriesId).stream()
                .map(version -> view(version, series.activeConfigVersionId()))
                .toList();
    }

    public ConfigView version(long seriesId, long versionId, AuthUser user) {
        permissionService.requireRead(seriesId, user);
        Series series = seriesStore.findById(seriesId, false).orElseThrow();
        ConfigVersion version =
                configStore
                        .findByIdAndSeries(versionId, seriesId)
                        .orElseThrow(() -> new PayrollException("配置版本不存在"));
        return view(version, series.activeConfigVersionId());
    }

    public ConfigVersion createInitial(
            long seriesId, IndexStrategy strategy, ConfigPayload payload, long userId) {
        ConfigPayload normalized = normalize(payload);
        validate(normalized);
        return configStore.insert(
                seriesId,
                1,
                strategy,
                write(normalized.parse),
                write(normalized.chunk),
                write(normalized.retrieval),
                "初始化配置",
                userId);
    }

    public ConfigView save(
            long seriesId, SaveConfigRequest request, AuthUser user) {
        permissionService.requireAdmin(seriesId, user);
        Series series =
                seriesStore
                        .findById(seriesId, false)
                        .orElseThrow(() -> new PayrollException("知识库不存在或已删除"));
        IndexStrategy strategy =
                request == null || request.indexStrategy() == null
                        ? series.indexStrategy()
                        : request.indexStrategy();
        if (strategy == IndexStrategy.GRAPH) {
            throw new PayrollException("暂不支持 GRAPH 索引策略，请选择 VECTOR 或 HYBRID");
        }
        ConfigPayload raw = new ConfigPayload();
        if (request != null) {
            raw.parse = request.parseConfig();
            raw.chunk = request.chunkConfig();
            raw.retrieval = request.retrievalConfig();
        }
        ConfigPayload payload = normalize(raw);
        validate(payload);
        int nextVersion =
                configStore.findLatest(seriesId).map(version -> version.versionNo() + 1).orElse(1);
        String note = request == null ? null : request.changeNote();
        ConfigVersion saved =
                configStore.insert(
                        seriesId,
                        nextVersion,
                        strategy,
                        write(payload.parse),
                        write(payload.chunk),
                        write(payload.retrieval),
                        note == null || note.isBlank() ? "更新配置" : note.trim(),
                        user.id());
        seriesStore.update(
                new Series(
                        series.id(),
                        series.name(),
                        series.description(),
                        series.deptId(),
                        series.authorId(),
                        strategy,
                        series.status(),
                        saved.id(),
                        series.docCount(),
                        series.readyDocCount(),
                        series.failedDocCount(),
                        series.chunkCount(),
                        series.rowVersion(),
                        series.createdAt(),
                        series.updatedAt(),
                        series.deletedAt()),
                false);
        return view(saved, saved.id());
    }

    public ConfigPayload payload(ConfigVersion version) {
        ConfigPayload payload = new ConfigPayload();
        payload.parse = read(version.parseConfig(), ParseConfig.class);
        payload.chunk = read(version.chunkConfig(), ChunkConfig.class);
        payload.retrieval = read(version.retrievalConfig(), RetrievalConfig.class);
        normalize(payload);
        return payload;
    }

    public ConfigPayload payloadAt(long seriesId, Long versionId) {
        Optional<ConfigVersion> version =
                versionId == null
                        ? configStore.findLatest(seriesId)
                        : configStore.findByIdAndSeries(versionId, seriesId);
        return version.map(this::payload)
                .orElseGet(
                        () -> {
                            ConfigPayload payload = new ConfigPayload();
                            payload.parse = KbConfigModels.defaultParse();
                            payload.chunk = KbConfigModels.defaultChunk();
                            payload.retrieval = KbConfigModels.defaultRetrieval();
                            return payload;
                        });
    }

    public ConfigVersion requireConfig(long seriesId, Long versionId) {
        if (versionId != null) {
            Optional<ConfigVersion> version = configStore.findByIdAndSeries(versionId, seriesId);
            if (version.isPresent()) {
                return version.get();
            }
        }
        return requireActiveConfig(seriesId);
    }

    public ConfigVersion requireActiveConfig(long seriesId) {
        Series series =
                seriesStore
                        .findById(seriesId, false)
                        .orElseThrow(() -> new PayrollException("知识库不存在或已删除"));
        if (series.activeConfigVersionId() != null) {
            Optional<ConfigVersion> version =
                    configStore.findByIdAndSeries(series.activeConfigVersionId(), seriesId);
            if (version.isPresent()) {
                return version.get();
            }
        }
        return configStore.findLatest(seriesId).orElse(null);
    }

    private ConfigView view(ConfigVersion version, Long activeId) {
        String createdByName =
                adminRepository
                        .findUser(version.createdBy())
                        .map(AppUser::displayName)
                        .orElse("用户#" + version.createdBy());
        ConfigPayload payload = payload(version);
        return new ConfigView(
                version.id(),
                version.versionNo(),
                version.indexStrategy(),
                payload.parse,
                payload.chunk,
                payload.retrieval,
                version.changeNote(),
                version.createdBy(),
                createdByName,
                activeId != null && activeId == version.id(),
                version.createdAt());
    }

    /** 缺省字段补齐默认值；null 表示使用默认配置。 */
    public static ConfigPayload normalize(ConfigPayload input) {
        ConfigPayload payload = new ConfigPayload();
        payload.parse = input == null || input.parse == null ? KbConfigModels.defaultParse() : input.parse;
        payload.chunk = input == null || input.chunk == null ? KbConfigModels.defaultChunk() : input.chunk;
        payload.retrieval =
                input == null || input.retrieval == null
                        ? KbConfigModels.defaultRetrieval()
                        : input.retrieval;
        if (payload.parse.docTypes == null || payload.parse.docTypes.isEmpty()) {
            payload.parse.docTypes = KbConfigModels.defaultParse().docTypes;
        }
        payload.parse.docTypes =
                payload.parse.docTypes.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(value -> value.trim().toUpperCase(Locale.ROOT))
                        .distinct()
                        .toList();
        if (payload.parse.perType == null || payload.parse.perType.isEmpty()) {
            payload.parse.perType = KbConfigModels.defaultParse().perType;
        }
        if (payload.parse.ocr == null) {
            payload.parse.ocr = new KbConfigModels.OcrConfig();
        }
        if (payload.parse.options == null) {
            payload.parse.options = new KbConfigModels.ParseOptions();
        }
        if (payload.chunk.common == null) {
            payload.chunk.common = new KbConfigModels.CommonChunkConfig();
        }
        if (payload.chunk.byLine == null) {
            payload.chunk.byLine = new KbConfigModels.ByLineConfig();
        }
        if (payload.chunk.parentChild == null) {
            payload.chunk.parentChild = new KbConfigModels.ParentChildConfig();
        }
        if (payload.chunk.titleEnhanced == null) {
            payload.chunk.titleEnhanced = new KbConfigModels.TitleEnhancedConfig();
        }
        if (payload.chunk.custom == null) {
            payload.chunk.custom = new KbConfigModels.CustomConfig();
        }
        if (payload.chunk.strategy == null || payload.chunk.strategy.isBlank()) {
            payload.chunk.strategy = "DEFAULT";
        }
        payload.chunk.strategy = payload.chunk.strategy.trim().toUpperCase(Locale.ROOT);
        if (payload.retrieval.citation == null) {
            payload.retrieval.citation = new KbConfigModels.CitationConfig();
        }
        return payload;
    }

    public static void validate(ConfigPayload payload) {
        ParseConfig parse = payload.parse;
        if (parse.docTypes.isEmpty()) {
            throw new PayrollException("解析配置：至少选择一种文档类型");
        }
        for (String type : parse.docTypes) {
            if (!PARSE_TYPES.contains(type)) {
                throw new PayrollException("解析配置：不支持的文档类型「" + type + "」");
            }
        }
        for (String type : parse.docTypes) {
            KbConfigModels.TypeParseConfig typeConfig = parse.perType.get(type);
            if (typeConfig == null) {
                throw new PayrollException("解析配置：缺少 " + type + " 的解析策略");
            }
            String strategy = typeConfig.strategy == null ? "" : typeConfig.strategy.toUpperCase(Locale.ROOT);
            if (!strategy.equals("NATIVE") && !strategy.equals("AUTO") && !strategy.equals("OCR")) {
                throw new PayrollException(
                        "解析配置.perType." + type + ".strategy 仅支持 NATIVE/AUTO/OCR");
            }
        }
        if (parse.ocr.minConfidence < 0 || parse.ocr.minConfidence > 1) {
            throw new PayrollException("解析配置.ocr.minConfidence 必须在 0 到 1 之间");
        }

        ChunkConfig chunk = payload.chunk;
        if (!CHUNK_STRATEGIES.contains(chunk.strategy)) {
            throw new PayrollException(
                    "分块配置.strategy 仅支持 " + String.join("/", CHUNK_STRATEGIES));
        }
        KbConfigModels.CommonChunkConfig common = chunk.common;
        if (common.maxTokens < 64 || common.maxTokens > 4096) {
            throw new PayrollException("分块配置.common.maxTokens 必须在 64 到 4096 之间");
        }
        if (common.overlapTokens < 0 || common.overlapTokens > common.maxTokens / 2) {
            throw new PayrollException("分块配置.common.overlapTokens 不能超过 maxTokens 的一半");
        }
        if (common.minTokens < 0 || common.minTokens > common.maxTokens) {
            throw new PayrollException("分块配置.common.minTokens 不能大于 maxTokens");
        }
        KbConfigModels.ParentChildConfig parentChild = chunk.parentChild;
        if (parentChild.parentTokens < 256 || parentChild.parentTokens > 4096) {
            throw new PayrollException("分块配置.parentChild.parentTokens 必须在 256 到 4096 之间");
        }
        if (parentChild.childTokens < 64 || parentChild.childTokens > parentChild.parentTokens / 2) {
            throw new PayrollException(
                    "分块配置.parentChild.childTokens 必须在 64 到父块一半之间");
        }
        if (parentChild.childOverlap < 0
                || parentChild.childOverlap > parentChild.childTokens / 2) {
            throw new PayrollException("分块配置.parentChild.childOverlap 不能超过 childTokens 的一半");
        }
        if (parentChild.maxChildrenPerParent < 1 || parentChild.maxChildrenPerParent > 32) {
            throw new PayrollException(
                    "分块配置.parentChild.maxChildrenPerParent 必须在 1 到 32 之间");
        }
        if (chunk.byLine.maxTokens < 64 || chunk.byLine.maxTokens > 4096) {
            throw new PayrollException("分块配置.byLine.maxTokens 必须在 64 到 4096 之间");
        }
        if (chunk.custom.separator == null || chunk.custom.separator.isEmpty()) {
            throw new PayrollException("分块配置.custom.separator 不能为空");
        }

        RetrievalConfig retrieval = payload.retrieval;
        if (retrieval.vectorWeight < 0 || retrieval.vectorWeight > 1) {
            throw new PayrollException("检索配置.vectorWeight 必须在 0 到 1 之间");
        }
        if (retrieval.recallTopK < 1 || retrieval.recallTopK > 100) {
            throw new PayrollException("检索配置.recallTopK 必须在 1 到 100 之间");
        }
        if (retrieval.rerankTopN < 1 || retrieval.rerankTopN > retrieval.recallTopK) {
            throw new PayrollException("检索配置.rerankTopN 必须在 1 到 recallTopK 之间");
        }
        if (retrieval.similarityThreshold < 0 || retrieval.similarityThreshold > 1) {
            throw new PayrollException("检索配置.similarityThreshold 必须在 0 到 1 之间");
        }
        String granularity =
                retrieval.citation.granularity == null
                        ? ""
                        : retrieval.citation.granularity.toUpperCase(Locale.ROOT);
        if (!granularity.equals("SENTENCE")
                && !granularity.equals("PARAGRAPH")
                && !granularity.equals("CHUNK")) {
            throw new PayrollException(
                    "检索配置.citation.granularity 仅支持 SENTENCE/PARAGRAPH/CHUNK");
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new PayrollException("配置序列化失败：" + e.getOriginalMessage());
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new PayrollException("配置解析失败：" + e.getOriginalMessage());
        }
    }
}
