package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.retention.RetentionService;
import io.agentscope.examples.payroll.retention.RetentionSettings;
import io.agentscope.examples.payroll.retention.RetentionStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 保留任务：删除顺序、批删、孤儿兜底、幂等与失败跳过。 */
class RetentionServiceTest {

    private static final LocalDateTime OLD = LocalDateTime.now().minusDays(400);
    private static final LocalDateTime RECENT = LocalDateTime.now().minusDays(5);
    private static final LocalDateTime USAGE_OLD = LocalDateTime.now().minusDays(200);

    @TempDir Path attachmentRoot;

    @Test
    @DisplayName("一次运行按 1→6 删除关联表，消息删净后同一轮删除会话")
    void deletesDependencyChainThenSessionInOneRun() throws Exception {
        PayrollDatabase database = PayrollDatabase.inMemory("c4-order-" + System.nanoTime());
        Path file = attachmentRoot.resolve("1/00000000-0000-0000-0000-000000000001/a.txt");
        try (Connection c = database.connection()) {
            insertSession(c, 1, OLD);
            insertMessage(c, 10, 1, OLD);
            insertTrace(c, 100, 10, 1, OLD);
            insertFeedback(c, 1000, 10, 1, OLD);
            insertProposal(c, "P-1", 1, 10, "CONFIRMED", OLD);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "x");
            insertAttachment(
                    c, 500, 1, 10L, "1/00000000-0000-0000-0000-000000000001/a.txt", 1L, OLD);

            insertSession(c, 2, RECENT);
            insertMessage(c, 20, 2, RECENT);
            insertTrace(c, 200, 20, 2, RECENT);
        }

        RecordingStore store = new RecordingStore(database);
        RetentionService service = service(store, settings());
        RetentionService.RetentionReport report = service.run(false, null);

