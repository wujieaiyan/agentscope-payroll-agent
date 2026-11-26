package io.agentscope.examples.payroll.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollNotFoundException;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SkillStore} 的库实现（规格 §5/§8/§9）：正文进 {@code skill_version}，文件字节进 {@link
 * SkillBlobStore}，清单与派生列进 {@code skill_definition}，管理台读写与运行期读共用同一套 SQL。
 *
 * <p>事务与并发风格照抄 {@code JdbcAgentDefinitionRepository}：写前 {@code SELECT ... FOR UPDATE}
 * 锁行、行级 {@code revision} 递增、SQLState 23 开头判唯一键冲突。H2（MODE=MySQL）与 MySQL 共用语句，
 * 只有 blob 元数据的幂等写入按方言分叉。
 *
 * <p>与 Nacos 实现的关键差别是「保存一个技能 = 一个事务」：正文版本、资源清单与三个 {@code resource_*}
 * 派生列同提交，因此不存在「正文已存、文件包重绑失败」的半成品状态。
 *
 * <p>命名说明：规格 §19 把这一步写作 {@code JdbcSkillRepository}，但本类实现的是管理面端口 {@link
 * SkillStore}，而 {@code *SkillRepository} 后缀已被 {@code AgentSkillRepository} 的实现占用
 * （{@code NacosSkillRepository} 与 P0-4 的 {@code DbSkillRepository}），故按端口命名为 Store。
 */
public final class JdbcSkillStore implements SkillStore, SkillRuntimeReader {

    public static final String SOURCE_DB = "db";

    /** 一条 {@code IN (...)} 语句的参数上限，不是技能数上限（§7）。 */
    private static final int MAX_IN_PARAMS = 500;

    private static final Logger log = LoggerFactory.getLogger(JdbcSkillStore.class);

    private static final String MEDIA_TYPE_TEXT = "text/plain";
    private static final String EMPTY_META_JSON = "{}";

    /** {@code tags} 与 {@code skill_version} 的列宽；超限直接报错，静默截断会让指纹对不上内容。 */
    private static final int MAX_TAGS_CHARS = 512;

    private static final int MAX_VERSION_CHARS = 32;

    /** {@code skill_version.change_note} 的列宽，与规格 §9.2 的「≤200 字符」同源。 */
    private static final int MAX_CHANGE_NOTE_CHARS = 200;

    private static final TypeReference<LinkedHashMap<String, Object>> META_JSON =
            new TypeReference<>() {};

    /**
     * frontmatter 里已拆成列的键不再重复写进 {@code meta_json}：同一份数据只能有一处真相，
     * 否则改了列、元信息视图仍回显旧值。
     */
    private static final Set<String> COLUMNAR_META_KEYS =
            Set.of(
                    "name",
                    "description",
                    "tags",
                    "version",
                    "execution",
                    SkillExecutions.META_ENABLED);

    private static final String ROW_COLUMNS =
            "skill_id, description, tags, skill_version, builtin, execution_enabled,"
                    + " content_hash, content_length, resource_count, resource_hash,"
                    + " current_version_id, revision, updated_at";

    /** {@code sort_order} 决定目录顺序，而目录顺序直接进入系统提示词，必须显式排。 */
    private static final String SELECT_CATALOG =
            "SELECT " + ROW_COLUMNS + " FROM skill_definition ORDER BY sort_order ASC, skill_id ASC";

    private static final String SELECT_ROW =
            "SELECT " + ROW_COLUMNS + " FROM skill_definition WHERE skill_id = ?";

    private static final String SELECT_ROW_FOR_UPDATE = SELECT_ROW + " FOR UPDATE";

    /**
     * 列表页一屏要的四列：版本号、写入者、数据需求、文件数。当前版本号与 {@code meta_json} 走
     * {@code LEFT JOIN}（内置技能可能还没有版本行），setup 脚本是否存在再由
     * {@link #SELECT_SETUP_PATHS} 批量补齐——逐条调 {@code detail} 会退化成 N+1。
     */
    private static final String SELECT_SUMMARIES =
            """
            SELECT d.skill_id, d.description, d.builtin, d.content_length, d.resource_count,
                   d.updated_at, d.updated_by, d.revision, d.current_version_id,
                   v.version_no, v.meta_json
            FROM skill_definition d
            LEFT JOIN skill_version v ON v.version_id = d.current_version_id
            ORDER BY d.sort_order ASC, d.skill_id ASC
            """;

    private static final String SELECT_PROVENANCE =
            "SELECT created_by, created_at, updated_by FROM skill_definition WHERE skill_id = ?";

    /** 只取可能成为 setup 的路径：根目录 {@code setup.sh} 与 {@code scripts/**}，与解析规则同一口径。 */
    private static final String SELECT_SETUP_PATHS =
            """
            SELECT r.skill_id, r.path FROM skill_resource r
            JOIN skill_definition d ON d.current_version_id = r.version_id
            WHERE r.skill_id IN %s AND (r.path = ? OR r.path LIKE 'scripts/%')
            """;

    /** 历史版本只读视图：清单与正文同版本，不用当前版本冒充。 */
    private static final String SELECT_VERSION_AT =
            """
            SELECT version_id, version_no, body_md, content_length, change_note, created_by, created_at
            FROM skill_version WHERE skill_id = ? AND version_no = ?
            """;

    /**
     * 版本行只追加（D3a）；{@code meta_json} 是当前版本可改的元信息，不属正文。
     * {@code change_note} 取管理台的「变更说明」（§9.2，≤200 字符），不填即空串；
     * {@code created_by} 取写入者，未知即 NULL（启动期迁移与命令行没有操作者）——落库留白比编一个
     * {@code "admin"} 好，时间线读回时统一归一为空串。
     */
    private static final String INSERT_VERSION =
            """
            INSERT INTO skill_version
                (skill_id, version_no, body_md, meta_json, content_hash, content_length,
                 change_note, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String UPDATE_VERSION_META =
            "UPDATE skill_version SET meta_json = ? WHERE version_id = ?";

    /** 正文没变、只补一句变更说明时改当前版本行：追加一条同内容版本对读者没有信息量。 */
    private static final String UPDATE_VERSION_NOTE =
            "UPDATE skill_version SET change_note = ? WHERE version_id = ?";

    /** 正文与版本序号一次取回：管理台详情要把 {@code version_no} 写进审计（§13-6），不值得为它多一跳。 */
    private static final String SELECT_VERSION_BODY =
            "SELECT version_no, body_md FROM skill_version WHERE version_id = ?";

    private static final String SELECT_META_JSON =
            "SELECT meta_json FROM skill_version WHERE version_id = ?";

    /**
     * 版本时间线（§13）：不取 {@code body_md}——打开时间线不该等于下载全部历史，
     * 正文按需走 {@link #SELECT_VERSION_BY_NO}。
     */
    private static final String SELECT_VERSION_TIMELINE =
            """
            SELECT version_id, version_no, change_note, content_length, created_by, created_at
            FROM skill_version WHERE skill_id = ? ORDER BY version_no DESC
            """;

    /** 回滚目标行：{@code body_md} 要复制进新版本，{@code content_hash} 直接复用避免二次哈希。 */
    private static final String SELECT_VERSION_BY_NO =
            """
            SELECT version_id, body_md, content_hash
            FROM skill_version WHERE skill_id = ? AND version_no = ?
            """;

    private static final String SELECT_MAX_VERSION_NO =
            "SELECT COALESCE(MAX(version_no), 0) FROM skill_version WHERE skill_id = ?";

    private static final String SELECT_NEXT_SORT_ORDER =
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM skill_definition";

    /** {@code updated_at} 只为算跨实例失效滞后，不进哈希载荷——载荷必须与 §9.1 定义的口径逐字节一致。 */
    private static final String SELECT_AGGREGATE_REVISION =
            "SELECT skill_id, revision, updated_at FROM skill_definition ORDER BY skill_id";

    private static final String SELECT_RESOURCES =
            """
            SELECT path, sha256, size_bytes, media_type, is_binary
            FROM skill_resource WHERE version_id = ? ORDER BY path
            """;

    /**
     * 批量装配的两条 {@code IN} 查询。键是 {@code current_version_id} 而不是 {@code skill_id}：
     * 一个技能有多条历史版本行（D3a），只有指针指向的那条是运行期要读的，按 id 查会把旧正文带出来。
     */
    private static final String SELECT_VERSIONS_BY_IDS =
            "SELECT version_id, body_md, meta_json FROM skill_version WHERE version_id IN %s";

    private static final String SELECT_RESOURCES_BY_IDS =
            """
            SELECT version_id, path, sha256, size_bytes, media_type, is_binary
            FROM skill_resource WHERE version_id IN %s ORDER BY version_id, path
            """;

    private static final String INSERT_RESOURCE =
            """
            INSERT INTO skill_resource
                (version_id, skill_id, path, sha256, size_bytes, media_type)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    /** 正文变了但文件没变：把旧版本的清单行整批复制到新版本，字节仍按 sha 复用。 */
    private static final String COPY_RESOURCES =
            """
            INSERT INTO skill_resource
                (version_id, skill_id, path, sha256, size_bytes, media_type, is_binary)
            SELECT ?, skill_id, path, sha256, size_bytes, media_type, is_binary
            FROM skill_resource WHERE version_id = ?
            """;

    private static final String DELETE_RESOURCES_OF_VERSION =
            "DELETE FROM skill_resource WHERE version_id = ?";

    private static final String DELETE_RESOURCE_ROW =
            "DELETE FROM skill_resource WHERE version_id = ? AND path = ?";

