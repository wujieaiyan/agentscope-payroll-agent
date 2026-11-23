package io.agentscope.examples.payroll.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.examples.payroll.devteam.DevTask;
import io.agentscope.examples.payroll.devteam.DevTaskStatus;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 研发任务面板存储（P0-7）：按计划整体替换、按任务更新状态与负责人。
 *
 * <p>{@code (plan_id, task_id)} 唯一，重复发布同一计划先删后插，保证面板与
 * {@code tasks.json} 一致；依赖/产物/验收以 JSON 数组落库，数据库仍是唯一事实源。
 */
public final class DevTaskStore {

    private static final int MAX_PLAN_ID = 64;
    private static final int MAX_TASK_ID = 64;
    private static final int MAX_TITLE = 200;
    private static final int MAX_ROLE = 64;
    private static final int MAX_OWNER = 128;
    private static final int MAX_NOTE = 500;
    private static final int MAX_ITEMS = 64;
    private static final int MAX_ITEM_LENGTH = 500;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private static final String SELECT =
            """
            SELECT row_id, plan_id, task_id, title, role, owner_agent, priority, status,
                   depends_on, outputs, acceptance, note, attempt, error,
                   started_at, finished_at, created_at, updated_at
            FROM dev_tasks
            """;

    private final PayrollDatabase database;

    public DevTaskStore(PayrollDatabase database) {
        this.database = database;
    }

    /** 整体替换一个计划的任务清单；返回按优先级与行序排好的入库结果。 */
    public List<DevTask> replacePlan(String planId, List<DevTask> tasks) {
        String plan = requireId(planId, "计划 id", MAX_PLAN_ID);
        List<DevTask> values = tasks == null ? List.of() : tasks;
        for (DevTask task : values) {
            requireTask(task);
        }
        LocalDateTime now = LocalDateTime.now();
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delete =
                        connection.prepareStatement("DELETE FROM dev_tasks WHERE plan_id = ?")) {
                    delete.setString(1, plan);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
                    for (DevTask task : values) {
                        bindInsert(insert, plan, task, now);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存研发任务失败：" + e.getMessage(), e);
        }
        return list(plan);
    }

