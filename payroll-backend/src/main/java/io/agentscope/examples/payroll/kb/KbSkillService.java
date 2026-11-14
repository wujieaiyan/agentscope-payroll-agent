package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.agent.KbQueryTools;
import io.agentscope.examples.payroll.agent.KbTools;
import io.agentscope.examples.payroll.db.H2AdminRepository;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.Department;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollForbiddenException;
import io.agentscope.examples.payroll.kb.KbConfigModels.ConfigPayload;
import io.agentscope.examples.payroll.kb.KbDtos.ConfigView;
import io.agentscope.examples.payroll.kb.KbDtos.CreateSeriesRequest;
import io.agentscope.examples.payroll.kb.KbDtos.GrantMembersRequest;
import io.agentscope.examples.payroll.kb.KbDtos.MoveDirectoryRequest;
import io.agentscope.examples.payroll.kb.KbDtos.RetrievalHit;
import io.agentscope.examples.payroll.kb.KbDtos.RetrievalResult;
import io.agentscope.examples.payroll.kb.KbDtos.RetrievalTestRequest;
import io.agentscope.examples.payroll.kb.KbDtos.SaveConfigRequest;
import io.agentscope.examples.payroll.kb.KbDtos.SeriesView;
import io.agentscope.examples.payroll.kb.KbDtos.TaskView;
import io.agentscope.examples.payroll.kb.KbEnums.IndexStrategy;
import io.agentscope.examples.payroll.kb.KbEnums.KbRole;
import io.agentscope.examples.payroll.kb.KbEnums.SubjectType;
import io.agentscope.examples.payroll.service.KbSkillGateway;
import io.agentscope.examples.payroll.support.Permissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 知识库对话工具的服务端分发：沿用页面同一套服务与库级权限，不新开写路径。
 *
 * <p>只读工具要求 {@code kb:read}，新建库要求 {@code kb:create}，其余写操作要求
 * {@code kb:manage}；库内角色仍由各服务自行校验（保存配置/成员为 ADMIN，移动目录为 EDITOR）。
 */
@Service
public class KbSkillService {

    private static final Logger log = LoggerFactory.getLogger(KbSkillService.class);

    private static final int MAX_HIT_CONTENT = 1200;
    private static final int MAX_TOTAL_CONTENT = 6000;
    private static final int MAX_SEARCH_SERIES = 10;

    private static final List<String> READ_TOOLS =
            List.of(
                    KbQueryTools.KB_SEARCH,
                    KbTools.LIST_LIBRARIES,
                    KbTools.GET_LIBRARY,
                    KbTools.GET_CONFIG,
                    KbTools.LIST_DOCUMENTS,
                    KbTools.LIST_TASKS,
                    KbTools.LIST_MEMBERS,
                    KbTools.LIST_DIRECTORIES);

    private static final List<String> TOOLS =
            List.of(
                    KbQueryTools.KB_SEARCH,
                    KbTools.LIST_LIBRARIES,
                    KbTools.GET_LIBRARY,
                    KbTools.GET_CONFIG,
                    KbTools.LIST_DOCUMENTS,
                    KbTools.LIST_TASKS,
                    KbTools.LIST_MEMBERS,
                    KbTools.LIST_DIRECTORIES,
                    KbTools.CREATE_LIBRARY,
                    KbTools.SAVE_CONFIG,
                    KbTools.GRANT_MEMBER,
                    KbTools.REMOVE_MEMBER,
                    KbTools.MOVE_DIRECTORY,
                    KbTools.REBUILD_INDEX);

    private final KbSeriesService seriesService;
    private final KbConfigService configService;
    private final KbDirectoryService directoryService;
    private final KbMemberService memberService;
    private final KbTaskService taskService;
    private final KbDocumentService documentService;
    private final KbPermissionService permissionService;
    private final KbRetrievalService retrievalService;
    private final H2AdminRepository adminRepository;

    public KbSkillService(
            KbSeriesService seriesService,
            KbConfigService configService,
            KbDirectoryService directoryService,
            KbMemberService memberService,
            KbTaskService taskService,
            KbDocumentService documentService,
            KbPermissionService permissionService,
            KbRetrievalService retrievalService,
            H2AdminRepository adminRepository) {
        this.seriesService = seriesService;
        this.configService = configService;
        this.directoryService = directoryService;
        this.memberService = memberService;
        this.taskService = taskService;
        this.documentService = documentService;
        this.permissionService = permissionService;
        this.retrievalService = retrievalService;
        this.adminRepository = adminRepository;
    }

