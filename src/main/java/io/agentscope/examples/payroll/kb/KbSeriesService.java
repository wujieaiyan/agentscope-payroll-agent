package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.db.H2AdminRepository;
import io.agentscope.examples.payroll.domain.AppUser;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.Department;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.kb.KbDtos.CreateSeriesRequest;
import io.agentscope.examples.payroll.kb.KbDtos.PageResult;
import io.agentscope.examples.payroll.kb.KbDtos.SeriesView;
import io.agentscope.examples.payroll.kb.KbDtos.UpdateSeriesRequest;
import io.agentscope.examples.payroll.kb.KbEnums.IndexStrategy;
import io.agentscope.examples.payroll.kb.KbEnums.KbRole;
import io.agentscope.examples.payroll.kb.KbEnums.SeriesStatus;
import io.agentscope.examples.payroll.kb.KbEnums.SubjectType;
import io.agentscope.examples.payroll.kb.KbModels.Document;
import io.agentscope.examples.payroll.kb.KbModels.Series;
import io.agentscope.examples.payroll.service.AuditService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 知识库系列的业务逻辑：创建、配置初始版本、成员、启停与删除。 */
@Service
public class KbSeriesService {

    private final KbSeriesStore seriesStore;
    private final KbMemberStore memberStore;
    private final KbConfigStore configStore;
    private final KbConfigService configService;
    private final KbDirectoryStore directoryStore;
    private final KbDocumentStore documentStore;
    private final KbChunkStore chunkStore;
    private final KbTagStore tagStore;
    private final KbTaskStore taskStore;
    private final KbStorageService storageService;
    private final KbElasticsearchService elasticsearchService;
    private final KbPermissionService permissionService;
    private final H2AdminRepository adminRepository;
    private final AuditService auditService;

    public KbSeriesService(
            KbSeriesStore seriesStore,
            KbMemberStore memberStore,
            KbConfigStore configStore,
            KbConfigService configService,
            KbDirectoryStore directoryStore,
            KbDocumentStore documentStore,
            KbChunkStore chunkStore,
            KbTagStore tagStore,
            KbTaskStore taskStore,
            KbStorageService storageService,
            KbElasticsearchService elasticsearchService,
            KbPermissionService permissionService,
            H2AdminRepository adminRepository,
            AuditService auditService) {
        this.seriesStore = seriesStore;
        this.memberStore = memberStore;
        this.configStore = configStore;
        this.configService = configService;
        this.directoryStore = directoryStore;
        this.documentStore = documentStore;
        this.chunkStore = chunkStore;
        this.tagStore = tagStore;
        this.taskStore = taskStore;
        this.storageService = storageService;
        this.elasticsearchService = elasticsearchService;
        this.permissionService = permissionService;
        this.adminRepository = adminRepository;
        this.auditService = auditService;
    }

