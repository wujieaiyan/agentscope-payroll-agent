package io.agentscope.examples.payroll.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.examples.payroll.service.KbSkillGateway;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 协同智能体的知识库工具：只读查询直接执行，写操作先做权限预检，再交给 {@link ActionGuard}
 * 生成待确认提案。
 *
 * <p>知识库服务在 Web 模块，工具通过 {@link KbSkillGateway} 调用签名内部接口；权限拒绝由
 * Web 侧返回 FORBIDDEN，工具原样转述，禁止改用通用知识作答。
 */
public final class KbTools {

    private static final Logger log = LoggerFactory.getLogger(KbTools.class);

    public static final String LIST_LIBRARIES = "kb_list_libraries";
    public static final String GET_LIBRARY = "kb_get_library";
    public static final String GET_CONFIG = "kb_get_config";
    public static final String LIST_DOCUMENTS = "kb_list_documents";
    public static final String LIST_TASKS = "kb_list_tasks";
    public static final String LIST_MEMBERS = "kb_list_members";
    public static final String LIST_DIRECTORIES = "kb_list_directories";
    public static final String CREATE_LIBRARY = "kb_create_library";
    public static final String SAVE_CONFIG = "kb_save_config";
    public static final String GRANT_MEMBER = "kb_grant_member";
    public static final String REMOVE_MEMBER = "kb_remove_member";
    public static final String MOVE_DIRECTORY = "kb_move_directory";
    public static final String REBUILD_INDEX = "kb_rebuild_index";

    private final KbSkillGateway gateway;
    private final ToolTraceRecorder recorder;

    public KbTools(KbSkillGateway gateway) {
        this(gateway, null);
    }

    public KbTools(KbSkillGateway gateway, ToolTraceRecorder recorder) {
        this.gateway = gateway;
        this.recorder = recorder;
    }

    public boolean enabled() {
        return gateway != null && gateway.enabled();
    }

    @Tool(
            name = LIST_LIBRARIES,
            description =
                    "查询当前用户可见的知识库列表，可按关键字、范围（all/mine）与状态过滤。"
                            + "回答“有哪些知识库”“研发部有没有知识库”前先调用。",
            readOnly = true)
    public String listLibraries(
            @ToolParam(name = "scope", description = "可选，all 或 mine；默认 all", required = false)
                    String scope,
            @ToolParam(name = "keyword", description = "可选，按名称或描述模糊匹配", required = false)
                    String keyword,
            @ToolParam(
                            name = "status",
                            description = "可选，DRAFT / ACTIVE / DISABLED",
                            required = false)
                    String status,
            @ToolParam(name = "page", description = "可选，页码，默认 1", required = false)
                    Integer page,
            @ToolParam(name = "size", description = "可选，每页条数，默认 20，最大 100", required = false)
                    Integer size,
            RuntimeContext context) {
        return read(
                LIST_LIBRARIES,
                args(
                        "scope", scope,
                        "keyword", keyword,
                        "status", status,
                        "page", page,
                        "size", size),
                context);
    }

    @Tool(
            name = GET_LIBRARY,
            description = "按知识库 ID 查询名称、部门、索引策略、状态与文档/分块计数。",
            readOnly = true)
    public String getLibrary(
            @ToolParam(name = "series_id", description = "知识库 ID")
                    long seriesId,
            RuntimeContext context) {
        return read(GET_LIBRARY, args("series_id", seriesId), context);
    }

    @Tool(
            name = GET_CONFIG,
            description =
                    "查询知识库当前生效的解析、分块与检索配置版本，包括 recallTopK、rerankTopN、"
                            + "chunk maxTokens 等字段。回答配置值前必须调用。",
            readOnly = true)
    public String getConfig(
            @ToolParam(name = "series_id", description = "知识库 ID")
                    long seriesId,
            RuntimeContext context) {
        return read(GET_CONFIG, args("series_id", seriesId), context);
    }