    private static final String DELETE_RESOURCES_OF_SKILL =
            "DELETE FROM skill_resource WHERE skill_id = ?";

    private static final String DELETE_VERSIONS_OF_SKILL =
            "DELETE FROM skill_version WHERE skill_id = ?";

    private static final String DELETE_DEFINITION = "DELETE FROM skill_definition WHERE skill_id = ?";

    private static final String COUNT_RESOURCE_REFERENCES =
            "SELECT COUNT(*) FROM skill_resource WHERE sha256 = ?";

    /** 列序与 {@link #insertParams} 一一对应；{@code builtin} 与 {@code sort_order} 只在插入时给。 */
    private static final String INSERT_DEFINITION =
            """
            INSERT INTO skill_definition
                (skill_id, name, description, tags, skill_version, execution_enabled,
                 content_hash, content_length, resource_count, resource_bytes, resource_hash,
                 current_version_id, revision, created_by, updated_by, builtin, sort_order)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * {@code name} 恒等于 {@code skill_id}（现网无独立展示名）；保存不改 builtin 与排序。
     * {@code updated_by} 每次都按本次写入者覆盖——没有操作者的写入（启动迁移、命令行）落 NULL，
     * 宁可显示「未知」也不留上一个操作者的名字冒充这次是他改的。{@code created_by} 只在插入时写。
     */
    private static final String UPDATE_DEFINITION =
            """
            UPDATE skill_definition
            SET description = ?, tags = ?, skill_version = ?, execution_enabled = ?,
                content_hash = ?, content_length = ?, resource_count = ?, resource_bytes = ?,
                resource_hash = ?, current_version_id = ?, revision = ?, updated_by = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE skill_id = ?
            """;

    private static final String UPSERT_BLOB_H2 =
            """
            MERGE INTO skill_blob
                (sha256, storage_key, size_bytes, media_type, storage_location)
            KEY(sha256) VALUES (?, ?, ?, ?, ?)
            """;

    private static final String UPSERT_BLOB_MYSQL =
            """
            INSERT INTO skill_blob
                (sha256, storage_key, size_bytes, media_type, storage_location)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE storage_key = VALUES(storage_key),
                                      media_type = VALUES(media_type)
            """;

    /** 管理台 DTO 需要的行 + 写路径需要的定位信息；正文与资源字节不在此列。 */
    public record Row(
            String id,
            String description,
            List<String> tags,
            String version,
            boolean builtin,
            boolean executionEnabled,
            String contentHash,
            int contentLength,
            int resourceCount,
            String resourceHash,
            long versionId,
            long revision,
            long updatedAt) {}

    /** {@code skill_resource} 的一行：清单在库，字节在介质。 */
    public record ResourceRef(
            String path, String sha256, long sizeBytes, String mediaType, boolean binary) {}

    /** 版本行的两个必读字段：正文进 DTO，{@code version_no} 进审计。 */
    private record VersionBody(int versionNo, String body) {}

    /** 列表摘要行：定义行 + 当前版本号/元信息，setup 判定结果由批量查询补齐。 */
    private record SummaryRow(
            String id,
            String description,
            boolean builtin,
            int contentLength,
            int resourceCount,
            long updatedAt,
            String updatedBy,
            long revision,
            long versionId,
            int versionNo,
            String metaJson) {}

    /** 定义行的溯源列；与 {@link Row} 分开查，避免目录快照多读三列。 */
    private record Provenance(String createdBy, long createdAt, String updatedBy) {}

    /** 历史版本读到内存后再去查清单，避免在 ResultSet 打开时再发一条语句。 */
    private record VersionAt(
            long versionId,
            int versionNo,
            String body,
            int contentLength,
            String changeNote,
            String createdBy,
            long createdAt) {}

    /** 回滚目标：指针、正文与指纹，三者一起才够追加一条新版本行。 */
    private record VersionTarget(long versionId, String body, String contentHash) {}

    /** 一次写入要落进定义行与版本行的元信息（已清洗，列宽在绑定前校验）。 */
    private record Meta(
            String description,
            List<String> tags,
            String version,
            boolean executionEnabled,
            String metaJson) {}

    /**
     * 一次写入算完资源清单后定义行应有的样子。三个 {@code resource_*} 由清单现算，不作为入参——
     * 规格 §8 步骤 4 要求它们与步骤 3 写入的行同事务重算，否则指纹会漂。
     */
    private record Pending(
            Meta meta,
            boolean builtin,
            String contentHash,
            int contentLength,
            long versionId,
            List<ResourceRef> resources,
            long revision,
            String operator) {}

    /** 事务体：{@code newBlobs} 由 {@link #inTransaction} 创建，回滚后据此清理字节。 */
    @FunctionalInterface
    private interface Tx<T> {
        T apply(Connection connection, List<String> newBlobs) throws SQLException;
    }

    private final PayrollDatabase database;
    private final SkillBlobStore blobStore;
    private final SkillFileSettings fileSettings;

    private volatile long lastSuccessfulListAt;

    public JdbcSkillStore(PayrollDatabase database, SkillBlobStore blobStore) {
        this(database, blobStore, SkillFileSettings.fromEnvironment());
    }

    public JdbcSkillStore(
            PayrollDatabase database, SkillBlobStore blobStore, SkillFileSettings fileSettings) {
        this.database = database;
        this.blobStore = blobStore;
        this.fileSettings = fileSettings == null ? SkillFileSettings.defaults() : fileSettings;
    }

    /**
     * db 源的可写性等价于数据库可用；库不可用时真正的读写会抛 {@link PayrollUnavailableException}，
     * 这里不再额外探测一次（每次管理请求多一次往返，结论还不比异常更准）。
     */
    @Override
    public boolean writable() {
        return true;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public long lastSuccessfulListAt() {
        return lastSuccessfulListAt;
    }

    @Override
    public String location() {
        return (database.isMysql() ? "mysql:" : "h2:") + "skill_definition";
    }

    /** 字节介质位置，供健康检查与排错展示。 */
    public String blobLocation() {
        return blobStore.location();
    }

    /**
     * 目录 + 尚未落库的内置技能。与 {@link NacosSkillRegistry#list()} 同形：三个角色技能即使还没入库
     * 也必须出现在列表里，否则切到 db 源的第一天技能页会少掉内置项。
     */
    @Override
    public List<SkillSummary> list() {
        Map<String, Row> remaining = new LinkedHashMap<>();
        catalog().forEach(row -> remaining.put(row.id(), row));
        List<SkillSummary> summaries = new ArrayList<>(remaining.size() + RoleSkills.ids().size());
        for (String id : RoleSkills.ids()) {
            Row row = remaining.remove(id);
            summaries.add(row == null ? builtinSummary(id) : summary(row));
        }
        remaining.values().forEach(row -> summaries.add(summary(row)));
        return List.copyOf(summaries);
    }

    /**
     * 列表页专用：比 {@link #list()} 多版本号、写入者与 setup 判定，只多一次批量资源查询。
     * 内置技能（库里还没有行）按「来源无法判定」返回：版本号 {@code null}、数据需求 {@code null}。
     */
    @Override
    public List<SkillSummary> summaries() {
        Map<String, SummaryRow> byId = new LinkedHashMap<>();
        Map<String, Set<String>> loaded;
        try (Connection connection = database.connection()) {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_SUMMARIES);
                    ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    SummaryRow row = readSummaryRow(rs);
                    byId.put(row.id(), row);
                }
            }
            loaded = byId.isEmpty()
                    ? Map.of()
                    : selectSetupPaths(connection, List.copyOf(byId.keySet()));
        } catch (SQLException e) {
            throw unavailable("读取技能摘要失败", e);
        }
        Map<String, Set<String>> setupPaths = loaded;
        List<SkillSummary> result = new ArrayList<>(byId.size() + RoleSkills.ids().size());
        Set<String> seen = new LinkedHashSet<>();
        for (String id : RoleSkills.ids()) {
            SummaryRow row = byId.get(id);
            result.add(row == null ? builtinSummary(id) : summary(row, setupPaths.get(id)));
            seen.add(id);
        }
        byId.values().stream()
                .filter(row -> !seen.contains(row.id()))
                .forEach(row -> result.add(summary(row, setupPaths.get(row.id()))));
        return List.copyOf(result);
    }

    /** 当前版本的文件路径 → sha256；技能不存在或文件包停用时为空。 */
    @Override
    public Map<String, String> resourceHashes(String id) {
        String skillId = SkillIds.normalize(id);
        try (Connection connection = database.connection()) {
            Row row = readRow(connection, skillId, false);
            if (row == null || !fileSettings.enabled()) {
                return Map.of();
            }
            return Collections.unmodifiableMap(hashByPath(selectResources(connection, row.versionId())));
        } catch (SQLException e) {
            throw unavailable("读取技能 " + skillId + " 的文件指纹失败", e);
        }
    }

    @Override
    public SkillVersionContent versionContent(String id, int versionNo) {
        String skillId = SkillIds.normalize(id);
        try (Connection connection = database.connection()) {
            Row row = readRow(connection, skillId, false);
            if (row == null) {
                throw new PayrollNotFoundException("技能不存在：" + skillId);
            }
            VersionAt version = selectVersionAt(connection, skillId, versionNo);
            List<ResourceRef> refs =
                    fileSettings.enabled()
                            ? selectResources(connection, version.versionId())
                            : List.of();
            return new SkillVersionContent(
                    version.versionNo(),
                    version.body(),
                    version.changeNote(),
                    version.createdBy(),
                    version.createdAt(),
                    version.contentLength(),
                    version.versionId() == row.versionId(),
                    summaries(refs));
        } catch (SQLException e) {
            throw unavailable("读取技能 " + skillId + " 的版本 v" + versionNo + " 失败", e);
        }
    }

    private static SummaryRow readSummaryRow(ResultSet rs) throws SQLException {
        java.sql.Timestamp updatedAt = rs.getTimestamp("updated_at");
        int versionNo = rs.getInt("version_no");
        if (rs.wasNull()) {
            versionNo = 0;
        }
        return new SummaryRow(
                rs.getString("skill_id"),
                text(rs.getString("description")),
                rs.getBoolean("builtin"),
                rs.getInt("content_length"),
                rs.getInt("resource_count"),
                updatedAt == null ? 0L : updatedAt.getTime(),
                text(rs.getString("updated_by")),
                rs.getLong("revision"),
                rs.getLong("current_version_id"),
                versionNo,
                text(rs.getString("meta_json")));
    }

    private static Map<String, Set<String>> selectSetupPaths(
            Connection connection, List<String> skillIds) throws SQLException {
        Map<String, Set<String>> bySkill = new LinkedHashMap<>();
        for (int from = 0; from < skillIds.size(); from += MAX_IN_PARAMS) {
            List<String> chunk =
                    skillIds.subList(from, Math.min(from + MAX_IN_PARAMS, skillIds.size()));
            String sql = SELECT_SETUP_PATHS.formatted(placeholders(chunk.size()));
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                for (String id : chunk) {
                    statement.setString(index++, id);
                }
                statement.setString(index, SkillFilePackage.SETUP_SH);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        bySkill.computeIfAbsent(rs.getString("skill_id"), ignored -> new LinkedHashSet<>())
                                .add(rs.getString("path"));
                    }
                }
            }
        }
        return bySkill;
    }

    private static VersionAt selectVersionAt(Connection connection, String skillId, int versionNo)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_VERSION_AT)) {
            bind(statement, List.of(skillId, versionNo));
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new PayrollNotFoundException("版本不存在：v" + versionNo);
                }
                java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
                return new VersionAt(
                        rs.getLong("version_id"),
                        rs.getInt("version_no"),
                        rs.getString("body_md"),
                        rs.getInt("content_length"),
                        text(rs.getString("change_note")),
                        text(rs.getString("created_by")),
                        createdAt == null ? 0L : createdAt.getTime());
            }
        }
    }

    private static Provenance selectProvenance(Connection connection, String skillId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_PROVENANCE)) {
            bind(statement, List.of(skillId));
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return new Provenance("", 0L, "");
                }
                java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
                return new Provenance(
                        text(rs.getString("created_by")),
                        createdAt == null ? 0L : createdAt.getTime(),
                        text(rs.getString("updated_by")));
            }
        }
    }

    /** setup 判定：声明了可用脚本才算需要数据；{@code meta_json} 坏掉时按未知（{@code null}）处理。 */
    private static Boolean needsData(Map<String, Object> meta, Set<String> setupPaths) {
        if (meta == null) {
            return null;
        }
        Object raw = meta.get("setup");
        return SkillInitRequirements.setupPath(
                        raw == null ? null : String.valueOf(raw),
                        setupPaths == null ? Set.of() : setupPaths)
                != null;
    }

    /** 元信息解析失败返回 {@code null}：调用方据此把派生字段一律报「未知」，而不是猜一个默认值。 */
    private static Map<String, Object> parseMeta(String metaJson) {
        try {
            return metaJson == null || metaJson.isBlank()
                    ? Map.of()
                    : JsonSupport.fromJson(metaJson, META_JSON);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Boolean needsData(Map<String, Object> customMeta, List<ResourceRef> refs) {
        Object raw = customMeta.get("setup");
        Set<String> paths = new LinkedHashSet<>();
        refs.forEach(ref -> paths.add(ref.path()));
        return SkillInitRequirements.setupPath(
                        raw == null ? null : String.valueOf(raw), paths)
                != null;
    }

    /** 目录单查询：不带正文、不读 blob（§5.2 的红线：装配单个技能的全部资源不得拖慢整张目录）。 */
    @Override
    public List<Row> catalog() {
        long started = System.nanoTime();
        List<Row> rows = null;
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(SELECT_CATALOG);
                ResultSet rs = statement.executeQuery()) {
            List<Row> loaded = new ArrayList<>();
            while (rs.next()) {
                loaded.add(readRow(rs));
            }
            rows = List.copyOf(loaded);
            lastSuccessfulListAt = System.currentTimeMillis();
            return rows;
        } catch (SQLException e) {
            throw unavailable("读取技能目录失败", e);
        } finally {
            SkillMetrics.catalogBuilt(SOURCE_DB, System.nanoTime() - started);
            SkillMetrics.catalogQueried(SOURCE_DB, rows == null ? -1L : rows.size());
        }
    }

    /** 单行目录，语义与 {@link #catalog()} 一致（含文件包停用时的指纹归零）。 */
    @Override
    public Optional<Row> find(String id) {
        String skillId = SkillIds.normalize(id);
        try (Connection connection = database.connection()) {
            return Optional.ofNullable(readRow(connection, skillId, false));
        } catch (SQLException e) {
            throw unavailable("读取技能 " + skillId + " 失败", e);
        }
    }

    /** 当前版本正文。缺失即报错：空正文会让智能体带着半句话上岗。 */
    public String bodyOf(Row row) {
        try (Connection connection = database.connection()) {
            return selectVersionBody(connection, row).body();
        } catch (SQLException e) {
            throw unavailable("读取技能 " + row.id() + " 正文失败", e);
        }
    }

    /** 当前版本的文件清单；文件包开关关闭时为空，与 Nacos 源的 kill-switch 语义一致。 */
    public List<ResourceRef> resourcesOf(Row row) {
        if (!fileSettings.enabled()) {
            return List.of();
        }
        try (Connection connection = database.connection()) {
            return selectResources(connection, row.versionId());
        } catch (SQLException e) {
            throw unavailable("读取技能 " + row.id() + " 文件清单失败", e);
        }
    }

    /** 按清单取字节并解码为文本；字节缺失直接报错，不返回脏数据也不回落其他存储。 */
    public Map<String, String> contents(String skillId, List<ResourceRef> refs) {
        if (refs.isEmpty()) {
            return Map.of();
        }
        Set<String> shas = new LinkedHashSet<>();
        refs.forEach(ref -> shas.add(ref.sha256()));
        return decode(skillId, refs, blobStore.getAll(shas));
    }

    /**
     * 运行期批量装配（规格 §7）：整批正文与元信息两次 {@code IN} 查询、整批字节一次
     * {@link SkillBlobStore#getAll}，代价与技能个数无关，只与查询条数有关。
     *
     * <p>目录阶段读不到正文与字节是红线（§5.2），因此这里只被 {@link DbSkillRepository} 的正文
     * 装配路径调用，{@link #catalog()} 与 {@link #list()} 都不走本方法。
     */
    @Override
    public Map<String, Runtime> runtimeData(Collection<Row> rows) {
        Map<Long, Row> byVersion = new LinkedHashMap<>();
        if (rows != null) {
            for (Row row : rows) {
                if (row != null) {
                    byVersion.putIfAbsent(row.versionId(), row);
                }
            }
        }
        if (byVersion.isEmpty()) {
            return Map.of();
        }
        List<Long> versionIds = List.copyOf(byVersion.keySet());
        Map<Long, VersionText> versions = new LinkedHashMap<>();
        Map<Long, List<ResourceRef>> resources = new LinkedHashMap<>();
        try (Connection connection = database.connection()) {
            for (int from = 0; from < versionIds.size(); from += MAX_IN_PARAMS) {
                List<Long> chunk =
                        versionIds.subList(from, Math.min(versionIds.size(), from + MAX_IN_PARAMS));
                selectVersions(connection, chunk, versions);
                if (fileSettings.enabled()) {
                    selectResourceGroups(connection, chunk, resources);
                }
            }
        } catch (SQLException e) {
            throw unavailable("批量读取技能正文失败", e);
        }
        List<ResourceRef> refs =
                resources.values().stream().flatMap(List::stream).toList();
        Map<String, byte[]> blobs =
                refs.isEmpty() ? Map.of() : blobStore.getAll(distinctShas(refs));
        Map<String, Runtime> results = new LinkedHashMap<>();
        for (Row row : byVersion.values()) {
            VersionText text = versions.get(row.versionId());
            if (text == null) {
                // 定义行还在、版本行已被并发删除：按「该技能不存在」返回，让其他来源兜住这一轮。
                log.warn("[Skill] 技能 {} 的版本行 {} 读不到，本轮跳过", row.id(), row.versionId());
                continue;
            }
            results.put(
                    row.id(),
                    new Runtime(
                            text.body(),
                            metadataOf(row, text.customMeta()),
                            decode(
                                    row.id(),
                                    resources.getOrDefault(row.versionId(), List.of()),
                                    blobs)));
        }
        return Collections.unmodifiableMap(results);
    }

    private void selectVersions(Connection connection, List<Long> versionIds, Map<Long, VersionText> into)
            throws SQLException {
        try (PreparedStatement statement =
                        connection.prepareStatement(
                                SELECT_VERSIONS_BY_IDS.formatted(placeholders(versionIds.size())));
                ResultSet rs = bindIds(statement, versionIds).executeQuery()) {
            while (rs.next()) {
                long versionId = rs.getLong("version_id");
                into.put(
                        versionId,
                        new VersionText(
                                rs.getString("body_md"),
                                parseCustomMeta(versionId, rs.getString("meta_json"))));
            }
        }
    }

    private void selectResourceGroups(
            Connection connection, List<Long> versionIds, Map<Long, List<ResourceRef>> into)
            throws SQLException {
        try (PreparedStatement statement =
                        connection.prepareStatement(
                                SELECT_RESOURCES_BY_IDS.formatted(placeholders(versionIds.size())));
                ResultSet rs = bindIds(statement, versionIds).executeQuery()) {
            while (rs.next()) {
                long versionId = rs.getLong("version_id");
                into.computeIfAbsent(versionId, ignored -> new ArrayList<>())
                        .add(
                                new ResourceRef(
                                        rs.getString("path"),
                                        text(rs.getString("sha256")),
                                        rs.getLong("size_bytes"),
                                        text(rs.getString("media_type")),
                                        rs.getBoolean("is_binary")));
            }
        }
    }

    /** 已取到字节时按清单顺序解码；缺字节报错的文案与 {@link #contents} 同一条，两条读路径不分叉。 */
    private static Map<String, String> decode(
            String skillId, List<ResourceRef> refs, Map<String, byte[]> blobs) {
        Map<String, String> contents = new LinkedHashMap<>();
        for (ResourceRef ref : refs) {
            byte[] bytes = blobs.get(ref.sha256());
            if (bytes == null) {
                // 带上技能 id：整批装配时一个 blob 读不回会抛在最前面那条技能上，只有路径的话运维
                // 无从判断是单条脏数据还是 PAYROLL_SKILL_BLOB_ROOT 指错了目录。
                throw new PayrollException("技能资源缺失：" + skillId + "/" + ref.path());
            }
            contents.put(ref.path(), new String(bytes, StandardCharsets.UTF_8));
        }
        return Collections.unmodifiableMap(contents);
    }

    private static Set<String> distinctShas(List<ResourceRef> refs) {
        Set<String> shas = new LinkedHashSet<>();
        refs.forEach(ref -> shas.add(ref.sha256()));
        return shas;
    }

    private static String placeholders(int count) {
        return "(" + String.join(",", Collections.nCopies(count, "?")) + ")";
    }

    private static PreparedStatement bindIds(PreparedStatement statement, List<Long> ids)
            throws SQLException {
        for (int i = 0; i < ids.size(); i++) {
            statement.setLong(i + 1, ids.get(i));
        }
        return statement;
    }

    /** 正文 + 已解析的 {@code meta_json}；文件字节不在此列。 */
    private record VersionText(String body, Map<String, Object> customMeta) {}

    /**
     * 跨实例失效轮询用的目录指纹（§9.1）：全表 {@code skill_id:revision} 排序后取哈希，
     * 与 {@code JdbcAgentDefinitionRepository} 的聚合口径同构。
     */
    @Override
    public CatalogFingerprint fingerprint() {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(SELECT_AGGREGATE_REVISION);
                ResultSet rs = statement.executeQuery()) {
            StringBuilder raw = new StringBuilder();
            long lastWrite = 0L;
            while (rs.next()) {
                raw.append(rs.getString(1)).append(':').append(rs.getLong(2)).append('\n');
                java.sql.Timestamp updatedAt = rs.getTimestamp(3);
                lastWrite = Math.max(lastWrite, updatedAt == null ? 0L : updatedAt.getTime());
            }
            return new CatalogFingerprint(SkillManifests.contentHash(raw.toString()), lastWrite);
        } catch (SQLException e) {
            throw unavailable("读取技能目录指纹失败", e);
        }
    }

    @Override
    public SkillDetail detail(String id) {
        String skillId = SkillIds.normalize(id);
        try (Connection connection = database.connection()) {
            Row row = readRow(connection, skillId, false);
            if (row == null) {
                return builtinDetail(skillId);
            }
            List<ResourceRef> refs =
                    fileSettings.enabled()
                            ? selectResources(connection, row.versionId())
                            : List.of();
            VersionBody version = selectVersionBody(connection, row);
            Map<String, Object> custom = selectCustomMeta(connection, row.versionId());
            Provenance provenance = selectProvenance(connection, skillId);
            return new SkillDetail(
                    skillId,
                    row.description(),
                    version.body(),
                    SOURCE_DB,
                    row.builtin(),
                    row.builtin(),
                    provenance.createdAt(),
                    row.updatedAt(),
                    summaries(refs),
                    row.executionEnabled(),
                    metadataOf(row, custom),
                    String.valueOf(row.revision()),
                    version.versionNo(),
                    provenance.createdBy(),
                    provenance.updatedBy(),
                    needsData(custom, refs));
        } catch (SQLException e) {
            throw unavailable("读取技能 " + skillId + " 失败", e);
        }
    }

    @Override
    public List<SkillFilePackage.Summary> files(String id) {
        String skillId = SkillIds.normalize(id);
        try (Connection connection = database.connection()) {
            Row row = readRow(connection, skillId, false);
            if (row == null || !fileSettings.enabled()) {
                return List.of();
            }
            return summaries(selectResources(connection, row.versionId()));
        } catch (SQLException e) {
            throw unavailable("读取技能 " + skillId + " 文件列表失败", e);
        }
    }

    @Override
    public String fileContent(String id, String path) {
        String skillId = SkillIds.normalize(id);
        String normalized = SkillFilePackage.normalizePath(path);
        Row row = find(skillId).orElseThrow(() -> new PayrollException("技能不存在或未落库：" + skillId));
        ResourceRef ref =
                resourcesOf(row).stream()
                        .filter(candidate -> candidate.path().equals(normalized))
                        .findFirst()
                        .orElseThrow(() -> new PayrollException("文件不存在：" + normalized));
        return contents(skillId, List.of(ref)).get(normalized);
    }

    /**
     * 版本时间线（§13）：技能没有落库行时返回空列表而不是报错——内置技能在库里本来就没有版本行。
     * {@code current} 按定义行的 {@code current_version_id} 判定，不按 {@code version_no} 最大：
     * 回滚会追加更大的序号，但指针任何时候都只有一条行能被它指到。
     */
    @Override
    public List<SkillVersion> versions(String id) {
        String skillId = SkillIds.normalize(id);
        try (Connection connection = database.connection()) {
            Row row = readRow(connection, skillId, false);
            return row == null ? List.of() : selectTimeline(connection, skillId, row.versionId());
        } catch (SQLException e) {
            throw unavailable("读取技能 " + skillId + " 的版本历史失败", e);
        }
    }

    private static List<SkillVersion> selectTimeline(
            Connection connection, String skillId, long currentVersionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_VERSION_TIMELINE);
                ResultSet rs = bind(statement, List.of(skillId)).executeQuery()) {
            List<SkillVersion> versions = new ArrayList<>();
            while (rs.next()) {
                java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
                versions.add(
                        new SkillVersion(
                                rs.getInt("version_no"),
                                text(rs.getString("change_note")),
                                rs.getInt("content_length"),
                                text(rs.getString("created_by")),
                                createdAt == null ? 0L : createdAt.getTime(),
                                rs.getLong("version_id") == currentVersionId));
            }
            return List.copyOf(versions);
        }
    }

    @Override
    public SkillSummary create(
            String id, String description, String content, boolean executionEnabled) {
        return create(id, description, content, executionEnabled, "");
    }

    @Override
    public SkillSummary create(
            String id,
            String description,
            String content,
            boolean executionEnabled,
            String operator) {
        String skillId = SkillIds.normalize(id);
        if (find(skillId).isPresent()) {
            throw new PayrollException("技能已存在：" + skillId);
        }
        return write(
                skillId,
                description,
                content,
                executionEnabled,
                null,
                List.of(),
                false,
                null,
                true,
                "",
                operator);
    }

    @Override
    public SkillSummary update(
            String id, String description, String content, boolean executionEnabled) {
        return update(id, description, content, executionEnabled, null);
    }

    /**
     * 带乐观并发的保存：{@code expectedRevision} 为空串/null 表示不做并发检查（行锁照旧），
     * 与库内当前 revision 不一致抛 {@link PayrollConflictException}。端口把它做成默认方法，
     * 是为了让没有行级 revision 的 Nacos 源不必实现一组空重载。
     */
    @Override
    public SkillSummary update(
            String id,
            String description,
            String content,
            boolean executionEnabled,
            String expectedRevision) {
        return update(id, description, content, executionEnabled, expectedRevision, "", "");
    }

    /** 同 {@link #update(String, String, String, boolean, String)}，并把「变更说明」落到版本行。 */
    @Override
    public SkillSummary update(
            String id,
            String description,
            String content,
            boolean executionEnabled,
            String expectedRevision,
            String changeNote) {
        return update(
                id, description, content, executionEnabled, expectedRevision, changeNote, "");
    }

    /**
     * 管理台保存的完整形状：三个尾部参数各归各的列，串位就是「把变更说明写进了操作者」，
     * 所以只在 {@code write} 末尾按 operator 收口，版本行与定义行都由它归一（见 {@link #operatorOrNull}）。
     */
    @Override
    public SkillSummary update(
            String id,
            String description,
            String content,
            boolean executionEnabled,
            String expectedRevision,
            String changeNote,
            String operator) {
        return write(
                SkillIds.normalize(id),
                description,
                content,
                executionEnabled,
                null,
                List.of(),
                false,
                expectedRevision,
                false,
                changeNote,
                operator);
    }

    /**
     * 回滚（§9.2）：追加一条正文与清单都复用目标版本的新版本行，再把 {@code current_version_id} 移过去，
     * 历史行一字不改。序号因此单调递增——「回到 v2」在库里是新增一条 v5，而不是把指针拨回去重写历史。
     *
     * <p>目标版本的字节已丢时拒绝：指针一移，运行期就会读到「技能资源缺失」，整批技能装配一起失败。
     * 宁可让管理员先重新上传文件，也不把一个读不出来的包推上线。
     *
     * <p>{@code meta_json} 沿用当前版本而不是目标版本：元信息按定义是「当前版本可改的、不属正文」的部分，
     * 回滚正文不该顺带把标签之外的自定义元信息也退回旧值。
     */
    @Override
    public SkillSummary rollback(String id, int versionNo, String expectedRevision) {
        return rollback(id, versionNo, expectedRevision, "");
    }

    /**
     * 同 {@link #rollback(String, int, String)}，并把按下回滚的人写进新追加的版本行。
     * 这里不能用原作者：{@code created_by} 记的是「这条 v5 是谁造的」，造它的人是回滚的操作者。
     */
    @Override
    public SkillSummary rollback(
            String id, int versionNo, String expectedRevision, String operator) {
        String skillId = SkillIds.normalize(id);
        Pending pending =
                inTransaction(
                        skillId,
                        (connection, ignored) -> {
                            Row locked =
                                    lockRow(
                                            connection,
                                            skillId,
                                            expectedRevision,
                                            "技能不存在或未落库：" + skillId);
                            VersionBody current = selectVersionBody(connection, locked);
                            if (current.versionNo() == versionNo) {
                                throw new PayrollException("v" + versionNo + " 已是当前版本，无需回滚");
                            }
                            VersionTarget target = selectVersionTarget(connection, skillId, versionNo);
                            // 先按目标清单验字节，再动库：验不过就整笔事务不提交，不会留下空版本行。
                            requireBlobsPresent(
                                    skillId, versionNo, selectResources(connection, target.versionId()));
                            String writer = operatorOrNull(operator);
                            long versionId =
                                    insertVersion(
                                            connection,
                                            skillId,
                                            nextVersionNo(connection, skillId),
                                            target.body(),
                                            target.contentHash(),
                                            selectMetaJson(connection, locked.versionId()),
                                            "回滚自 v" + versionNo,
                                            writer);
                            List<ResourceRef> resources =
                                    copyResources(connection, target.versionId(), versionId);
                            return rewriteDefinition(
                                    connection,
                                    locked,
                                    metaOf(
                                            locked,
                                            null,
                                            locked.description(),
                                            locked.executionEnabled()),
                                    target.contentHash(),
                                    target.body().length(),
                                    versionId,
                                    resources,
                                    writer);
                        });
        SkillRepositories.invalidateShared();
        log.info("[Skill] 已把技能 {} 回滚到 v{}", skillId, versionNo);
        return summary(rowOf(skillId, pending));
    }

    @Override
    public List<SkillFilePackage.Summary> replaceFiles(
            String id, List<SkillFilePackage.SkillFile> files) {
        return replaceFiles(id, files, null);
    }

    /** 全量替换文件包：不追加正文版本（§9.2 版本只跟正文走），当前版本的清单行原地重建并重算指纹。 */
    @Override
    public List<SkillFilePackage.Summary> replaceFiles(
            String id, List<SkillFilePackage.SkillFile> files, String expectedRevision) {
        return replaceFiles(id, files, expectedRevision, "");
    }

    /** 同上，外加操作者：文件包写入不动版本行，但要刷新定义行的 {@code updated_by}。 */
    @Override
    public List<SkillFilePackage.Summary> replaceFiles(
            String id,
            List<SkillFilePackage.SkillFile> files,
            String expectedRevision,
            String operator) {
        String skillId = SkillIds.normalize(id);
        requireFilesEnabled();
        List<SkillFilePackage.SkillFile> clean = SkillFilePackage.normalizeAll(files, fileSettings);
        Pending pending =
                inTransaction(
                        skillId,
                        (connection, newBlobs) -> {
                            Row locked =
                                    lockRow(connection, skillId, expectedRevision, "技能不存在：" + skillId);
                            return replaceResources(
                                    connection,
                                    locked,
                                    rebuildResources(
                                            connection, locked.versionId(), skillId, clean, newBlobs),
                                    operator);
                        });
        SkillRepositories.invalidateShared();
        log.info("[Skill] 已更新技能 {} 的文件包（{} 个文件）", skillId, pending.resources().size());
        return summaries(pending.resources());
    }

    @Override
    public boolean deleteFile(String id, String path) {
        return deleteFile(id, path, null);
    }

    @Override
    public boolean deleteFile(String id, String path, String expectedRevision) {
        return deleteFile(id, path, expectedRevision, "");
    }

    @Override
    public boolean deleteFile(
            String id, String path, String expectedRevision, String operator) {
        String skillId = SkillIds.normalize(id);
        String normalized = SkillFilePackage.normalizePath(path);
        requireFilesEnabled();
        inTransaction(
                skillId,
                (connection, ignored) -> {
                    Row locked =
                            lockRow(
                                    connection,
                                    skillId,
                                    expectedRevision,
                                    "技能不存在或未落库：" + skillId);
                    if (deleteResourceRow(connection, locked.versionId(), normalized) == 0) {
                        throw new PayrollException("文件不存在：" + normalized);
                    }
                    replaceResources(
                            connection,
                            locked,
                            selectResources(connection, locked.versionId()),
                            operator);
                    return Boolean.TRUE;
                });
        SkillRepositories.invalidateShared();
        return true;
    }

    /**
     * 单文件写：只 upsert 目标路径那一行清单，其余文件不读不写，因此耗时不随文件数增长。
     * 先删同路径旧行再插入，等价于覆盖；新文件的删除命中 0 行，不影响结果。
     */
    @Override
    public List<SkillFilePackage.Summary> writeFile(
            String id, String path, String content, String expectedRevision, String operator) {
        String skillId = SkillIds.normalize(id);
        requireFilesEnabled();
        SkillFilePackage.SkillFile file =
                SkillFilePackage.normalizeAll(
                                List.of(new SkillFilePackage.SkillFile(path, content)), fileSettings)
                        .get(0);
        Pending pending =
                inTransaction(
                        skillId,
                        (connection, newBlobs) -> {
                            Row locked =
                                    lockRow(
                                            connection,
                                            skillId,
                                            expectedRevision,
                                            "技能不存在或未落库：" + skillId);
                            deleteResourceRow(connection, locked.versionId(), file.path());
                            writeResources(
                                    connection,
                                    locked.versionId(),
                                    skillId,
                                    List.of(file),
                                    newBlobs);
                            return replaceResources(
                                    connection,
                                    locked,
                                    selectResources(connection, locked.versionId()),
                                    operator);
                        });
        SkillRepositories.invalidateShared();
        log.info("[Skill] 已写入技能 {} 的单文件 {}", skillId, file.path());
        return summaries(pending.resources());
    }

    @Override
    public SkillSummary importProject(
            SkillProjectParser.ParsedProject project, boolean overwrite) {
        return importProject(project, overwrite, null, "");
    }

    @Override
    public SkillSummary importProject(
            SkillProjectParser.ParsedProject project,
            boolean overwrite,
            String expectedRevision,
            String operator) {
        return importProjectInternal(project, overwrite, expectedRevision, operator, null);
    }

    /**
     * 迁移导入专用（§11.2 规则 6）：Nacos 的执行开关存在 {@code <id>.md} 的 frontmatter 里，
     * 而端口沿用「已有行的开关」这一语义对新行只能取 {@code false}，直接导入会让开了沙箱的技能
     * 在切库后静默失去 {@code skill_run}。迁移没有人类操作者，所以 {@code operator} 传空串、
     * {@code created_by} 落 NULL——对账时能把「灌进来的」和「页面上改的」分开。
     */
    public SkillSummary importProjectForMigration(
            SkillProjectParser.ParsedProject project, boolean overwrite, Boolean executionEnabled) {
        return importProjectInternal(project, overwrite, null, "", executionEnabled);
    }

    /**
     * @param executionEnabledOverride {@code null} 表示按端口语义沿用已有行的执行开关
     * @param expectedRevision 行级令牌；{@code null}/空串跳过检查，不一致抛
     *     {@link PayrollConflictException} 且带 {@code currentRevision}——与上面「覆盖需要显式确认」
     *     那类 409 的区别就在有没有这个字段（§13-6）
     */
    private SkillSummary importProjectInternal(
            SkillProjectParser.ParsedProject project,
            boolean overwrite,
            String expectedRevision,
            String operator,
            Boolean executionEnabledOverride) {
        if (project == null) {
            throw new PayrollException("技能项目不能为空");
        }
        String skillId = SkillIds.normalize(project.id());
        Row existing = find(skillId).orElse(null);
        boolean builtin = RoleSkills.ids().contains(skillId);
        if ((builtin || existing != null) && !overwrite) {
            throw new PayrollConflictException("技能已存在：" + skillId + "；覆盖导入需要显式确认");
        }
        List<SkillFilePackage.SkillFile> files =
                SkillFilePackage.normalizeAll(project.files(), fileSettings);
        // 文件包停用时只写正文，并保留已有清单：与 Nacos 源同语义，开关回滚不需要清数据。
        boolean replaceResources = fileSettings.enabled();
        if (!replaceResources && !files.isEmpty()) {
            log.warn("[Skill] 技能文件包已停用，导入 {} 时只写正文", skillId);
        }
        boolean executionEnabled =
                executionEnabledOverride != null
                        ? executionEnabledOverride
                        : existing != null && existing.executionEnabled();
        return write(
                skillId,
                project.description(),
                project.body(),
                executionEnabled,
                project.metadata(),
                files,
                replaceResources,
                expectedRevision,
                true,
                "",
                operator);
    }

    @Override
    public boolean delete(String id) {
        return delete(id, null);
    }

    @Override
    public boolean delete(String id, String expectedRevision) {
        String skillId = SkillIds.normalize(id);
        inTransaction(
                skillId,
                (connection, ignored) -> {
                    lockRow(
                            connection,
                            skillId,
                            expectedRevision,
                            RoleSkills.ids().contains(skillId)
                                    ? "内置技能没有落库版本，不能删除"
                                    : "技能不存在：" + skillId);
                    // 只删清单与版本行：字节可能与其他技能共享，回收归 §14 的保留期巡检。
                    update(connection, DELETE_RESOURCES_OF_SKILL, List.of(skillId));
                    update(connection, DELETE_VERSIONS_OF_SKILL, List.of(skillId));
                    update(connection, DELETE_DEFINITION, List.of(skillId));
                    return Boolean.TRUE;
                });
        SkillRepositories.invalidateShared();
        log.info("[Skill] 已删除技能 {}", skillId);
        return true;
    }

    /**
     * 保存正文（可选同时替换文件包）：一次事务写版本行 + 清单 + 派生列。
     *
     * @param metadata 导入项目的 frontmatter；{@code null} 表示沿用当前行的元信息
     * @param files 仅在 {@code replaceResources} 为真时整包替换当前版本清单
     * @param createIfMissing {@code false} 时行已被并发删除会报「技能不存在」，而不是补插一条
     * @param changeNote 写进本次产生的版本行；正文没变时改挂当前版本行（见 {@link #updateSkill}）
     * @param operator 写入者，进 {@code skill_version.created_by} 与 {@code skill_definition} 的两个 {@code *_by}；
     *     空白即 {@code null}，见 {@link #operatorOrNull}
     */
    private SkillSummary write(
            String skillId,
            String rawDescription,
            String rawContent,
            boolean executionEnabled,
            Map<String, Object> metadata,
            List<SkillFilePackage.SkillFile> files,
            boolean replaceResources,
            String expectedRevision,
            boolean createIfMissing,
            String changeNote,
            String operator) {
        String cleanDescription = SkillContentRules.cleanDescription(rawDescription);
        String cleanContent = SkillContentRules.requireBody(rawContent);
        String contentHash = SkillManifests.contentHash(cleanContent);
        String description =
                cleanDescription.isBlank() && RoleSkills.ids().contains(skillId)
                        ? RoleSkills.description(skillId)
                        : cleanDescription;
        Pending pending =
                inTransaction(
                        skillId,
                        (connection, newBlobs) -> {
                            Row locked = readRow(connection, skillId, true);
                            if (locked == null && !createIfMissing) {
                                throw new PayrollException("技能不存在：" + skillId);
                            }
                            requireRevision(locked, expectedRevision);
                            Meta meta = metaOf(locked, metadata, description, executionEnabled);
                            List<SkillFilePackage.SkillFile> incoming =
                                    replaceResources ? files : List.of();
                            return locked == null
                                    ? insertSkill(
                                            connection,
                                            skillId,
                                            meta,
                                            cleanContent,
                                            contentHash,
                                            incoming,
                                            changeNote,
                                            operator,
                                            newBlobs)
                                    : updateSkill(
                                            connection,
                                            skillId,
                                            locked,
                                            meta,
                                            cleanContent,
                                            contentHash,
                                            incoming,
                                            replaceResources,
                                            changeNote,
                                            operator,
                                            newBlobs);
                        });
        SkillRepositories.invalidateShared();
        log.info(
                "[Skill] 已保存技能 {}（{} 字，{} 个文件）",
                skillId,
                cleanContent.length(),
                pending.resources().size());
        return summary(rowOf(skillId, pending));
    }

    /**
     * 元信息以导入项目为准；普通保存只改正文与执行开关，标签/版本号沿用当前行——否则一次正文保存会
     * 静默清空 {@code tags} 与 {@code skill_version}。
     *
     * @return {@code metaJson} 为 {@code null} 表示版本行的 {@code meta_json} 保持原样
     */
    private static Meta metaOf(
            Row locked, Map<String, Object> metadata, String description, boolean executionEnabled) {
        if (metadata != null) {
            return new Meta(
                    description,
                    SkillManifests.tags(metadata),
                    requireVersionLength(SkillManifests.version(metadata)),
                    executionEnabled,
                    JsonSupport.toJson(customMeta(metadata)));
        }
        return new Meta(
                description,
                locked == null ? List.of() : locked.tags(),
                locked == null ? "" : locked.version(),
                executionEnabled,
                null);
    }

    private Pending insertSkill(
            Connection connection,
            String skillId,
            Meta meta,
            String body,
            String contentHash,
            List<SkillFilePackage.SkillFile> files,
            String changeNote,
            String operator,
            List<String> newBlobs)
            throws SQLException {
        boolean builtin = RoleSkills.ids().contains(skillId);
        String writer = operatorOrNull(operator);
        long versionId =
                insertVersion(
                        connection,
                        skillId,
                        1,
                        body,
                        contentHash,
                        meta.metaJson() == null ? EMPTY_META_JSON : meta.metaJson(),
                        changeNote,
                        writer);
        List<ResourceRef> resources =
                writeResources(connection, versionId, skillId, files, newBlobs);
        Pending pending =
                new Pending(
                        meta,
                        builtin,
                        contentHash,
                        body.length(),
                        versionId,
                        resources,
                        1L,
                        writer);
        update(connection, INSERT_DEFINITION, insertParams(connection, skillId, pending));
        return pending;
    }

    private Pending updateSkill(
            Connection connection,
            String skillId,
            Row current,
            Meta meta,
            String body,
            String contentHash,
            List<SkillFilePackage.SkillFile> files,
            boolean replaceResources,
            String changeNote,
            String operator,
            List<String> newBlobs)
            throws SQLException {
        String writer = operatorOrNull(operator);
        boolean bodyChanged = !contentHash.equals(current.contentHash());
        String note = requireChangeNote(changeNote);
        long versionId;
        List<ResourceRef> resources;
        if (bodyChanged) {
            // version_no 必须在行锁之后分配，否则并发写会撞 uk_skill_version_no（§8 步骤 1）。
            versionId =
                    insertVersion(
                            connection,
                            skillId,
                            nextVersionNo(connection, skillId),
                            body,
                            contentHash,
                            meta.metaJson() == null
                                    ? selectMetaJson(connection, current.versionId())
                                    : meta.metaJson(),
                            note,
                            writer);
            resources =
                    replaceResources
                            ? writeResources(connection, versionId, skillId, files, newBlobs)
                            : copyResources(connection, current.versionId(), versionId);
        } else {
            versionId = current.versionId();
            if (meta.metaJson() != null) {
                update(connection, UPDATE_VERSION_META, List.of(meta.metaJson(), versionId));
            }
            // 正文没变就不追加版本行，说明改挂在当前版本上；空串视为「没填」，不清掉已有说明。
            // created_by 同理不动：这句说明挂在谁写的那条版本上，库里已经记着。
            if (!note.isEmpty()) {
                update(connection, UPDATE_VERSION_NOTE, List.of(note, versionId));
            }
            resources =
                    replaceResources
                            ? rebuildResources(connection, versionId, skillId, files, newBlobs)
                            : selectResources(connection, versionId);
        }
        return rewriteDefinition(
                connection, current, meta, contentHash, body.length(), versionId, resources, writer);
    }

    /** 清单变了就要重算派生列，且必须与写 {@code skill_resource} 同事务，否则行上的指纹对不上内容。 */
    private static Pending replaceResources(
            Connection connection, Row current, List<ResourceRef> resources, String operator)
            throws SQLException {
        return rewriteDefinition(
                connection,
                current,
                metaOf(current, null, current.description(), current.executionEnabled()),
                current.contentHash(),
                current.contentLength(),
                current.versionId(),
                resources,
                operator);
    }

    private static Pending rewriteDefinition(
            Connection connection,
            Row current,
            Meta meta,
            String contentHash,
            int contentLength,
            long versionId,
            List<ResourceRef> resources,
            String operator)
            throws SQLException {
        Pending pending =
                new Pending(
                        meta,
                        current.builtin(),
                        contentHash,
                        contentLength,
                        versionId,
                        resources,
                        current.revision() + 1,
                        operatorOrNull(operator));
        update(connection, UPDATE_DEFINITION, updateParams(current.id(), pending));
        return pending;
    }

    /** 写完成功后的行视图；{@code updatedAt} 取本机时刻，不值得为一个展示字段再查一次库。 */
    private static Row rowOf(String skillId, Pending pending) {
        Meta meta = pending.meta();
        return new Row(
                skillId,
                meta.description(),
                meta.tags(),
                meta.version(),
                pending.builtin(),
                meta.executionEnabled(),
                pending.contentHash(),
                pending.contentLength(),
                pending.resources().size(),
                SkillManifests.resourceHashOf(hashByPath(pending.resources())),
                pending.versionId(),
                pending.revision(),
                System.currentTimeMillis());
    }

    private <T> T inTransaction(String skillId, Tx<T> body) {
        List<String> newBlobs = new ArrayList<>();
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            boolean committed = false;
            try {
                T result = body.apply(connection, newBlobs);
                connection.commit();
                committed = true;
                return result;
            } catch (SQLException e) {
                rollback(connection, e);
                if (integrityViolation(e)) {
                    throw new PayrollException("技能已存在：" + skillId);
                }
                throw unavailable("写入技能 " + skillId + " 失败", e);
            } catch (RuntimeException e) {
                rollback(connection, e);
                throw e;
            } finally {
                if (!committed) {
                    cleanupBlobs(newBlobs);
                }
            }
        } catch (SQLException e) {
            throw unavailable("连接数据库失败", e);
        }
    }

    /**
     * 事务回滚后清理本轮新写、且没有任何清单行引用的 blob。
     *
     * <p>仍被引用的字节必须留下：另一节点可能已经提交了同一份内容。检查与删除之间还有毫秒级窗口，
     * 真发生时读取会明确报「技能资源缺失」，重新保存即可自愈，不值得为它加分布式锁；
     * 权威回收是 §14 的宽限期巡检。
     */
    private void cleanupBlobs(List<String> shas) {
        if (shas.isEmpty()) {
            return;
        }
        try (Connection connection = database.connection()) {
            for (String sha : shas) {
                if (referenced(connection, sha)) {
                    continue;
                }
                blobStore.delete(sha);
            }
        } catch (SQLException e) {
            log.warn("[Skill] 事务回滚后清理技能 blob 失败，留给保留期巡检：{}", e.getMessage());
        }
    }

    private static boolean referenced(Connection connection, String sha) throws SQLException {
        try (PreparedStatement statement =
                        connection.prepareStatement(COUNT_RESOURCE_REFERENCES);
                ResultSet rs = bind(statement, List.of(sha)).executeQuery()) {
            return rs.next() && rs.getLong(1) > 0;
        }
    }

    private long insertVersion(
            Connection connection,
            String skillId,
            int versionNo,
            String body,
            String contentHash,
            String metaJson,
            String changeNote,
            String operator)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(INSERT_VERSION, Statement.RETURN_GENERATED_KEYS)) {
            List<Object> values = new ArrayList<>();
            values.add(skillId);
            values.add(versionNo);
            values.add(body);
            values.add(metaJson);
            values.add(contentHash);
            values.add(body.length());
            values.add(requireChangeNote(changeNote));
            values.add(operatorOrNull(operator));
            bind(statement, values).executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new PayrollException("技能版本写入后未取到 version_id：" + skillId);
                }
                return keys.getLong(1);
            }
        }
    }

    /**
     * 操作者的唯一归一点：空白一律视作「没有操作者」，落 NULL 而不是空串或 {@code "admin"}——
     * 审计行的 {@code actor_name} 同样按「未知即缺省」处理，两边都得能被查出「是谁写的不知道」。
     */
    private static String operatorOrNull(String operator) {
        return operator == null || operator.isBlank() ? null : operator.trim();
    }

    /**
     * {@code change_note} 列宽 200，静默截断会让读者以为说明被完整保存，所以超限直接报错——
     * 与 {@code tags}/{@code skill_version} 同一口径（见 {@link #MAX_TAGS_CHARS}）。
     */
    private static String requireChangeNote(String changeNote) {
        if (changeNote == null || changeNote.isBlank()) {
            return "";
        }
        String trimmed = changeNote.trim();
        if (trimmed.length() > MAX_CHANGE_NOTE_CHARS) {
            throw new PayrollException(
                    "变更说明过长（" + trimmed.length() + " 字符），上限 " + MAX_CHANGE_NOTE_CHARS + " 字符");
        }
        return trimmed;
    }

    private static int nextVersionNo(Connection connection, String skillId) throws SQLException {
        try (PreparedStatement statement =
                        connection.prepareStatement(SELECT_MAX_VERSION_NO);
                ResultSet rs = bind(statement, List.of(skillId)).executeQuery()) {
            return rs.next() ? rs.getInt(1) + 1 : 1;
        }
    }

    private static int nextSortOrder(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                        connection.prepareStatement(SELECT_NEXT_SORT_ORDER);
                ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static String selectMetaJson(Connection connection, long versionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_META_JSON);
                ResultSet rs = bind(statement, List.of(versionId)).executeQuery()) {
            return rs.next() ? text(rs.getString(1)) : EMPTY_META_JSON;
        }
    }

    private static Map<String, Object> selectCustomMeta(Connection connection, long versionId)
            throws SQLException {
        return parseCustomMeta(versionId, selectMetaJson(connection, versionId));
    }

    /** 元信息解析失败不阻断读取：坏 {@code meta_json} 只让标签/版本号等展示字段变空，正文照常可用。 */
    private static Map<String, Object> parseCustomMeta(long versionId, String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JsonSupport.fromJson(json, META_JSON);
        } catch (RuntimeException e) {
            log.warn("[Skill] 技能版本 {} 的 meta_json 无法解析，元信息按空处理：{}", versionId, e.getMessage());
            return Map.of();
        }
    }

    private static VersionBody selectVersionBody(Connection connection, Row row)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_VERSION_BODY);
                ResultSet rs = bind(statement, List.of(row.versionId())).executeQuery()) {
            if (!rs.next()) {
                throw new PayrollException("技能正文缺失：" + row.id());
            }
            return new VersionBody(rs.getInt(1), rs.getString(2));
        }
    }

    private static VersionTarget selectVersionTarget(
            Connection connection, String skillId, int versionNo) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_VERSION_BY_NO);
                ResultSet rs = bind(statement, List.of(skillId, versionNo)).executeQuery()) {
            if (!rs.next()) {
                throw new PayrollException("技能 " + skillId + " 没有 v" + versionNo + " 版本");
            }
            return new VersionTarget(
                    rs.getLong("version_id"),
                    rs.getString("body_md"),
                    rs.getString("content_hash"));
        }
    }

    /**
     * 回滚前的字节体检：清单行还在、介质里的字节已被回收时拒绝回滚。
     * 文件包停用（{@code PAYROLL_SKILL_FILES_ENABLED=false}）时运行期根本不读清单，跳过检查。
     */
    private void requireBlobsPresent(
            String skillId, int versionNo, List<ResourceRef> resources) {
        if (!fileSettings.enabled() || resources.isEmpty()) {
            return;
        }
        for (ResourceRef ref : resources) {
            if (!blobStore.exists(ref.sha256())) {
                throw new PayrollException(
                        "技能 " + skillId + " 的 v" + versionNo + " 文件字节缺失：" + ref.path());
            }
        }
    }

    private static List<ResourceRef> selectResources(Connection connection, long versionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_RESOURCES);
                ResultSet rs = bind(statement, List.of(versionId)).executeQuery()) {
            List<ResourceRef> refs = new ArrayList<>();
            while (rs.next()) {
                refs.add(
                        new ResourceRef(
                                rs.getString("path"),
                                text(rs.getString("sha256")),
                                rs.getLong("size_bytes"),
                                text(rs.getString("media_type")),
                                rs.getBoolean("is_binary")));
            }
            return refs;
        }
    }

    private List<ResourceRef> writeResources(
            Connection connection,
            long versionId,
            String skillId,
            List<SkillFilePackage.SkillFile> files,
            List<String> newBlobs)
            throws SQLException {
        List<ResourceRef> refs = new ArrayList<>(files.size());
        for (SkillFilePackage.SkillFile file : files) {
            byte[] bytes = file.content().getBytes(StandardCharsets.UTF_8);
            String sha = SkillManifests.contentHash(file.content());
            // 先探一次存在性：已落盘的字节不是本轮产生的，回滚时不能删（别的版本正在引用它）。
            boolean fresh = !blobStore.exists(sha);
            SkillBlobStore.BlobRef blob = blobStore.put(bytes, MEDIA_TYPE_TEXT);
            if (fresh && !newBlobs.contains(blob.sha256())) {
                newBlobs.add(blob.sha256());
            }
            update(
                    connection,
                    INSERT_RESOURCE,
                    List.of(
                            versionId,
                            skillId,
                            file.path(),
                            blob.sha256(),
                            blob.sizeBytes(),
                            blob.mediaType()));
            upsertBlob(connection, blob);
            refs.add(
                    new ResourceRef(
                            file.path(), blob.sha256(), blob.sizeBytes(), blob.mediaType(), false));
        }
        return refs;
    }

    private List<ResourceRef> rebuildResources(
            Connection connection,
            long versionId,
            String skillId,
            List<SkillFilePackage.SkillFile> files,
            List<String> newBlobs)
            throws SQLException {
        // 整包替换：被移除文件的字节留给 §14 巡检，避免在这里判断跨版本引用。
        update(connection, DELETE_RESOURCES_OF_VERSION, List.of(versionId));
        return writeResources(connection, versionId, skillId, files, newBlobs);
    }

    private static List<ResourceRef> copyResources(
            Connection connection, long fromVersionId, long toVersionId) throws SQLException {
        update(connection, COPY_RESOURCES, List.of(toVersionId, fromVersionId));
        return selectResources(connection, toVersionId);
    }

    private void upsertBlob(Connection connection, SkillBlobStore.BlobRef ref) throws SQLException {
        update(
                connection,
                database.dialect(UPSERT_BLOB_H2, UPSERT_BLOB_MYSQL),
                List.of(
                        ref.sha256(),
                        ref.storageKey(),
                        ref.sizeBytes(),
                        ref.mediaType(),
                        blobStore.location()));
    }

    private static int deleteResourceRow(Connection connection, long versionId, String path)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_RESOURCE_ROW)) {
            return bind(statement, List.of(versionId, path)).executeUpdate();
        }
    }

    /** {@code forUpdate=true} 只允许在事务里调用：行锁随事务提交才释放。 */
    private Row readRow(Connection connection, String skillId, boolean forUpdate)
            throws SQLException {
        try (PreparedStatement statement =
                        connection.prepareStatement(
                                forUpdate ? SELECT_ROW_FOR_UPDATE : SELECT_ROW);
                ResultSet rs = bind(statement, List.of(skillId)).executeQuery()) {
            return rs.next() ? readRow(rs) : null;
        }
    }

    /** 写路径唯一的行读取方式：拿到行锁后才能分配 version_no、递增 revision。 */
    private Row lockRow(
            Connection connection, String skillId, String expectedRevision, String missingMessage)
            throws SQLException {
        Row row = readRow(connection, skillId, true);
        if (row == null) {
            throw new PayrollException(missingMessage);
        }
        requireRevision(row, expectedRevision);
        return row;
    }

    private static void requireRevision(Row current, String expected) {
        if (expected == null || expected.isBlank()) {
            return;
        }
        String actual = current == null ? "" : String.valueOf(current.revision());
        if (!actual.equals(expected)) {
            throw new PayrollConflictException("配置已被他人修改，请刷新后重试", actual);
        }
    }

    private void requireFilesEnabled() {
        if (!fileSettings.enabled()) {
            throw new PayrollException(
                    "技能文件包已停用（PAYROLL_SKILL_FILES_ENABLED=false），无法编辑文件");
        }
    }

    private static SkillDetail builtinDetail(String skillId) {
        if (!RoleSkills.ids().contains(skillId)) {
            throw new PayrollException("技能不存在：" + skillId);
        }
        AgentSkill skill = RoleSkills.skill(skillId);
        return new SkillDetail(
                skillId,
                skill.getDescription(),
                skill.getSkillContent(),
                NacosSkillRegistry.SOURCE_BUILTIN,
                true,
                false,
                0L,
                List.of(),
                false,
                Map.of(),
                "",
                0);
    }

    private static SkillSummary builtinSummary(String skillId) {
        AgentSkill skill = RoleSkills.skill(skillId);
        return new SkillSummary(
                skillId,
                skill.getDescription(),
                NacosSkillRegistry.SOURCE_BUILTIN,
                true,
                false,
                0L,
                skill.getSkillContent().length(),
                0,
                "");
    }

    /** 列序：skill_id、name、11 个共享值、created_by、updated_by、builtin、sort_order。 */
    private static List<Object> insertParams(Connection connection, String skillId, Pending pending)
            throws SQLException {
        List<Object> params = new ArrayList<>();
        params.add(skillId);
        params.add(skillId);
        params.addAll(definitionValues(pending));
        params.add(pending.operator());
        params.add(pending.operator());
        params.add(pending.builtin());
        params.add(nextSortOrder(connection));
        return params;
    }

    /** 列序：11 个共享值 + updated_by + WHERE skill_id。 */
    private static List<Object> updateParams(String skillId, Pending pending) {
        List<Object> params = new ArrayList<>(definitionValues(pending));
        params.add(pending.operator());
        params.add(skillId);
        return params;
    }

    /** 定义行的取值顺序，INSERT 与 UPDATE 共用：三个 resource_* 只能由清单现算。 */
    private static List<Object> definitionValues(Pending pending) {
        Meta meta = pending.meta();
        return List.of(
                meta.description(),
                joinTags(meta.tags()),
                meta.version(),
                meta.executionEnabled(),
                pending.contentHash(),
                pending.contentLength(),
                pending.resources().size(),
                sumBytes(pending.resources()),
                SkillManifests.resourceHashOf(hashByPath(pending.resources())),
                pending.versionId(),
                pending.revision());
    }

    private static PreparedStatement bind(PreparedStatement statement, List<Object> values)
            throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            statement.setObject(i + 1, values.get(i));
        }
        return statement;
    }

    private static void update(Connection connection, String sql, List<Object> values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values).executeUpdate();
        }
    }

    private static long sumBytes(List<ResourceRef> refs) {
        long total = 0L;
        for (ResourceRef ref : refs) {
            total += ref.sizeBytes();
        }
        return total;
    }

    /** 库侧的资源指纹：blob 的 sha256 与 {@link SkillManifests#contentHash} 同算法，可直接聚合。 */
    private static Map<String, String> hashByPath(List<ResourceRef> refs) {
        Map<String, String> hashes = new LinkedHashMap<>();
        refs.forEach(ref -> hashes.put(ref.path(), ref.sha256()));
        return hashes;
    }

    private static String joinTags(List<String> tags) {
        String value = String.join(",", tags == null ? List.of() : tags);
        if (value.length() > MAX_TAGS_CHARS) {
            throw new PayrollException("技能标签总长不能超过 " + MAX_TAGS_CHARS + " 个字符");
        }
        return value;
    }

    /** 版本号会渲染进系统提示词的目录行，截断等于悄悄改提示词，只能报错。 */
    private static String requireVersionLength(String version) {
        if (version.length() > MAX_VERSION_CHARS) {
            throw new PayrollException("技能版本号不能超过 " + MAX_VERSION_CHARS + " 个字符");
        }
        return version;
    }

    private static List<String> splitTags(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (String piece : value.split(",")) {
            String tag = piece.trim();
            if (!tag.isEmpty() && !tags.contains(tag)) {
                tags.add(tag);
            }
        }
        return List.copyOf(tags);
    }

    private static Map<String, Object> customMeta(Map<String, Object> metadata) {
        Map<String, Object> custom = new LinkedHashMap<>();
        if (metadata != null) {
            metadata.forEach(
                    (key, value) -> {
                        if (key != null && value != null && !COLUMNAR_META_KEYS.contains(key)) {
                            custom.put(key, value);
                        }
                    });
        }
        return custom;
    }

    /** 详情用的元信息：拆列字段回填进 map，与 Nacos 解析出的 frontmatter 视图保持同形。 */
    private static Map<String, Object> metadataOf(Row row, Map<String, Object> custom) {
        Map<String, Object> metadata = new LinkedHashMap<>(custom);
        metadata.put("name", row.id());
        if (!row.description().isBlank()) {
            metadata.put("description", row.description());
        }
        if (!row.tags().isEmpty()) {
            metadata.put("tags", row.tags());
        }
        if (!row.version().isBlank()) {
            metadata.put("version", row.version());
        }
        if (row.executionEnabled()) {
            metadata.put(SkillExecutions.META_EXECUTION, Map.of("enabled", true));
        }
        // 保序：运行期装配直接把这张 map 交给 AgentSkill，Map.copyOf 的迭代顺序不稳定会让指纹抖。
        return Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    private static List<SkillFilePackage.Summary> summaries(List<ResourceRef> refs) {
        return refs.stream()
                .map(
                        ref ->
                                new SkillFilePackage.Summary(
                                        ref.path(),
                                        (int) ref.sizeBytes(),
                                        SkillFilePackage.kind(ref.path())))
                .toList();
    }

    /** 列表不回正文与字节，{@code fileCount} 直接取行上的 {@code resource_count}。 */
    private static SkillSummary summary(Row row) {
        return new SkillSummary(
                row.id(),
                row.description(),
                SOURCE_DB,
                row.builtin(),
                row.builtin(),
                row.updatedAt(),
                row.contentLength(),
                row.resourceCount(),
                String.valueOf(row.revision()));
    }

    /** 摘要行 + setup 判定；版本号与写入者直接来自定义行与当前版本行。 */
    private static SkillSummary summary(SummaryRow row, Set<String> setupPaths) {
        Map<String, Object> meta = parseMeta(row.metaJson());
        return new SkillSummary(
                row.id(),
                row.description(),
                SOURCE_DB,
                row.builtin(),
                row.builtin(),
                row.updatedAt(),
                row.contentLength(),
                row.resourceCount(),
                String.valueOf(row.revision()),
                row.versionNo(),
                row.updatedBy(),
                needsData(meta, setupPaths),
                meta == null ? null : SkillProjectParser.dataMaxBytes(meta));
    }

    private Row readRow(ResultSet rs) throws SQLException {
        java.sql.Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new Row(
                rs.getString("skill_id"),
                text(rs.getString("description")),
                splitTags(rs.getString("tags")),
                text(rs.getString("skill_version")),
                rs.getBoolean("builtin"),
                rs.getBoolean("execution_enabled"),
                text(rs.getString("content_hash")),
                rs.getInt("content_length"),
                rs.getInt("resource_count"),
                // 文件包停用运行期不装配资源，目录报出的指纹必须一起归零：
                // 指纹进智能体缓存，留着旧值等于让缓存与内容脱钩。
                fileSettings.enabled() ? text(rs.getString("resource_hash")) : "",
                rs.getLong("current_version_id"),
                rs.getLong("revision"),
                updatedAt == null ? 0L : updatedAt.getTime());
    }

    /**
     * 定宽列归一化：{@code CHAR(64)} 的空串会被读成一串空格，而资源指纹参与缓存比较，
     * 空格串会让「无资源」被读成「资源变了」。哈希与文本列本身不可能带首尾空白，直接裁掉。
     */
    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static PayrollUnavailableException unavailable(String action, SQLException cause) {
        return new PayrollUnavailableException(action + "：" + cause.getMessage(), cause);
    }

    private static boolean integrityViolation(SQLException e) {
        String state = e.getSQLState();
        return state != null && state.startsWith("23");
    }

    private static void rollback(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollback) {
            cause.addSuppressed(rollback);
        }
    }
}