        assertTrue(report.completed(), "全部表应在预算内完成");
        assertOrder(
                store.deletes,
                "chat_tool_traces",
                "chat_feedback",
                "chat_action_proposals",
                "chat_attachments",
                "chat_messages",
                "chat_sessions");
        assertEquals(0, count(database, "chat_messages", "message_id = 10"));
        assertEquals(0, count(database, "chat_tool_traces", "trace_id = 100"));
        assertEquals(0, count(database, "chat_feedback", "feedback_id = 1000"));
        assertEquals(0, count(database, "chat_action_proposals", "proposal_id = 'P-1'"));
        assertEquals(0, count(database, "chat_attachments", "attachment_id = 500"));
        assertEquals(0, count(database, "chat_sessions", "session_id = 1"));
        assertFalse(Files.exists(file), "附件文件应随行一起删除");
        assertEquals(1, count(database, "chat_messages", "message_id = 20"));
        assertEquals(1, count(database, "chat_sessions", "session_id = 2"));
    }

    @Test
    @DisplayName("孤儿兜底清除父行已消失的子行，且不受保留期约束的近期孤儿保留")
    void orphanSweepRemovesDanglingChildren() throws Exception {
        PayrollDatabase database = PayrollDatabase.inMemory("c4-orphan-" + System.nanoTime());
        try (Connection c = database.connection()) {
            insertTrace(c, 300, 999_999, 1, OLD);
            insertFeedback(c, 3000, 999_998, 1, OLD);
            insertProposal(c, "P-orphan", 1, 999_997, "CONFIRMED", OLD);
            insertAttachment(
                    c, 600, 1, 999_996L, "1/00000000-0000-0000-0000-000000000002/b.txt", 2L, OLD);
            Path orphanFile =
                    attachmentRoot.resolve("1/00000000-0000-0000-0000-000000000002/b.txt");
            Files.createDirectories(orphanFile.getParent());
            Files.writeString(orphanFile, "x");
            insertTeamRun(c, 700, 1, 999_995, OLD, OLD);
            insertKbLog(c, 800, 999_994, OLD);
            insertTrace(c, 301, 999_993, 1, RECENT);
        }

        RetentionStore store = new RetentionStore(database);
        LocalDateTime orphanCutoff = LocalDateTime.now().minusDays(365).minusDays(1);
        assertTrue(store.selectOrphanTraces(orphanCutoff, 100).contains(300L));
        assertTrue(store.selectOrphanFeedback(orphanCutoff, 100).contains(3000L));
        assertTrue(store.selectOrphanProposalIds(orphanCutoff, 100).contains("P-orphan"));
        assertTrue(store.selectOrphanAttachments(orphanCutoff, 100).stream()
                .anyMatch(row -> row.id() == 600L));
        assertTrue(store.selectArchivedTeamRuns(orphanCutoff, 100).contains(700L));
        assertTrue(store.selectOrphanTaskLogs(orphanCutoff, 100).contains(800L));

        RetentionService service = service(store, settings());
        RetentionService.RetentionReport report = service.run(false, null);

        assertTrue(report.completed());
        assertEquals(0, count(database, "chat_tool_traces", "trace_id = 300"));
        assertEquals(0, count(database, "chat_feedback", "feedback_id = 3000"));
        assertEquals(0, count(database, "chat_action_proposals", "proposal_id = 'P-orphan'"));
        assertEquals(0, count(database, "chat_attachments", "attachment_id = 600"));
        assertEquals(0, count(database, "agent_team_runs", "run_id = 700"));
        assertEquals(0, count(database, "kb_task_log", "log_id = 800"));
        assertEquals(1, count(database, "chat_tool_traces", "trace_id = 301"), "未过孤儿期的行保留");
    }

    @Test
    @DisplayName("400 个过期空会话按 200/批删除，仍有消息的会话保留")
    void deletesEmptySessionsInBatchesButKeepsSessionsWithMessages() throws Exception {
        PayrollDatabase database = PayrollDatabase.inMemory("c4-sessions-" + System.nanoTime());
        try (Connection c = database.connection()) {
            for (int i = 1; i <= 400; i++) {
                insertSession(c, i, OLD);
            }
            insertSession(c, 401, OLD);
            insertMessage(c, 9001, 401, OLD);
            insertSession(c, 402, OLD);
            insertMessage(c, 9002, 402, RECENT);
        }

        RecordingStore store = new RecordingStore(database);
        RetentionService service = service(store, settings());
        RetentionService.RetentionReport report = service.run(false, null);

        RetentionService.TableReport sessions = table(report, "chat_sessions");
        assertEquals(401, sessions.deleted());
        assertEquals(List.of(200, 200, 1), store.sessionBatches);
        assertEquals(0, count(database, "chat_sessions", "session_id = 401"));
        assertEquals(0, count(database, "chat_messages", "message_id = 9001"));
        assertEquals(1, count(database, "chat_sessions", "session_id = 402"));
        assertEquals(1, count(database, "chat_messages", "message_id = 9002"));
    }

    @Test
    @DisplayName("附件文件已不存在按成功处理，未绑定消息的过期附件也按主删清理")
    void attachmentMissingFileIsTreatedAsSuccess() throws Exception {
        PayrollDatabase database = PayrollDatabase.inMemory("c4-attach-" + System.nanoTime());
        String keptKey = "7/00000000-0000-0000-0000-000000000003/c.txt";
        Path keptFile = attachmentRoot.resolve(keptKey);
        try (Connection c = database.connection()) {
            insertAttachment(c, 601, 7, null, "7/missing/file.txt", 3L, OLD);
            insertAttachment(c, 602, 7, null, keptKey, 4L, OLD);
        }
        Files.createDirectories(keptFile.getParent());
        Files.writeString(keptFile, "x");

        RetentionService service = service(new RetentionStore(database), settings());
        RetentionService.RetentionReport report = service.run(false, null);

        assertTrue(report.completed());
        assertEquals(2, report.attachmentFiles());
        assertEquals(0, count(database, "chat_attachments", "attachment_id IN (601, 602)"));
        assertFalse(Files.exists(keptFile));
    }

    @Test
    @DisplayName("过期 PENDING 提案首轮置 EXPIRED、次轮删除")
    void pendingProposalExpiresThenDeletesNextRound() throws Exception {
        PayrollDatabase database = PayrollDatabase.inMemory("c4-pending-" + System.nanoTime());
        try (Connection c = database.connection()) {
            insertProposal(c, "P-pending", 1, 0, "PENDING", OLD);
        }
        RetentionService service = service(new RetentionStore(database), settings());

        RetentionService.RetentionReport first = service.run(false, null);
        assertEquals(0, table(first, "chat_action_proposals").deleted());
        assertEquals(1, count(database, "chat_action_proposals", "proposal_id = 'P-pending'"));
        assertEquals("EXPIRED", status(database, "P-pending"));

        RetentionService.RetentionReport second = service.run(false, null);
        assertEquals(1, table(second, "chat_action_proposals").deleted());
        assertEquals(0, count(database, "chat_action_proposals", "proposal_id = 'P-pending'"));
    }

    @Test
    @DisplayName("单表 DELETE 连续失败两轮后跳过，不再重选同一批，其他表照常完成")
    void failureInjectionSkipsTableAfterTwoRounds() throws Exception {
        PayrollDatabase database = PayrollDatabase.inMemory("c4-fail-" + System.nanoTime());
        try (Connection c = database.connection()) {
            insertSession(c, 1, OLD);
            for (int i = 1; i <= 3; i++) {
                insertMessage(c, 100 + i, 1, OLD);
            }
            insertTrace(c, 900, 101, 1, OLD);
        }

        FailingStore store = new FailingStore(database);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RetentionService service =
                new RetentionService(store, settings(), registry);

        RetentionService.RetentionReport first = service.run(false, null);
        RetentionService.TableReport firstMessages = table(first, "chat_messages");
        assertEquals(1, firstMessages.failedBatches());
        assertFalse(firstMessages.skipped());
        assertEquals(1, table(first, "chat_tool_traces").deleted());

        RetentionService.RetentionReport second = service.run(false, null);
        assertTrue(table(second, "chat_messages").skipped());
        assertEquals(2L, store.callsFor("chat_messages"));

        service.run(false, null);
        assertEquals(2L, store.callsFor("chat_messages"), "跳过轮次不得再触发 DELETE");
        assertEquals(3, count(database, "chat_messages", "session_id = 1"));

        var skipped = registry.get("payroll.retention.skipped").tag("table", "chat_messages");
        assertNotNull(skipped);
        assertTrue(skipped.counter().count() >= 1);
    }

    @Test
    @DisplayName("dry-run 各表命中行数与 COUNT(*) 一致且不删除数据")
    void dryRunCountsMatchTableCounts() throws Exception {
        PayrollDatabase database = PayrollDatabase.inMemory("c4-dry-" + System.nanoTime());
        try (Connection c = database.connection()) {
            insertSession(c, 1, OLD);
            insertSession(c, 2, RECENT);
            insertMessage(c, 10, 1, OLD);
            insertMessage(c, 11, 1, OLD);
            insertMessage(c, 12, 1, RECENT);
            insertTrace(c, 100, 10, 1, OLD);
            insertFeedback(c, 1000, 10, 1, OLD);
            insertProposal(c, "P-dry", 1, 0, "REJECTED", OLD);
            insertAttachment(c, 500, 1, null, "1/missing/dry.txt", 9L, OLD);
            insertUsage(c, 600, USAGE_OLD);
            insertKbTask(c, 700, "SUCCEEDED", USAGE_OLD);
            insertKbLog(c, 800, 700, USAGE_OLD);
        }

        RetentionService service = service(new RetentionStore(database), settings());
        RetentionService.RetentionReport report = service.run(true, null);

        assertTrue(report.dryRun());
        assertTrue(report.completed());
        assertEquals(1, table(report, "chat_tool_traces").selected());
        assertEquals(1, table(report, "chat_feedback").selected());
        assertEquals(1, table(report, "chat_action_proposals").selected());
        assertEquals(1, table(report, "chat_attachments").selected());
        assertEquals(2, table(report, "chat_messages").selected());
        assertEquals(1, table(report, "chat_sessions").selected());
        assertEquals(1, table(report, "llm_usage").selected());
        assertEquals(1, table(report, "kb_task_log").selected());
        assertEquals(1, table(report, "kb_ingest_task").selected());
        assertEquals(1, report.attachmentFiles());
        assertEquals(9L, report.attachmentBytes());

        assertEquals(3, count(database, "chat_messages", "session_id = 1"));
        assertEquals(2, count(database, "chat_sessions", "1 = 1"));
        assertEquals(1, count(database, "chat_tool_traces", "trace_id = 100"));
        assertEquals(1, count(database, "chat_attachments", "attachment_id = 500"));
    }

    private RetentionSettings settings() {
        return new RetentionSettings(false, 365, 180, 180, 2, 30, 10, 0L,
                attachmentRoot.toString());
    }

    private static RetentionService service(RetentionStore store, RetentionSettings settings) {
        return new RetentionService(store, settings, new SimpleMeterRegistry());
    }

    private static RetentionService.TableReport table(
            RetentionService.RetentionReport report, String table) {
        return report.tables().stream()
                .filter(row -> row.table().equals(table))
                .findFirst()
                .orElseThrow(() -> new AssertionError("报告缺少表 " + table));
    }

    private static void assertOrder(List<String> deletes, String... tables) {
        int previous = -1;
        for (String table : tables) {
            int index = deletes.indexOf(table);
            assertTrue(index >= 0, "未删除 " + table + "：" + deletes);
            assertTrue(index > previous, table + " 顺序错误：" + deletes);
            previous = index;
        }
    }

    private static void insertSession(Connection c, long id, LocalDateTime createdAt)
            throws SQLException {
        exec(c,
                "INSERT INTO chat_sessions (session_id, user_id, title, created_at,"
                        + " updated_at, context_summary, context_turns)"
                        + " VALUES (?, 1, ?, ?, ?, '摘要', 3)",
                id, "会话-" + id, createdAt, createdAt);
    }

    private static void insertMessage(
            Connection c, long id, long sessionId, LocalDateTime createdAt) throws SQLException {
        exec(c,
                "INSERT INTO chat_messages (message_id, session_id, role, content, created_at)"
                        + " VALUES (?, ?, 'user', '内容', ?)",
                id, sessionId, createdAt);
    }

    private static void insertTrace(
            Connection c, long id, long messageId, long sessionId, LocalDateTime createdAt)
            throws SQLException {
        exec(c,
                "INSERT INTO chat_tool_traces (trace_id, message_id, session_id, tool_name,"
                        + " created_at) VALUES (?, ?, ?, 'payroll_calc', ?)",
                id, messageId, sessionId, createdAt);
    }

    private static void insertFeedback(
            Connection c, long id, long messageId, long sessionId, LocalDateTime createdAt)
            throws SQLException {
        exec(c,
                "INSERT INTO chat_feedback (feedback_id, message_id, session_id, user_id,"
                        + " rating, created_at) VALUES (?, ?, ?, 1, 'UP', ?)",
                id, messageId, sessionId, createdAt);
    }

    private static void insertProposal(
            Connection c, String id, long sessionId, long messageId, String status,
            LocalDateTime createdAt) throws SQLException {
        exec(c,
                "INSERT INTO chat_action_proposals (proposal_id, user_id, session_id,"
                        + " message_id, tool_name, summary, status, created_at)"
                        + " VALUES (?, 1, ?, ?, 'payroll_calc', '摘要', ?, ?)",
                id, sessionId, messageId, status, createdAt);
    }

    private static void insertAttachment(
            Connection c, long id, long sessionId, Long messageId, String storageKey,
            long sizeBytes, LocalDateTime createdAt) throws SQLException {
        exec(c,
                "INSERT INTO chat_attachments (attachment_id, session_id, user_id, message_id,"
                        + " name, ext, size_bytes, storage_key, status, created_at)"
                        + " VALUES (?, ?, 1, ?, 'a.txt', 'txt', ?, ?, 'READY', ?)",
                id, sessionId, messageId, sizeBytes, storageKey, createdAt);
    }

    private static void insertUsage(Connection c, long id, LocalDateTime occurredAt)
            throws SQLException {
        exec(c, "INSERT INTO llm_usage (usage_id, occurred_at) VALUES (?, ?)", id, occurredAt);
    }

    private static void insertKbTask(
            Connection c, long id, String status, LocalDateTime createdAt) throws SQLException {
        exec(c,
                "INSERT INTO kb_ingest_task (task_id, series_id, task_type, task_status,"
                        + " idempotency_key, operator_id, created_at)"
                        + " VALUES (?, 1, 'INGEST', ?, ?, 1, ?)",
                id, status, "idem-" + id, createdAt);
    }

    private static void insertKbLog(Connection c, long id, long taskId, LocalDateTime createdAt)
            throws SQLException {
        exec(c,
                "INSERT INTO kb_task_log (log_id, task_id, stage, created_at)"
                        + " VALUES (?, ?, 'PARSE', ?)",
                id, taskId, createdAt);
    }

    private static void insertTeamRun(
            Connection c, long id, long sessionId, long messageId, LocalDateTime createdAt,
            LocalDateTime deletedAt) throws SQLException {
        exec(c,
                "INSERT INTO agent_team_runs (run_id, plan_id, session_id, message_id, user_id,"
                        + " run_mode, plan_json, status, created_at, deleted_at)"
                        + " VALUES (?, ?, ?, ?, 1, 'SHADOW', '{}', 'CLOSED', ?, ?)",
                id, "plan-" + id, sessionId, messageId, createdAt, deletedAt);
    }

    private static void exec(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                Object value = params[i];
                if (value == null) {
                    statement.setObject(i + 1, null);
                } else if (value instanceof LocalDateTime time) {
                    statement.setTimestamp(i + 1, Timestamp.valueOf(time));
                } else {
                    statement.setObject(i + 1, value);
                }
            }
            statement.executeUpdate();
        }
    }

    private static int count(PayrollDatabase database, String table, String where)
            throws SQLException {
        try (Connection c = database.connection();
                Statement statement = c.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT COUNT(*) FROM " + table + " WHERE " + where)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static String status(PayrollDatabase database, String proposalId) throws SQLException {
        try (Connection c = database.connection();
                PreparedStatement statement =
                        c.prepareStatement(
                                "SELECT status FROM chat_action_proposals WHERE proposal_id = ?")) {
            statement.setString(1, proposalId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static final class RecordingStore extends RetentionStore {
        private final List<String> deletes = new ArrayList<>();
        private final List<Integer> sessionBatches = new ArrayList<>();

        private RecordingStore(PayrollDatabase database) {
            super(database);
        }

        @Override
        public int deleteByIds(String table, String pk, List<Long> ids) {
            deletes.add(table);
            return super.deleteByIds(table, pk, ids);
        }

        @Override
        public int deleteProposals(List<String> ids) {
            deletes.add("chat_action_proposals");
            return super.deleteProposals(ids);
        }

        @Override
        public int deleteAttachments(List<Long> ids) {
            deletes.add("chat_attachments");
            return super.deleteAttachments(ids);
        }

        @Override
        public int deleteSessions(List<Long> ids) {
            deletes.add("chat_sessions");
            sessionBatches.add(ids.size());
            return super.deleteSessions(ids);
        }

        @Override
        public int deleteTeamRuns(List<Long> ids) {
            deletes.add("agent_team_runs");
            return super.deleteTeamRuns(ids);
        }
    }

    private static final class FailingStore extends RetentionStore {
        private final List<String> calls = new ArrayList<>();

        private FailingStore(PayrollDatabase database) {
            super(database);
        }

        @Override
        public int deleteByIds(String table, String pk, List<Long> ids) {
            calls.add(table);
            if ("chat_messages".equals(table)) {
                throw new IllegalStateException("注入 DELETE 失败");
            }
            return super.deleteByIds(table, pk, ids);
        }

        private long callsFor(String table) {
            return calls.stream().filter(table::equals).count();
        }
    }
}