    @Tool(
            name = LIST_DOCUMENTS,
            description = "查询知识库文档列表，可按目录、状态与关键字过滤；用于定位待重建文档。",
            readOnly = true)
    public String listDocuments(
            @ToolParam(name = "series_id", description = "知识库 ID")
                    long seriesId,
            @ToolParam(name = "directory_id", description = "可选，目录 ID", required = false)
                    Long directoryId,
            @ToolParam(name = "status", description = "可选，文档状态", required = false)
                    String status,
            @ToolParam(name = "keyword", description = "可选，文档名称关键字", required = false)
                    String keyword,
            RuntimeContext context) {
        return read(
                LIST_DOCUMENTS,
                args(
                        "series_id", seriesId,
                        "directory_id", directoryId,
                        "status", status,
                        "keyword", keyword),
                context);
    }

    @Tool(
            name = LIST_TASKS,
            description = "查询知识库入库/重建任务列表，可按状态、类型过滤；回答任务进度时使用。",
            readOnly = true)
    public String listTasks(
            @ToolParam(name = "series_id", description = "知识库 ID")
                    long seriesId,
            @ToolParam(name = "status", description = "可选，任务状态", required = false)
                    String status,
            @ToolParam(name = "type", description = "可选，任务类型", required = false)
                    String type,
            @ToolParam(name = "limit", description = "可选，返回条数，默认 50", required = false)
                    Integer limit,
            RuntimeContext context) {
        return read(
                LIST_TASKS,
                args("series_id", seriesId, "status", status, "type", type, "limit", limit),
                context);
    }

    @Tool(
            name = LIST_MEMBERS,
            description = "查询知识库成员列表，返回成员记录 ID、用户/部门与角色；移除成员前先调用确认 member_id。",
            readOnly = true)
    public String listMembers(
            @ToolParam(name = "series_id", description = "知识库 ID")
                    long seriesId,
            RuntimeContext context) {
        return read(LIST_MEMBERS, args("series_id", seriesId), context);
    }

    @Tool(
            name = LIST_DIRECTORIES,
            description = "查询知识库目录树，返回目录 ID、父目录与排序号；移动目录前先调用确认目录 ID。",
            readOnly = true)
    public String listDirectories(
            @ToolParam(name = "series_id", description = "知识库 ID")
                    long seriesId,
            RuntimeContext context) {
        return read(LIST_DIRECTORIES, args("series_id", seriesId), context);
    }

    @Tool(
            name = CREATE_LIBRARY,
            description =
                    "新建知识库。必须确认名称与所属部门（可用 department_name 传部门名称）；"
                            + "可同时设置检索 topK 与分块大小。"
                            + "该操作会生成待确认提案，用户确认后才会落库。",
            readOnly = false)
    public String createLibrary(
            @ToolParam(name = "name", description = "知识库名称，2 到 50 个字符")
                    String name,
            @ToolParam(name = "description", description = "可选，知识库描述", required = false)
                    String description,
            @ToolParam(name = "department_name", description = "可选，所属部门名称", required = false)
                    String departmentName,
            @ToolParam(name = "department_id", description = "可选，所属部门 ID；不传用当前用户部门", required = false)
                    Long departmentId,
            @ToolParam(
                            name = "index_strategy",
                            description = "可选，VECTOR 或 HYBRID，默认 HYBRID",
                            required = false)
                    String indexStrategy,
            @ToolParam(name = "draft", description = "可选，是否保存为草稿", required = false)
                    Boolean draft,
            @ToolParam(name = "retrieval_top_k", description = "可选，检索召回 topK", required = false)
                    Integer retrievalTopK,
            @ToolParam(name = "chunk_max_tokens", description = "可选，分块最大 token 数", required = false)
                    Integer chunkMaxTokens,
            RuntimeContext context) {
        return write(
                CREATE_LIBRARY,
                args(
                        "name", name,
                        "description", description,
                        "department_name", departmentName,
                        "department_id", departmentId,
                        "index_strategy", indexStrategy,
                        "draft", draft,
                        "retrieval_top_k", retrievalTopK,
                        "chunk_max_tokens", chunkMaxTokens),
                context,
                false);
    }