    public static List<String> toolNames() {
        return TOOLS;
    }

    public static boolean readOnly(String tool) {
        return READ_TOOLS.contains(tool);
    }

    public static String requiredPermission(String tool) {
        if (READ_TOOLS.contains(tool)) {
            return Permissions.KB_READ;
        }
        if (KbTools.CREATE_LIBRARY.equals(tool)) {
            return Permissions.KB_CREATE;
        }
        return Permissions.KB_MANAGE;
    }

    /** 内部接口按用户 ID 还原登录身份；关闭认证时与页面一致使用内置管理员。 */
    public AuthUser resolveUser(long userId) {
        if (userId <= 0) {
            return AuthUser.system();
        }
        return adminRepository
                .findAuthUser(userId)
                .orElseThrow(() -> new PayrollForbiddenException("账号不存在或已停用：" + userId));
    }

    public KbSkillGateway.Check check(AuthUser user, String tool, Map<String, Object> arguments) {
        if (user == null) {
            return KbSkillGateway.Check.denied("知识库操作缺少登录身份");
        }
        String permission;
        try {
            permission = requiredPermission(tool);
            requireTool(tool);
        } catch (PayrollException e) {
            return KbSkillGateway.Check.denied(e.getMessage());
        }
        if (!user.can(permission)) {
            return KbSkillGateway.Check.denied(
                    "当前账号缺少知识库权限：" + Permissions.label(permission));
        }
        try {
            KbRole minimum = minimumRole(tool);
            if (minimum != null) {
                long seriesId = longArg(arguments, "series_id");
                permissionService.require(seriesId, user, minimum);
            }
            return KbSkillGateway.Check.granted();
        } catch (PayrollForbiddenException e) {
            return KbSkillGateway.Check.denied(e.getMessage());
        } catch (PayrollException e) {
            return KbSkillGateway.Check.denied(e.getMessage());
        }
    }

    public Object execute(AuthUser user, String tool, Map<String, Object> arguments) {
        requireTool(tool);
        String permission = requiredPermission(tool);
        if (!user.can(permission)) {
            throw new PayrollForbiddenException(
                    "当前账号缺少知识库权限：" + Permissions.label(permission));
        }
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        return switch (tool) {
            case KbQueryTools.KB_SEARCH -> search(user, args);
            case KbTools.LIST_LIBRARIES ->
                    seriesService.list(
                            user,
                            stringArg(args, "scope"),
                            stringArg(args, "keyword"),
                            null,
                            stringArg(args, "status"),
                            intArg(args, "page", 1),
                            intArg(args, "size", 20));
            case KbTools.GET_LIBRARY -> seriesService.get(longArg(args, "series_id"), user);
            case KbTools.GET_CONFIG -> configService.active(longArg(args, "series_id"), user);
            case KbTools.LIST_DOCUMENTS ->
                    documentService.list(
                            longArg(args, "series_id"),
                            optionalLong(args, "directory_id"),
                            stringArg(args, "status"),
                            stringArg(args, "keyword"),
                            user);
            case KbTools.LIST_TASKS ->
                    taskService.list(
                            longArg(args, "series_id"),
                            stringArg(args, "status"),
                            stringArg(args, "type"),
                            intArg(args, "limit", 50),
                            user);
            case KbTools.LIST_MEMBERS -> memberService.list(longArg(args, "series_id"), user);
            case KbTools.LIST_DIRECTORIES ->
                    directoryService.tree(longArg(args, "series_id"), user);
            case KbTools.CREATE_LIBRARY -> createLibrary(user, args);
            case KbTools.SAVE_CONFIG -> saveConfig(user, args);
            case KbTools.GRANT_MEMBER -> grantMember(user, args);
            case KbTools.REMOVE_MEMBER -> removeMember(user, args);
            case KbTools.MOVE_DIRECTORY -> moveDirectory(user, args);
            case KbTools.REBUILD_INDEX -> rebuildIndex(user, args);
            default -> throw new PayrollException("未知知识库工具：" + tool);
        };
    }