    public PageResult<SeriesView> list(
            AuthUser user,
            String scope,
            String keyword,
            Long deptId,
            String status,
            int page,
            int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size <= 0 ? 20 : size, 100));
        Map<Long, KbRole> roles = permissionService.visibleRoles(user);
        boolean admin = permissionService.platformAdmin(user);
        SeriesStatus statusFilter =
                status == null || status.isBlank()
                        ? null
                        : SeriesStatus.parse(status, null);
        String needle =
                keyword == null || keyword.isBlank()
                        ? null
                        : keyword.trim().toLowerCase(Locale.ROOT);
        List<Series> all =
                seriesStore.findAll().stream()
                        .filter(series -> admin || roles.containsKey(series.id()))
                        .filter(series -> !"mine".equalsIgnoreCase(scope) || isMine(series, user))
                        .filter(series -> deptId == null || series.deptId() == deptId)
                        .filter(series -> statusFilter == null || series.status() == statusFilter)
                        .filter(
                                series ->
                                        needle == null
                                                || series.name().toLowerCase(Locale.ROOT).contains(needle)
                                                || (series.description() != null
                                                        && series.description()
                                                                .toLowerCase(Locale.ROOT)
                                                                .contains(needle)))
                        .toList();
        int from = Math.min((safePage - 1) * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        List<SeriesView> items =
                all.subList(from, to).stream()
                        .map(
                                series ->
                                        view(
                                                series,
                                                admin
                                                        ? KbRole.OWNER
                                                        : roles.getOrDefault(series.id(), KbRole.READER)))
                        .toList();
        return new PageResult<>(items, all.size(), safePage, safeSize);
    }

    public SeriesView get(long seriesId, AuthUser user) {
        KbRole role = permissionService.requireRead(seriesId, user);
        Series series = requireSeries(seriesId);
        return view(series, role);
    }

    public SeriesView create(CreateSeriesRequest request, AuthUser user) {
        if (request == null) {
            throw new PayrollException("知识库信息不能为空");
        }
        String name = request.name() == null ? "" : request.name().trim();
        if (name.length() < 2 || name.length() > 50) {
            throw new PayrollException("知识库名称长度需在 2 到 50 个字符之间");
        }
        String description = request.description() == null ? "" : request.description().trim();
        if (description.length() > 200) {
            throw new PayrollException("知识库描述不能超过 200 个字符");
        }
        long deptId =
                request.deptId() != null
                        ? request.deptId()
                        : user.departmentId() == null ? 0L : user.departmentId();
        if (deptId <= 0) {
            throw new PayrollException("请选择所属部门");
        }
        Department department =
                adminRepository
                        .findDepartment(deptId)
                        .orElseThrow(() -> new PayrollException("所属部门不存在"));
        if (!department.active()) {
            throw new PayrollException("所属部门已停用");
        }
        IndexStrategy strategy =
                request.indexStrategy() == null ? IndexStrategy.HYBRID : request.indexStrategy();
        if (strategy == IndexStrategy.GRAPH) {
            throw new PayrollException("暂不支持 GRAPH 索引策略，请选择 VECTOR 或 HYBRID");
        }
        boolean draft = Boolean.TRUE.equals(request.draft());
        Series created =
                seriesStore.insert(
                        new Series(
                                0L,
                                name,
                                description,
                                deptId,
                                user.id(),
                                strategy,
                                draft ? SeriesStatus.DRAFT : SeriesStatus.ACTIVE,
                                null,
                                0,
                                0,
                                0,
                                0L,
                                1L,
                                null,
                                null,
                                null));
        directoryStore.insert(created.id(), null, "根目录", "/", 0, true, 0, user.id());
        KbModels.ConfigVersion version =
                configService.createInitial(created.id(), strategy, request.config(), user.id());
        Series active =
                new Series(
                        created.id(),
                        created.name(),
                        created.description(),
                        created.deptId(),
                        created.authorId(),
                        strategy,
                        created.status(),
                        version.id(),
                        0,
                        0,
                        0,
                        0L,
                        created.rowVersion(),
                        created.createdAt(),
                        created.updatedAt(),
                        null);
        seriesStore.update(active, false);
        memberStore.upsert(created.id(), SubjectType.USER, user.id(), KbRole.OWNER, user.id());
        SeriesView view = view(requireSeries(created.id()), KbRole.OWNER);
        auditService.record(
                "kb.series.create", "kb_series", String.valueOf(created.id()), null, view);
        return view;
    }

    public SeriesView update(long seriesId, UpdateSeriesRequest request, AuthUser user) {
        permissionService.requireAdmin(seriesId, user);
        Series series = requireSeries(seriesId);
        if (request == null) {
            throw new PayrollException("请求内容不能为空");
        }
        if (request.rowVersion() != null && request.rowVersion() != series.rowVersion()) {
            throw new PayrollConflictException("数据已被其他用户修改，请刷新后重试");
        }
        String name =
                request.name() == null || request.name().isBlank()
                        ? series.name()
                        : request.name().trim();
        if (name.length() < 2 || name.length() > 50) {
            throw new PayrollException("知识库名称长度需在 2 到 50 个字符之间");
        }
        String description =
                request.description() == null
                        ? series.description()
                        : request.description().trim();
        if (description.length() > 200) {
            throw new PayrollException("知识库描述不能超过 200 个字符");
        }
        long deptId = request.deptId() == null ? series.deptId() : request.deptId();
        Department department =
                adminRepository
                        .findDepartment(deptId)
                        .orElseThrow(() -> new PayrollException("所属部门不存在"));
        if (!department.active()) {
            throw new PayrollException("所属部门已停用");
        }
        Series updated =
                new Series(
                        series.id(),
                        name,
                        description,
                        deptId,
                        series.authorId(),
                        series.indexStrategy(),
                        series.status(),
                        series.activeConfigVersionId(),
                        series.docCount(),
                        series.readyDocCount(),
                        series.failedDocCount(),
                        series.chunkCount(),
                        series.rowVersion(),
                        series.createdAt(),
                        series.updatedAt(),
                        series.deletedAt());
        seriesStore.update(updated, true);
        SeriesView view = view(requireSeries(seriesId), permissionService.requireRead(seriesId, user));
        auditService.record(
                "kb.series.update", "kb_series", String.valueOf(seriesId), series, view);
        return view;
    }

    public SeriesView setStatus(long seriesId, boolean enabled, AuthUser user) {
        permissionService.requireAdmin(seriesId, user);
        Series series = requireSeries(seriesId);
        if (series.status() == SeriesStatus.DELETED) {
            throw new PayrollException("知识库已删除");
        }
        seriesStore.updateStatus(seriesId, enabled ? SeriesStatus.ACTIVE : SeriesStatus.DISABLED);
        SeriesView view = view(requireSeries(seriesId), permissionService.requireRead(seriesId, user));
        auditService.record(
                enabled ? "kb.series.enable" : "kb.series.disable",
                "kb_series",
                String.valueOf(seriesId),
                series.status(),
                view);
        return view;
    }

    public void delete(long seriesId, AuthUser user) {
        permissionService.requireOwner(seriesId, user);
        Series series = requireSeries(seriesId);
        List<Document> documents = documentStore.findBySeries(seriesId, null, null, null);
        for (Document document : documents) {
            documentStore.softDelete(document.id());
        }
        chunkStore.deleteBySeries(seriesId);
        tagStore.deleteSeriesTags(seriesId);
        taskStore.deleteBySeries(seriesId);
        memberStore.deleteBySeries(seriesId);
        configStore.deleteBySeries(seriesId);
        directoryStore.deleteBySeries(seriesId);
        elasticsearchService.deleteSeries(seriesId);
        storageService.deleteSeriesDirectory(seriesId);
        seriesStore.softDelete(seriesId);
        auditService.record(
                "kb.series.delete", "kb_series", String.valueOf(seriesId), series, null);
    }

    public Map<String, Object> stats(long seriesId, AuthUser user) {
        permissionService.requireRead(seriesId, user);
        requireSeries(seriesId);
        int[] counters = documentStore.countStatus(seriesId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentCount", counters[0]);
        result.put("readyCount", counters[1]);
        result.put("failedCount", counters[2]);
        result.put("chunkCount", chunkStore.countBySeries(seriesId));
        return result;
    }

    public void refreshCounters(long seriesId) {
        int[] counters = documentStore.countStatus(seriesId);
        long chunks = chunkStore.countBySeries(seriesId);
        seriesStore.updateCounters(seriesId, counters[0], counters[1], counters[2], chunks);
    }

    public Series requireSeries(long seriesId) {
        return seriesStore
                .findById(seriesId, false)
                .orElseThrow(() -> new PayrollException("知识库不存在或已删除"));
    }

    public SeriesView view(Series series, KbRole role) {
        String deptName =
                adminRepository
                        .findDepartment(series.deptId())
                        .map(Department::name)
                        .orElse("部门#" + series.deptId());
        String authorName =
                series.authorId() == 0
                        ? "系统管理员"
                        : adminRepository
                                .findUser(series.authorId())
                                .map(AppUser::displayName)
                                .orElse("用户#" + series.authorId());
        return new SeriesView(
                series.id(),
                series.name(),
                series.description(),
                series.deptId(),
                deptName,
                series.authorId(),
                authorName,
                series.indexStrategy(),
                series.status(),
                displayStatus(series),
                series.activeConfigVersionId(),
                series.docCount(),
                series.readyDocCount(),
                series.failedDocCount(),
                series.chunkCount(),
                series.rowVersion(),
                role,
                series.createdAt(),
                series.updatedAt());
    }

    static String displayStatus(Series series) {
        if (series.status() == SeriesStatus.DISABLED) {
            return "DISABLED";
        }
        if (series.status() == SeriesStatus.DRAFT || series.docCount() == 0) {
            return series.docCount() == 0 ? "EMPTY" : "INITIALIZING";
        }
        if (series.failedDocCount() > 0) {
            return "PARTIAL";
        }
        if (series.readyDocCount() < series.docCount()) {
            return "INITIALIZING";
        }
        return "NORMAL";
    }

    private boolean isMine(Series series, AuthUser user) {
        return series.authorId() == user.id()
                || memberStore
                        .findOne(series.id(), SubjectType.USER, user.id())
                        .isPresent();
    }
}