    @Tool(
            name = SAVE_CONFIG,
            description =
                    "保存知识库配置的新版本。只传需要调整的字段，其余沿用当前生效配置；"
                            + "例如把检索 topK 调到 8 只传 retrieval_top_k=8。"
                            + "该操作会生成待确认提案，用户确认后才会生效。",
            readOnly = false)
    public String saveConfig(
            @ToolParam(name = "series_id", description = "知识库 ID")
                    long seriesId,
            @ToolParam(name = "retrieval_top_k", description = "可选，召回 topK", required = false)
                    Integer retrievalTopK,
            @ToolParam(name = "rerank_top_n", description = "可选，重排后保留条数", required = false)
                    Integer rerankTopN,
            @ToolParam(name = "similarity_threshold", description = "可选，相似度阈值 0 到 1", required = false)
                    Double similarityThreshold,
            @ToolParam(name = "chunk_max_tokens", description = "可选，分块最大 token 数", required = false)
                    Integer chunkMaxTokens,
            @ToolParam(name = "chunk_overlap_tokens", description = "可选，分块重叠 token 数", required = false)
                    Integer chunkOverlapTokens,
            @ToolParam(name = "change_note", description = "可选，版本说明", required = false)
                    String changeNote,
            @ToolParam(
                            name = "rebuild_mode",
                            description = "可选，NONE / FULL / FAILED / DOCUMENT；需要按新配置重建时传入",
                            required = false)
                    String rebuildMode,
            RuntimeContext context) {
        return write(
                SAVE_CONFIG,
                args(
                        "series_id", seriesId,
                        "retrieval_top_k", retrievalTopK,
                        "rerank_top_n", rerankTopN,
                        "similarity_threshold", similarityThreshold,
                        "chunk_max_tokens", chunkMaxTokens,
                        "chunk_overlap_tokens", chunkOverlapTokens,
                        "change_note", changeNote,
                        "rebuild_mode", rebuildMode),
                context,
                false);
    }

    @Tool(
            name = GRANT_MEMBER,
            description =
                    "给用户或部门授予知识库角色（READER/EDITOR/ADMIN/OWNER）。"
                            + "该操作会生成待确认提案，用户确认后才会生效。",
            readOnly = false)
    public String grantMember(
            @ToolParam(name = "series_id", description = "知识库 ID")
                    long seriesId,
            @ToolParam(name = "subject_type", description = "USER 或 DEPT")
                    String subjectType,
            @ToolParam(name = "subject_ids", description = "用户 ID 或部门 ID 列表")
                    List<Long> subjectIds,
            @ToolParam(name = "role", description = "READER / EDITOR / ADMIN / OWNER")
                    String role,
            RuntimeContext context) {
        return write(
                GRANT_MEMBER,
                args(
                        "series_id", seriesId,
                        "subject_type", subjectType,
                        "subject_ids", subjectIds,
                        "role", role),
                context,
                false);
    }

    @Tool(
            name = REMOVE_MEMBER,
            description =
                    "移除知识库成员。成员记录 ID 通过 kb_list_members 查询确认。"
                            + "该操作不可撤销，会生成待确认提案。",
            readOnly = false)
    public String removeMember(
            @ToolParam(name = "series_id", description = "知识库 ID")
                    long seriesId,
            @ToolParam(name = "member_id", description = "成员记录 ID")
                    long memberId,
            RuntimeContext context) {
        return write(
                REMOVE_MEMBER, args("series_id", seriesId, "member_id", memberId), context, true);
    }

    @Tool(
            name = MOVE_DIRECTORY,
            description =
                    "移动知识库目录到新的父目录；parent_id 为空表示移到根目录。"
                            + "该操作会生成待确认提案。",
            readOnly = false)
    public String moveDirectory(
            @ToolParam(name = "series_id", description = "知识库 ID")
                    long seriesId,
            @ToolParam(name = "directory_id", description = "要移动的目录 ID")
                    long directoryId,
            @ToolParam(name = "parent_id", description = "可选，目标父目录 ID；空表示根目录", required = false)
                    Long parentId,
            @ToolParam(name = "sort_order", description = "可选，同级排序号", required = false)
                    Integer sortOrder,
            RuntimeContext context) {
        return write(
                MOVE_DIRECTORY,
                args(
                        "series_id", seriesId,
                        "directory_id", directoryId,
                        "parent_id", parentId,
                        "sort_order", sortOrder),
                context,
                false);
    }