    /**
     * 多库检索：逐库校验 READER，单库独立召回后按分数合并、按 chunkId 去重，再截断到 topK。
     * 无权库只回数字 ID，不返回名称与正文；全部无权时直接拒绝。
     */
    private Map<String, Object> search(AuthUser user, Map<String, Object> args) {
        long started = System.currentTimeMillis();
        String query = stringArg(args, "query");
        if (query == null) {
            throw new PayrollException("请输入检索问题");
        }
        if (query.length() > 200) {
            throw new PayrollException("检索问题不能超过 200 个字符");
        }
        List<Long> seriesIds = searchSeriesIds(args);
        int topK =
                Math.max(
                        1,
                        Math.min(
                                KbQueryTools.MAX_TOP_K,
                                intArg(args, "top_k", KbQueryTools.DEFAULT_TOP_K)));
        int perSeriesLimit = (int) Math.ceil((double) topK / seriesIds.size()) + 2;

        List<Long> denied = new ArrayList<>();
        List<LibraryResult> libraries = new ArrayList<>();
        boolean readyDocument = false;
        for (Long seriesId : seriesIds) {
            SeriesView series;
            try {
                series = seriesService.get(seriesId, user);
            } catch (PayrollForbiddenException | PayrollException e) {
                // 不存在与无权同样处理，避免借检索探测库是否存在。
                denied.add(seriesId);
                continue;
            }
            RetrievalResult result =
                    retrievalService.search(
                            seriesId,
                            new RetrievalTestRequest(query, perSeriesLimit, null, false),
                            user);
            if (!"NONE".equals(engineOf(result))) {
                readyDocument = true;
            }
            libraries.add(new LibraryResult(seriesId, series.name(), result));
        }
        if (libraries.isEmpty() && !denied.isEmpty()) {
            throw new PayrollForbiddenException("无权检索所选知识库");
        }

        List<RankedHit> merged = new ArrayList<>();
        for (LibraryResult library : libraries) {
            for (RetrievalHit hit : library.result().hits()) {
                merged.add(new RankedHit(library.seriesId(), hit));
            }
        }
        merged.sort(Comparator.comparingDouble((RankedHit hit) -> hit.hit().score()).reversed());
        List<RankedHit> selected = new ArrayList<>();
        Set<Long> chunkIds = new LinkedHashSet<>();
        for (RankedHit hit : merged) {
            if (chunkIds.add(hit.hit().chunkId())) {
                selected.add(hit);
            }
            if (selected.size() >= topK) {
                break;
            }
        }

        boolean truncated = false;
        int budget = MAX_TOTAL_CONTENT;
        Map<Long, List<Map<String, Object>>> hitsBySeries = new LinkedHashMap<>();
        for (LibraryResult library : libraries) {
            hitsBySeries.put(library.seriesId(), new ArrayList<>());
        }
        int index = 0;
        for (RankedHit ranked : selected) {
            RetrievalHit hit = ranked.hit();
            String content = hit.content();
            int contentLimit = Math.min(MAX_HIT_CONTENT, Math.max(0, budget));
            if (content != null && content.length() > contentLimit) {
                content = content.substring(0, contentLimit);
                truncated = true;
            }
            budget -= content == null ? 0 : content.length();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chunk_id", hit.chunkId());
            payload.put("document_id", hit.documentId());
            payload.put("document_name", hit.documentName());
            payload.put("page_no", hit.pageNo());
            payload.put("title_path", hit.titlePath());
            payload.put("score", hit.score());
            payload.put("point_id", "K" + (++index));
            payload.put("routes", hit.routes());
            payload.put("route_ranks", hit.routeRanks());
            payload.put("merged_chunks", hit.mergedChunks());
            payload.put("content", content);
            hitsBySeries.get(ranked.seriesId()).add(payload);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (LibraryResult library : libraries) {
            List<Map<String, Object>> hits = hitsBySeries.get(library.seriesId());
            if (hits.isEmpty()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("series_id", library.seriesId());
            entry.put("series_name", library.seriesName());
            entry.put("hits", hits);
            results.add(entry);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("results", results);
        data.put("denied_series", denied);
        data.put("truncated", truncated);
        if (results.isEmpty()) {
            data.put("reason", readyDocument ? "BELOW_THRESHOLD" : "NO_READY_DOCUMENT");
        }
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("engine", engines(libraries));
        diagnostics.put("took_ms", System.currentTimeMillis() - started);
        data.put("diagnostics", diagnostics);
        return data;
    }

    private static List<Long> searchSeriesIds(Map<String, Object> args) {
        List<Long> requested = longList(args, "series_ids");
        if (requested == null || requested.isEmpty()) {
            throw new PayrollException("请指定要检索的知识库");
        }
        LinkedHashSet<Long> distinct = new LinkedHashSet<>();
        for (Long seriesId : requested) {
            if (seriesId == null || seriesId <= 0) {
                throw new PayrollException("知识库 ID 不合法");
            }
            distinct.add(seriesId);
        }
        if (distinct.size() > MAX_SEARCH_SERIES) {
            throw new PayrollException("一次最多检索 " + MAX_SEARCH_SERIES + " 个知识库");
        }
        return new ArrayList<>(distinct);
    }

    private static String engineOf(RetrievalResult result) {
        Object engine = result.diagnostics() == null ? null : result.diagnostics().get("engine");
        return engine == null ? "NONE" : String.valueOf(engine).toUpperCase(Locale.ROOT);
    }

    private static String engines(List<LibraryResult> libraries) {
        LinkedHashSet<String> engines = new LinkedHashSet<>();
        for (LibraryResult library : libraries) {
            String engine = engineOf(library.result());
            if (!"NONE".equals(engine)) {
                engines.add(engine);
            }
        }
        return engines.isEmpty() ? "NONE" : String.join(",", engines);
    }

    private record LibraryResult(long seriesId, String seriesName, RetrievalResult result) {
    }

    private record RankedHit(long seriesId, RetrievalHit hit) {
    }

    private SeriesView createLibrary(AuthUser user, Map<String, Object> args) {        ConfigPayload config = new ConfigPayload();
        config.parse = KbConfigModels.defaultParse();
        config.chunk = KbConfigModels.defaultChunk();
        config.retrieval = KbConfigModels.defaultRetrieval();
        Integer topK = optionalInt(args, "retrieval_top_k");
        if (topK != null) {
            config.retrieval.recallTopK = topK;
        }
        Integer chunkMaxTokens = optionalInt(args, "chunk_max_tokens");
        if (chunkMaxTokens != null) {
            config.chunk.common.maxTokens = chunkMaxTokens;
        }
        CreateSeriesRequest request =
                new CreateSeriesRequest(
                        stringArg(args, "name"),
                        departmentId(args),
                        stringArg(args, "description"),
                        enumArg(IndexStrategy.class, args, "index_strategy", IndexStrategy.HYBRID),
                        optionalBoolean(args, "draft"),
                        config);
        return seriesService.create(request, user);
    }

    /** 对话里用户通常只说部门名称；按名称精确匹配，缺失或不唯一时给出可修正的错误。 */
    private Long departmentId(Map<String, Object> args) {
        Long departmentId = optionalLong(args, "department_id");
        if (departmentId != null) {
            return departmentId;
        }
        String name = stringArg(args, "department_name");
        if (name == null) {
            return null;
        }
        List<Department> matches =
                adminRepository.departments(false).stream()
                        .filter(department -> name.equals(department.name()))
                        .toList();
        if (matches.isEmpty()) {
            throw new PayrollException("未找到部门：" + name);
        }
        if (matches.size() > 1) {
            throw new PayrollException("部门名称不唯一，请改用 department_id：" + name);
        }
        return matches.get(0).id();
    }

    private Map<String, Object> saveConfig(AuthUser user, Map<String, Object> args) {
        long seriesId = longArg(args, "series_id");
        ConfigPayload payload = configService.payloadAt(seriesId, null);
        Integer topK = optionalInt(args, "retrieval_top_k");
        if (topK != null) {
            payload.retrieval.recallTopK = topK;
        }
        Integer rerankTopN = optionalInt(args, "rerank_top_n");
        if (rerankTopN != null) {
            payload.retrieval.rerankTopN = rerankTopN;
        }
        Double threshold = optionalDouble(args, "similarity_threshold");
        if (threshold != null) {
            payload.retrieval.similarityThreshold = threshold;
        }
        Integer chunkMaxTokens = optionalInt(args, "chunk_max_tokens");
        if (chunkMaxTokens != null) {
            payload.chunk.common.maxTokens = chunkMaxTokens;
        }
        Integer chunkOverlap = optionalInt(args, "chunk_overlap_tokens");
        if (chunkOverlap != null) {
            payload.chunk.common.overlapTokens = chunkOverlap;
        }
        SaveConfigRequest request =
                new SaveConfigRequest(
                        null,
                        payload.parse,
                        payload.chunk,
                        payload.retrieval,
                        stringArg(args, "change_note"),
                        null);
        ConfigView config = configService.save(seriesId, request, user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("config", config);
        String rebuildMode = stringArg(args, "rebuild_mode");
        if (rebuildMode != null
                && !rebuildMode.isBlank()
                && !"NONE".equalsIgnoreCase(rebuildMode.trim())) {
            try {
                result.put("rebuildTasks", taskService.rebuild(seriesId, rebuildMode, null, user));
            } catch (RuntimeException e) {
                log.warn(
                        "[知识库] 对话保存配置后重建失败：series={}，mode={}，原因={}",
                        seriesId,
                        rebuildMode,
                        e.getMessage());
                result.put("rebuildError", e.getMessage());
            }
        }
        return result;
    }

    private List<KbDtos.MemberView> grantMember(AuthUser user, Map<String, Object> args) {
        GrantMembersRequest request =
                new GrantMembersRequest(
                        enumArg(SubjectType.class, args, "subject_type", SubjectType.USER),
                        longList(args, "subject_ids"),
                        enumArg(KbRole.class, args, "role", KbRole.READER));
        return memberService.grant(longArg(args, "series_id"), request, user);
    }

    private Map<String, Object> removeMember(AuthUser user, Map<String, Object> args) {
        long seriesId = longArg(args, "series_id");
        long memberId = longArg(args, "member_id");
        memberService.remove(seriesId, memberId, user);
        return Map.of("status", "OK", "series_id", seriesId, "removed_member_id", memberId);
    }

    private KbDtos.DirectoryView moveDirectory(AuthUser user, Map<String, Object> args) {
        MoveDirectoryRequest request =
                new MoveDirectoryRequest(
                        optionalLong(args, "parent_id"), optionalInt(args, "sort_order"));
        return directoryService.move(
                longArg(args, "series_id"), longArg(args, "directory_id"), request, user);
    }

    private List<TaskView> rebuildIndex(AuthUser user, Map<String, Object> args) {
        return taskService.rebuild(
                longArg(args, "series_id"),
                stringArg(args, "mode"),
                longList(args, "document_ids"),
                user);
    }

    /** 库列表、新建库与多库检索只校验全局权限；读工具至少 READER，移动目录 EDITOR，其余写操作 ADMIN。 */
    private static KbRole minimumRole(String tool) {
        if (KbQueryTools.KB_SEARCH.equals(tool)
                || KbTools.LIST_LIBRARIES.equals(tool)
                || KbTools.CREATE_LIBRARY.equals(tool)) {
            return null;
        }
        if (READ_TOOLS.contains(tool)) {
            return KbRole.READER;
        }
        if (KbTools.MOVE_DIRECTORY.equals(tool)) {
            return KbRole.EDITOR;
        }
        return KbRole.ADMIN;
    }

    private static void requireTool(String tool) {
        if (tool == null || !TOOLS.contains(tool)) {
            throw new PayrollException("未知知识库工具：" + tool);
        }
    }

    private static String stringArg(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static long longArg(Map<String, Object> args, String name) {
        Long value = optionalLong(args, name);
        if (value == null || value <= 0) {
            throw new PayrollException("缺少或非法的参数：" + name);
        }
        return value;
    }

    private static Long optionalLong(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new PayrollException("参数 " + name + " 必须是整数：" + value);
        }
    }

    private static int intArg(Map<String, Object> args, String name, int fallback) {
        Integer value = optionalInt(args, name);
        return value == null ? fallback : value;
    }

    private static Integer optionalInt(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new PayrollException("参数 " + name + " 必须是整数：" + value);
        }
    }

    private static Double optionalDouble(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new PayrollException("参数 " + name + " 必须是数字：" + value);
        }
    }

    private static Boolean optionalBoolean(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value).trim());
    }

    private static List<Long> longList(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) {
            return null;
        }
        List<Long> result = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(parseLong(name, item));
                }
            }
            return result;
        }
        for (String part : String.valueOf(value).split("[,;\\s]+")) {
            if (!part.isBlank()) {
                result.add(parseLong(name, part));
            }
        }
        return result;
    }

    private static long parseLong(String name, Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new PayrollException("参数 " + name + " 包含非法 ID：" + value);
        }
    }

    private static <E extends Enum<E>> E enumArg(
            Class<E> type, Map<String, Object> args, String name, E fallback) {
        String value = stringArg(args, name);
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PayrollException("参数 " + name + " 取值不合法：" + value);
        }
    }
}