    public List<DevTask> list(String planId) {
        String plan = requireId(planId, "计划 id", MAX_PLAN_ID);
        String sql = SELECT + " WHERE plan_id = ? ORDER BY priority DESC, row_id ASC";
        List<DevTask> tasks = new ArrayList<>();
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, plan);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    tasks.add(map(rows));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取研发任务失败：" + e.getMessage(), e);
        }
        return List.copyOf(tasks);
    }

    public Optional<DevTask> find(String planId, String taskId) {
        String plan = requireId(planId, "计划 id", MAX_PLAN_ID);
        String task = requireId(taskId, "任务 id", MAX_TASK_ID);
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement(SELECT + " WHERE plan_id = ? AND task_id = ?")) {
            statement.setString(1, plan);
            statement.setString(2, task);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(map(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取研发任务失败：" + e.getMessage(), e);
        }
    }

    /** 更新状态；返回 false 表示任务不存在或未变化。 */
    public boolean updateStatus(
            String planId, String taskId, DevTaskStatus status, String note) {
        String plan = requireId(planId, "计划 id", MAX_PLAN_ID);
        String task = requireId(taskId, "任务 id", MAX_TASK_ID);
        if (status == null) {
            throw new PayrollException("任务状态不能为空");
        }
        String sql =
                "UPDATE dev_tasks SET status = ?, note = ?, updated_at = ?"
                        + " WHERE plan_id = ? AND task_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.wireName());
            statement.setString(2, trimTo(note, MAX_NOTE));
            statement.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
            statement.setString(4, plan);
            statement.setString(5, task);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("更新研发任务状态失败：" + e.getMessage(), e);
        }
    }

    /** 手动改派负责人；空串表示清空。 */
    public boolean assign(String planId, String taskId, String ownerAgent) {
        String plan = requireId(planId, "计划 id", MAX_PLAN_ID);
        String task = requireId(taskId, "任务 id", MAX_TASK_ID);
        String owner = trimTo(ownerAgent, MAX_OWNER);
        String sql =
                "UPDATE dev_tasks SET owner_agent = ?, updated_at = ?"
                        + " WHERE plan_id = ? AND task_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner);
            statement.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            statement.setString(3, plan);
            statement.setString(4, task);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("改派研发任务失败：" + e.getMessage(), e);
        }
    }

    /** 进入执行：状态置 RUNNING 并记录开始时间；调用方负责先校验状态流转。 */
    public boolean start(String planId, String taskId, String note) {
        String plan = requireId(planId, "计划 id", MAX_PLAN_ID);
        String task = requireId(taskId, "任务 id", MAX_TASK_ID);
        LocalDateTime now = LocalDateTime.now();
        String sql =
                "UPDATE dev_tasks SET status = ?, note = ?, error = '',"
                        + " started_at = ?, finished_at = NULL, updated_at = ?"
                        + " WHERE plan_id = ? AND task_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, DevTaskStatus.RUNNING.wireName());
            statement.setString(2, trimTo(note, MAX_NOTE));
            statement.setTimestamp(3, Timestamp.valueOf(now));
            statement.setTimestamp(4, Timestamp.valueOf(now));
            statement.setString(5, plan);
            statement.setString(6, task);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("启动研发任务失败：" + e.getMessage(), e);
        }
    }

    /** 结束执行：写入终态、备注与失败原因，并记录结束时间。 */
    public boolean finish(
            String planId, String taskId, DevTaskStatus status, String note, String error) {
        String plan = requireId(planId, "计划 id", MAX_PLAN_ID);
        String task = requireId(taskId, "任务 id", MAX_TASK_ID);
        if (status == null) {
            throw new PayrollException("任务状态不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        String sql =
                "UPDATE dev_tasks SET status = ?, note = ?, error = ?,"
                        + " finished_at = ?, updated_at = ?"
                        + " WHERE plan_id = ? AND task_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.wireName());
            statement.setString(2, trimTo(note, MAX_NOTE));
            statement.setString(3, trimTo(error, MAX_NOTE));
            statement.setTimestamp(4, Timestamp.valueOf(now));
            statement.setTimestamp(5, Timestamp.valueOf(now));
            statement.setString(6, plan);
            statement.setString(7, task);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("结束研发任务失败：" + e.getMessage(), e);
        }
    }

    /** 人工重试：次数加一并重置为 READY，清空上次开始/结束时间与失败原因。 */
    public boolean retry(String planId, String taskId, String note) {
        String plan = requireId(planId, "计划 id", MAX_PLAN_ID);
        String task = requireId(taskId, "任务 id", MAX_TASK_ID);
        String sql =
                "UPDATE dev_tasks SET status = ?, note = ?, error = '',"
                        + " attempt = attempt + 1, started_at = NULL, finished_at = NULL,"
                        + " updated_at = ?"
                        + " WHERE plan_id = ? AND task_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, DevTaskStatus.READY.wireName());
            statement.setString(2, trimTo(note, MAX_NOTE));
            statement.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
            statement.setString(4, plan);
            statement.setString(5, task);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("重试研发任务失败：" + e.getMessage(), e);
        }
    }

    /**
     * 启动恢复：上一实例遗留的 RUNNING 行已无执行线程，统一置为 FAILED 等待人工重试。
     *
     * @return 受影响行数
     */
    public int recoverInterrupted(String note, String error) {
        String sql =
                "UPDATE dev_tasks SET status = ?, note = ?, error = ?,"
                        + " finished_at = ?, updated_at = ?"
                        + " WHERE status = ?";
        LocalDateTime now = LocalDateTime.now();
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, DevTaskStatus.FAILED.wireName());
            statement.setString(2, trimTo(note, MAX_NOTE));
            statement.setString(3, trimTo(error, MAX_NOTE));
            statement.setTimestamp(4, Timestamp.valueOf(now));
            statement.setTimestamp(5, Timestamp.valueOf(now));
            statement.setString(6, DevTaskStatus.RUNNING.wireName());
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("恢复中断的研发任务失败：" + e.getMessage(), e);
        }
    }

    private static final String INSERT =
            """
            INSERT INTO dev_tasks
                (plan_id, task_id, title, role, owner_agent, priority, status,
                 depends_on, outputs, acceptance, note, attempt, error,
                 started_at, finished_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static void bindInsert(
            PreparedStatement statement, String planId, DevTask task, LocalDateTime now)
            throws SQLException {
        statement.setString(1, planId);
        statement.setString(2, task.taskId());
        statement.setString(3, trimTo(task.title(), MAX_TITLE));
        statement.setString(4, trimTo(task.role(), MAX_ROLE));
        statement.setString(5, trimTo(task.ownerAgent(), MAX_OWNER));
        statement.setInt(6, task.priority());
        statement.setString(7, task.status().wireName());
        statement.setString(8, toJson(task.dependsOn()));
        statement.setString(9, toJson(task.outputs()));
        statement.setString(10, toJson(task.acceptance()));
        statement.setString(11, trimTo(task.note(), MAX_NOTE));
        statement.setInt(12, task.attempt());
        statement.setString(13, trimTo(task.error(), MAX_NOTE));
        setTimestamp(statement, 14, task.startedAt());
        setTimestamp(statement, 15, task.finishedAt());
        statement.setTimestamp(16, Timestamp.valueOf(now));
        statement.setTimestamp(17, Timestamp.valueOf(now));
    }

    private static void setTimestamp(PreparedStatement statement, int index, LocalDateTime value)
            throws SQLException {
        if (value == null) {
            statement.setTimestamp(index, null);
        } else {
            statement.setTimestamp(index, Timestamp.valueOf(value));
        }
    }

    private static DevTask map(ResultSet rows) throws SQLException {
        return new DevTask(
                rows.getLong("row_id"),
                rows.getString("plan_id"),
                rows.getString("task_id"),
                rows.getString("title"),
                rows.getString("role"),
                rows.getString("owner_agent"),
                rows.getInt("priority"),
                DevTaskStatus.parse(rows.getString("status")).orElse(DevTaskStatus.PENDING),
                fromJson(rows.getString("depends_on")),
                fromJson(rows.getString("outputs")),
                fromJson(rows.getString("acceptance")),
                rows.getString("note"),
                rows.getInt("attempt"),
                rows.getString("error"),
                rows.getObject("started_at", LocalDateTime.class),
                rows.getObject("finished_at", LocalDateTime.class),
                rows.getObject("created_at", LocalDateTime.class),
                rows.getObject("updated_at", LocalDateTime.class));
    }

    private static void requireTask(DevTask task) {
        if (task == null) {
            throw new PayrollException("任务不能为空");
        }
        requireId(task.taskId(), "任务 id", MAX_TASK_ID);
        if (task.title().isBlank()) {
            throw new PayrollException("任务标题不能为空：" + task.taskId());
        }
        if (task.role().isBlank()) {
            throw new PayrollException("任务角色不能为空：" + task.taskId());
        }
    }

    private static String requireId(String value, String label, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || trimmed.length() > maxLength || !ID.matcher(trimmed).matches()) {
            throw new PayrollException(label + " 非法：" + value);
        }
        return trimmed;
    }

    private static String toJson(List<String> values) {
        List<String> normalized = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                if (normalized.size() >= MAX_ITEMS) {
                    break;
                }
                normalized.add(trimTo(value, MAX_ITEM_LENGTH));
            }
        }
        try {
            return JSON.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化任务字段失败：" + e.getMessage(), e);
        }
    }

    private static List<String> fromJson(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = JSON.readValue(value, STRING_LIST);
            return parsed == null ? List.of() : List.copyOf(parsed);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static String trimTo(String value, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }
}