    @Tool(
            name = REBUILD_INDEX,
            description =
                    "按当前配置触发知识库重建索引；mode 为 FULL（全部）、FAILED（失败文档）或"
                            + "DOCUMENT（指定 document_ids）。该操作会生成待确认提案。",
            readOnly = false)
    public String rebuildIndex(
            @ToolParam(name = "series_id", description = "知识库 ID")
                    long seriesId,
            @ToolParam(name = "mode", description = "FULL 或 INCREMENTAL")
                    String mode,
            @ToolParam(name = "document_ids", description = "可选，仅重建指定文档", required = false)
                    List<Long> documentIds,
            RuntimeContext context) {
        return write(
                REBUILD_INDEX,
                args("series_id", seriesId, "mode", mode, "document_ids", documentIds),
                context,
                true);
    }

    private String read(
            String tool, Map<String, Object> arguments, RuntimeContext context) {
        long startedAt = System.nanoTime();
        String result;
        long userId = userId(context);
        if (userId < 0) {
            result = error("缺少用户身份，无法查询知识库");
        } else if (!enabled()) {
            result = error("知识库对话工具未启用，请检查 Web 地址与内部令牌配置");
        } else {
            try {
                result = gateway.call(tool, arguments, userId);
            } catch (RuntimeException e) {
                log.warn("知识库查询失败：tool={}，原因={}", tool, rootMessage(e));
                result = error("知识库查询失败：" + rootMessage(e));
            }
        }
        record(tool, arguments, result, context, startedAt);
        return result;
    }

    private String write(
            String tool,
            Map<String, Object> arguments,
            RuntimeContext context,
            boolean destructive) {
        long startedAt = System.nanoTime();
        String result;
        long userId = userId(context);
        if (userId < 0) {
            result = error("缺少用户身份，无法执行知识库操作");
        } else if (!enabled()) {
            result = error("知识库对话工具未启用，请检查 Web 地址与内部令牌配置");
        } else {
            KbSkillGateway.Check check;
            try {
                check = gateway.check(tool, arguments, userId);
            } catch (RuntimeException e) {
                log.warn("知识库权限预检失败：tool={}，原因={}", tool, rootMessage(e));
                result = error("知识库权限校验失败：" + rootMessage(e));
                record(tool, arguments, result, context, startedAt);
                return result;
            }
            if (!check.allowed()) {
                result = forbidden(check.message());
            } else {
                ActionGuard guard = context == null ? null : context.get(ActionGuard.class);
                String pending =
                        guard == null
                                ? null
                                : guard.intercept(tool, arguments, false, false, destructive);
                if (pending != null) {
                    result = pending;
                } else {
                    try {
                        result = gateway.call(tool, arguments, userId);
                    } catch (RuntimeException e) {
                        log.warn("知识库写操作失败：tool={}，原因={}", tool, rootMessage(e));
                        result = error("知识库操作失败：" + rootMessage(e));
                    }
                }
            }
        }
        record(tool, arguments, result, context, startedAt);
        return result;
    }

    private void record(
            String tool,
            Map<String, Object> arguments,
            String result,
            RuntimeContext context,
            long startedAt) {
        ToolTraceRecorder active = recorderFor(context);
        if (active == null) {
            return;
        }
        try {
            active.record(
                    tool,
                    JsonSupport.toJson(arguments),
                    result,
                    (System.nanoTime() - startedAt) / 1_000_000);
        } catch (RuntimeException e) {
            log.warn("记录知识库工具追溯失败：tool={}，原因={}", tool, e.getMessage());
        }
    }

    /** 与薪酬工具一致：共享智能体下按本次调用取用记录器，避免并发会话串线。 */
    private ToolTraceRecorder recorderFor(RuntimeContext context) {
        if (context != null) {
            ToolTraceRecorder scoped = context.get(ToolTraceRecorder.class);
            if (scoped != null) {
                return scoped;
            }
        }
        return recorder;
    }

    private static long userId(RuntimeContext context) {
        String value = context == null ? null : context.getUserId();
        if (value == null || value.isBlank()) {
            return -1L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                arguments.put((String) pairs[i], pairs[i + 1]);
            }
        }
        return arguments;
    }

    private static String forbidden(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "FORBIDDEN");
        response.put(
                "message",
                message == null || message.isBlank()
                        ? "当前账号无权执行该知识库操作"
                        : message);
        return JsonSupport.toJson(response);
    }

    private static String error(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ERROR");
        response.put("message", message);
        return JsonSupport.toJson(response);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }
}
