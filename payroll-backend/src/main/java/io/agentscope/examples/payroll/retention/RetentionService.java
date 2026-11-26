package io.agentscope.examples.payroll.retention;

import io.agentscope.examples.payroll.jobs.JobLeases;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 聊天与知识库保留任务：按「先子后父」批删，孤儿兜底，附件文件先删后删行。
 *
 * <p>每张表独立循环；单表连续失败 2 轮（跨日）后跳过并计数，避免同一批反复重选；
 * 全局预算 {@code retention-max-minutes} 耗尽立即结束整轮并记录未完成表。
 */
public final class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private static final int CHAT_BATCH = 500;
    private static final int SESSION_BATCH = 200;
    private static final int TRACE_BATCH = 1000;
    private static final int FEEDBACK_BATCH = 1000;
    private static final int PROPOSAL_BATCH = 500;
    private static final int ATTACHMENT_BATCH = 200;
    private static final int USAGE_BATCH = 1000;
    private static final int TASK_LOG_BATCH = 1000;
    private static final int TASK_BATCH = 500;
    private static final int TEAM_BATCH = 200;
    private static final long ATTACHMENT_FILE_BUDGET_MILLIS = 5_000L;

    public record TableReport(
            String table,
            long selected,
            long deleted,
            int failedBatches,
            boolean skipped,
            String note) {}

    public record RetentionReport(
            boolean dryRun,
            boolean completed,
            long elapsedMillis,
            List<TableReport> tables,
            List<String> unfinished,
            long attachmentFiles,
            long attachmentBytes) {}

    private final RetentionStore store;
    private final RetentionSettings settings;
    private final MeterRegistry registry;
    private final Path attachmentRoot;
    private final Map<String, Integer> consecutiveFailures = new LinkedHashMap<>();

    public RetentionService(
            RetentionStore store, RetentionSettings settings, MeterRegistry registry) {
        this.store = store;
        this.settings = settings;
        this.registry = registry;
        this.attachmentRoot =
                Path.of(settings.attachmentDir()).toAbsolutePath().normalize();
    }

    public RetentionSettings settings() {
        return settings;
    }

    /** 启动统计：只读，不删除；同样用于 dry-run 的 cron。 */
    public RetentionReport statsOnly() {
        return run(true, null);
    }

    /** 持租约的启动统计：每张表前续租，租约被接管立即停止，避免两节点同时全表扫描。 */
    public RetentionReport statsOnly(JobLeases.Lease lease) {
        return run(true, lease);
    }

    public RetentionReport run(boolean dryRun, JobLeases.Lease lease) {
        long startedAt = System.nanoTime();
        long deadline = startedAt + TimeUnit.MINUTES.toNanos(settings.maxMinutes());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime chatCutoff = cutoff(now, settings.chatDays());
        LocalDateTime usageCutoff = cutoff(now, settings.usageDays());
        LocalDateTime kbCutoff = cutoff(now, settings.kbDays());
        LocalDateTime orphanCutoff = chatCutoff == null ? null : chatCutoff.minusDays(1);

        List<TableReport> reports = new ArrayList<>();
        List<String> unfinished = new ArrayList<>();
        Budget budget = new Budget(deadline);
        boolean completed = true;
        long attachmentFiles = 0;
        long attachmentBytes = 0;

        if (dryRun) {
            List<String> skippedStats = new ArrayList<>();
            if (settings.chatDays() > 0) {
                statsStep(lease, skippedStats, "chat_tool_traces",
                        () -> reports.add(stat("chat_tool_traces", "trace_id", "created_at", chatCutoff)));
                statsStep(lease, skippedStats, "chat_feedback",
                        () -> reports.add(stat("chat_feedback", "feedback_id", "created_at", chatCutoff)));
                statsStep(lease, skippedStats, "chat_action_proposals",
                        () -> reports.add(proposalStat(chatCutoff)));
                long[] attachmentTotals = {0L, 0L};
                statsStep(lease, skippedStats, "chat_attachments",
                        () -> {
                            RetentionStore.AttachmentStat attachmentStat =
                                    timed(
                                            "chat_attachments",
                                            () -> store.attachmentStat(chatCutoff));
                            attachmentTotals[0] = attachmentStat.files();
                            attachmentTotals[1] = attachmentStat.bytes();
                            reports.add(
                                    new TableReport(
                                            "chat_attachments",
                                            attachmentStat.rows(),
                                            0,
                                            0,
                                            false,
                                            "文件数="
                                                    + attachmentStat.files()
                                                    + "，字节="
                                                    + attachmentStat.bytes()));
                        });
                attachmentFiles = attachmentTotals[0];
                attachmentBytes = attachmentTotals[1];
                statsStep(lease, skippedStats, "chat_messages",
                        () -> reports.add(stat("chat_messages", "message_id", "created_at", chatCutoff)));
                statsStep(lease, skippedStats, "chat_sessions",
                        () -> reports.add(stat("chat_sessions", "session_id", "created_at", chatCutoff)));
            }
            if (settings.usageDays() > 0) {
                statsStep(lease, skippedStats, "llm_usage",
                        () -> reports.add(stat("llm_usage", "usage_id", "occurred_at", usageCutoff)));
            }
            if (settings.kbDays() > 0) {
                statsStep(lease, skippedStats, "kb_task_log",
                        () -> reports.add(stat("kb_task_log", "log_id", "created_at", kbCutoff)));
                statsStep(lease, skippedStats, "kb_ingest_task",
                        () -> reports.add(stat("kb_ingest_task", "task_id", "created_at", kbCutoff)));
            }
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            log.info("[保留任务] dry-run 统计完成，耗时 {}ms：{}", elapsed, summary(reports));
            return new RetentionReport(
                    true, skippedStats.isEmpty(), elapsed, List.copyOf(reports),
                    List.copyOf(skippedStats), attachmentFiles, attachmentBytes);
        }

        Map<String, Long> selectedByTable = new LinkedHashMap<>();
        Map<String, Long> deletedByTable = new LinkedHashMap<>();

        if (settings.chatDays() > 0) {
            TableOutcome traces =
                    purgeSimple(
                            "chat_tool_traces", "trace_id", "created_at", chatCutoff,
                            TRACE_BATCH, lease, budget);
            add(reports, traces);
            selectedByTable.put("chat_tool_traces", traces.selected());
            deletedByTable.put("chat_tool_traces", traces.deleted());
            if (!traces.finished()) {
                unfinished.add("chat_tool_traces");
            }

            TableOutcome feedback =
                    purgeSimple(
                            "chat_feedback", "feedback_id", "created_at", chatCutoff,
                            FEEDBACK_BATCH, lease, budget);
            add(reports, feedback);
            selectedByTable.put("chat_feedback", feedback.selected());
            deletedByTable.put("chat_feedback", feedback.deleted());
            if (!feedback.finished()) {
                unfinished.add("chat_feedback");
            }

            TableOutcome proposals =
                    purgeProposals(chatCutoff, lease, budget);
            add(reports, proposals);
            selectedByTable.put("chat_action_proposals", proposals.selected());
            deletedByTable.put("chat_action_proposals", proposals.deleted());
            if (!proposals.finished()) {
                unfinished.add("chat_action_proposals");
            }
            // 先删已终态提案，再把过期 PENDING 置 EXPIRED：本轮刚过期的留下一次删除，
            // 避免过期动作在同一轮内既改状态又删行，无从审计。
            int expired = store.expirePendingProposals(chatCutoff);
            if (expired > 0) {
                log.info("[保留任务] 过期 PENDING 提案置 EXPIRED {} 条，下一轮删除", expired);
            }

            AttachmentOutcome mainAttachments =
                    purgeAttachments(chatCutoff, false, lease, budget);
            add(reports, mainAttachments.report());
            selectedByTable.put("chat_attachments", mainAttachments.report().selected());
            deletedByTable.put("chat_attachments", mainAttachments.report().deleted());
            attachmentFiles += mainAttachments.files();
            attachmentBytes += mainAttachments.bytes();
            if (!mainAttachments.report().finished()) {
                unfinished.add("chat_attachments");
            }

            TableOutcome messages =
                    purgeSimple(
                            "chat_messages", "message_id", "created_at", chatCutoff,
                            CHAT_BATCH, lease, budget);
            add(reports, messages);
            selectedByTable.put("chat_messages", messages.selected());
            deletedByTable.put("chat_messages", messages.deleted());
            if (!messages.finished()) {
                unfinished.add("chat_messages");
            }

            TableOutcome sessions =
                    purgeSessions(chatCutoff, lease, budget);
            add(reports, sessions);
            selectedByTable.put("chat_sessions", sessions.selected());
            deletedByTable.put("chat_sessions", sessions.deleted());
            if (!sessions.finished()) {
                unfinished.add("chat_sessions");
            }

            if (orphanCutoff != null) {
                TableOutcome orphanTraces =
                        purgeOrphanLong(
                                "chat_tool_traces", "trace_id", "message_id", orphanCutoff,
                                TRACE_BATCH, lease, budget);
                add(reports, orphanTraces);
                selectedByTable.merge(
                        "chat_tool_traces", orphanTraces.selected(), Long::sum);
                deletedByTable.merge(
                        "chat_tool_traces", orphanTraces.deleted(), Long::sum);
                TableOutcome orphanFeedback =
                        purgeOrphanLong(
                                "chat_feedback", "feedback_id", "message_id", orphanCutoff,
                                FEEDBACK_BATCH, lease, budget);
                add(reports, orphanFeedback);
                selectedByTable.merge(
                        "chat_feedback", orphanFeedback.selected(), Long::sum);
                deletedByTable.merge(
                        "chat_feedback", orphanFeedback.deleted(), Long::sum);
                TableOutcome orphanProposals =
                        purgeOrphanProposals(orphanCutoff, lease, budget);
                add(reports, orphanProposals);
                selectedByTable.merge(
                        "chat_action_proposals", orphanProposals.selected(), Long::sum);
                deletedByTable.merge(
                        "chat_action_proposals", orphanProposals.deleted(), Long::sum);
                AttachmentOutcome orphanAttachments =
                        purgeAttachments(orphanCutoff, true, lease, budget);
                add(reports, orphanAttachments.report());
                selectedByTable.merge(
                        "chat_attachments", orphanAttachments.report().selected(), Long::sum);
                deletedByTable.merge(
                        "chat_attachments", orphanAttachments.report().deleted(), Long::sum);
                attachmentFiles += orphanAttachments.files();
                attachmentBytes += orphanAttachments.bytes();
                TableOutcome orphanTeamRuns =
                        purgeTeamRuns(orphanCutoff, lease, budget);
                add(reports, orphanTeamRuns);
                selectedByTable.merge(
                        "agent_team_runs", orphanTeamRuns.selected(), Long::sum);
                deletedByTable.merge(
                        "agent_team_runs", orphanTeamRuns.deleted(), Long::sum);
                TableOutcome orphanDirs =
                        purgeAttachmentDirectories(orphanCutoff, lease, budget);
                add(reports, orphanDirs);
            }
        }

        if (settings.usageDays() > 0) {
            TableOutcome usage =
                    purgeSimple(
                            "llm_usage", "usage_id", "occurred_at", usageCutoff,
                            USAGE_BATCH, lease, budget);
            add(reports, usage);
            selectedByTable.put("llm_usage", usage.selected());
            deletedByTable.put("llm_usage", usage.deleted());
            if (!usage.finished()) {
                unfinished.add("llm_usage");
            }
        }

        if (settings.kbDays() > 0) {
            TableOutcome logs =
                    purgeSimple(
                            "kb_task_log", "log_id", "created_at", kbCutoff,
                            TASK_LOG_BATCH, lease, budget);
            add(reports, logs);
            selectedByTable.put("kb_task_log", logs.selected());
            deletedByTable.put("kb_task_log", logs.deleted());
            if (!logs.finished()) {
                unfinished.add("kb_task_log");
            }
            if (orphanCutoff != null) {
                TableOutcome orphanLogs =
                        purgeOrphanTaskLogs(orphanCutoff, lease, budget);
                add(reports, orphanLogs);
                selectedByTable.merge("kb_task_log", orphanLogs.selected(), Long::sum);
                deletedByTable.merge("kb_task_log", orphanLogs.deleted(), Long::sum);
            }
            TableOutcome tasks = purgeTasks(kbCutoff, lease, budget);
            add(reports, tasks);
            selectedByTable.put("kb_ingest_task", tasks.selected());
            deletedByTable.put("kb_ingest_task", tasks.deleted());
            if (!tasks.finished()) {
                unfinished.add("kb_ingest_task");
            }
        }

        selectedByTable.forEach(
                (table, value) -> counter("payroll.retention.rows", table, "selected")
                        .increment(value));
        deletedByTable.forEach(
                (table, value) -> counter("payroll.retention.rows", table, "deleted")
                        .increment(value));

        completed = unfinished.isEmpty();
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        log.info(
                "[保留任务] 完成={}，耗时 {}ms，附件文件={}，字节={}，未完成={}：{}",
                completed,
                elapsed,
                attachmentFiles,
                attachmentBytes,
                unfinished,
                summary(reports));
        return new RetentionReport(
                false,
                completed,
                elapsed,
                List.copyOf(reports),
                List.copyOf(unfinished),
                attachmentFiles,
                attachmentBytes);
    }

    private TableOutcome purgeSimple(
            String table,
            String pk,
            String timeColumn,
            LocalDateTime cutoff,
            int batch,
            JobLeases.Lease lease,
            Budget budget) {
        TableOutcome outcome = new TableOutcome(table);
        while (true) {
            if (!beforeBatch(table, outcome, lease, budget)) {
                break;
            }
            long timer = System.nanoTime();
            List<Long> ids = store.selectExpiredIds(table, pk, timeColumn, cutoff, batch);
            if (ids.isEmpty()) {
                outcome.finished = true;
                break;
            }
            outcome.selected += ids.size();
            try {
                int deleted = store.deleteByIds(table, pk, ids);
                if (deleted == 0) {
                    outcome.failedBatches++;
                    outcome.finished = markFailure(outcome);
                    break;
                }
                outcome.deleted += deleted;
                consecutiveFailures.remove(table);
                record(table, timer, deleted);
            } catch (RuntimeException e) {
                outcome.failedBatches++;
                outcome.note = e.getMessage();
                outcome.finished = markFailure(outcome);
                break;
            }
            outcome.note = "批次 " + ids.size() + " 行";
        }
        return outcome;
    }

    private TableOutcome purgeProposals(
            LocalDateTime cutoff, JobLeases.Lease lease, Budget budget) {
        String table = "chat_action_proposals";
        TableOutcome outcome = new TableOutcome(table);
        while (true) {
            if (!beforeBatch(table, outcome, lease, budget)) {
                break;
            }
            List<String> ids = store.selectExpiredProposalIds(cutoff, PROPOSAL_BATCH);
            if (ids.isEmpty()) {
                outcome.finished = true;
                break;
            }
            outcome.selected += ids.size();
            try {
                int deleted = store.deleteProposals(ids);
                if (deleted == 0) {
                    outcome.failedBatches++;
                    outcome.finished = markFailure(outcome);
                    break;
                }
                outcome.deleted += deleted;
                consecutiveFailures.remove(table);
                counter("payroll.retention.rows", table, "deleted").increment(deleted);
            } catch (RuntimeException e) {
                outcome.failedBatches++;
                outcome.note = e.getMessage();
                outcome.finished = markFailure(outcome);
                break;
            }
        }
        return outcome;
    }

    private TableOutcome purgeOrphanProposals(
            LocalDateTime cutoff, JobLeases.Lease lease, Budget budget) {
        String table = "chat_action_proposals";
        TableOutcome outcome = new TableOutcome(table);
        while (true) {
            if (!beforeBatch(table, outcome, lease, budget)) {
                break;
            }
            List<String> ids = store.selectOrphanProposalIds(cutoff, PROPOSAL_BATCH);
            if (ids.isEmpty()) {
                outcome.finished = true;
                break;
            }
            outcome.selected += ids.size();
            try {
                int deleted = store.deleteProposals(ids);
                if (deleted == 0) {
                    outcome.failedBatches++;
                    outcome.finished = markFailure(outcome);
                    break;
                }
                outcome.deleted += deleted;
                consecutiveFailures.remove(table);
            } catch (RuntimeException e) {
                outcome.failedBatches++;
                outcome.note = e.getMessage();
                outcome.finished = markFailure(outcome);
                break;
            }
        }
        return outcome;
    }

    private TableOutcome purgeOrphanLong(
            String table,
            String pk,
            String messageColumn,
            LocalDateTime cutoff,
            int batch,
            JobLeases.Lease lease,
            Budget budget) {
        TableOutcome outcome = new TableOutcome(table);
        while (true) {
            if (!beforeBatch(table, outcome, lease, budget)) {
                break;
            }
            List<Long> ids =
                    messageColumn.equals("message_id")
                            ? selectOrphanIds(table, cutoff, batch)
                            : List.of();
            if (ids.isEmpty()) {
                outcome.finished = true;
                break;
            }
            outcome.selected += ids.size();
            try {
                int deleted = store.deleteByIds(table, pk, ids);
                if (deleted == 0) {
                    outcome.failedBatches++;
                    outcome.finished = markFailure(outcome);
                    break;
                }
                outcome.deleted += deleted;
                consecutiveFailures.remove(table + ".orphan");
            } catch (RuntimeException e) {
                outcome.failedBatches++;
                outcome.note = e.getMessage();
                outcome.finished = markFailure(outcome);
                break;
            }
        }
        return outcome;
    }

    private List<Long> selectOrphanIds(String table, LocalDateTime cutoff, int batch) {
        if ("chat_tool_traces".equals(table)) {
            return store.selectOrphanTraces(cutoff, batch);
        }
        if ("chat_feedback".equals(table)) {
            return store.selectOrphanFeedback(cutoff, batch);
        }
        throw new IllegalArgumentException("未知的孤儿表：" + table);
    }

    private TableOutcome purgeOrphanTaskLogs(
            LocalDateTime cutoff, JobLeases.Lease lease, Budget budget) {
        String table = "kb_task_log";
        TableOutcome outcome = new TableOutcome(table);
        while (true) {
            if (!beforeBatch(table, outcome, lease, budget)) {
                break;
            }
            List<Long> ids = store.selectOrphanTaskLogs(cutoff, TASK_LOG_BATCH);
            if (ids.isEmpty()) {
                outcome.finished = true;
                break;
            }
            outcome.selected += ids.size();
            try {
                int deleted = store.deleteByIds(table, "log_id", ids);
                if (deleted == 0) {
                    outcome.failedBatches++;
                    outcome.finished = markFailure(outcome);
                    break;
                }
                outcome.deleted += deleted;
                consecutiveFailures.remove(table + ".orphan");
            } catch (RuntimeException e) {
                outcome.failedBatches++;
                outcome.note = e.getMessage();
                outcome.finished = markFailure(outcome);
                break;
            }
        }
        return outcome;
    }

    private TableOutcome purgeTeamRuns(
            LocalDateTime cutoff, JobLeases.Lease lease, Budget budget) {
        String table = "agent_team_runs";
        TableOutcome outcome = new TableOutcome(table);
        while (true) {
            if (!beforeBatch(table, outcome, lease, budget)) {
                break;
            }
            List<Long> ids = store.selectArchivedTeamRuns(cutoff, TEAM_BATCH);
            if (ids.isEmpty()) {
                outcome.finished = true;
                break;
            }
            outcome.selected += ids.size();
            try {
                int deleted = store.deleteTeamRuns(ids);
                if (deleted == 0) {
                    outcome.failedBatches++;
                    outcome.finished = markFailure(outcome);
                    break;
                }
                outcome.deleted += deleted;
                consecutiveFailures.remove(table + ".orphan");
            } catch (RuntimeException e) {
                outcome.failedBatches++;
                outcome.note = e.getMessage();
                outcome.finished = markFailure(outcome);
                break;
            }
        }
        return outcome;
    }

    private TableOutcome purgeSessions(
            LocalDateTime cutoff, JobLeases.Lease lease, Budget budget) {
        String table = "chat_sessions";
        TableOutcome outcome = new TableOutcome(table);
        while (true) {
            if (!beforeBatch(table, outcome, lease, budget)) {
                break;
            }
            List<Long> ids = store.selectExpiredEmptySessions(cutoff, SESSION_BATCH);
            if (ids.isEmpty()) {
                outcome.finished = true;
                break;
            }
            outcome.selected += ids.size();
            try {
                int deleted = store.deleteSessions(ids);
                if (deleted == 0) {
                    outcome.failedBatches++;
                    outcome.finished = markFailure(outcome);
                    break;
                }
                outcome.deleted += deleted;
                consecutiveFailures.remove(table);
            } catch (RuntimeException e) {
                outcome.failedBatches++;
                outcome.note = e.getMessage();
                outcome.finished = markFailure(outcome);
                break;
            }
        }
        return outcome;
    }

    private TableOutcome purgeTasks(
            LocalDateTime cutoff, JobLeases.Lease lease, Budget budget) {
        String table = "kb_ingest_task";
        TableOutcome outcome = new TableOutcome(table);
        for (String status : RetentionStore.TASK_TERMINAL_STATUSES) {
            while (true) {
                if (!beforeBatch(table, outcome, lease, budget)) {
                    return outcome;
                }
                List<Long> ids = store.selectExpiredTasks(status, cutoff, TASK_BATCH);
                if (ids.isEmpty()) {
                    break;
                }
                outcome.selected += ids.size();
                try {
                    int deleted = store.deleteByIds(table, "task_id", ids);
                    if (deleted == 0) {
                        outcome.failedBatches++;
                        outcome.finished = markFailure(outcome);
                        return outcome;
                    }
                    outcome.deleted += deleted;
                    consecutiveFailures.remove(table + "." + status);
                } catch (RuntimeException e) {
                    outcome.failedBatches++;
                    outcome.note = e.getMessage();
                    outcome.finished = markFailure(outcome);
                    return outcome;
                }
            }
        }
        outcome.finished = true;
        return outcome;
    }

    private AttachmentOutcome purgeAttachments(
            LocalDateTime cutoff, boolean orphanOnly, JobLeases.Lease lease, Budget budget) {
        String table = "chat_attachments";
        TableOutcome outcome = new TableOutcome(table);
        long files = 0;
        long bytes = 0;
        while (true) {
            if (!beforeBatch(table, outcome, lease, budget)) {
                break;
            }
            List<RetentionStore.AttachmentRow> rows =
                    orphanOnly
                            ? store.selectOrphanAttachments(cutoff, ATTACHMENT_BATCH)
                            : store.selectExpiredAttachments(cutoff, ATTACHMENT_BATCH);
            if (rows.isEmpty()) {
                outcome.finished = true;
                break;
            }
            outcome.selected += rows.size();
            long fileBatchStart = System.nanoTime();
            List<Long> deletedIds = new ArrayList<>();
            boolean fileFailure = false;
            for (RetentionStore.AttachmentRow row : rows) {
                if (System.nanoTime() - fileBatchStart
                        > TimeUnit.MILLISECONDS.toNanos(ATTACHMENT_FILE_BUDGET_MILLIS)) {
                    break;
                }
                if (!deleteAttachmentFile(row.storageKey())) {
                    fileFailure = true;
                    break;
                }
                deletedIds.add(row.id());
                files++;
            }
            if (deletedIds.isEmpty()) {
                outcome.failedBatches++;
                outcome.note = fileFailure ? "附件文件删除失败，保留行待下轮" : "文件批预算耗尽";
                outcome.finished = markFailure(outcome);
                break;
            }
            try {
                int deleted = store.deleteAttachments(deletedIds);
                outcome.deleted += deleted;
            } catch (RuntimeException e) {
                outcome.failedBatches++;
                outcome.note = e.getMessage();
                outcome.finished = markFailure(outcome);
                break;
            }
            if (fileFailure) {
                outcome.failedBatches++;
                outcome.note = "部分附件文件删除失败，保留行待下轮";
                outcome.finished = markFailure(outcome);
                break;
            }
        }
        return new AttachmentOutcome(outcome, files, bytes);
    }

    private TableOutcome purgeAttachmentDirectories(
            LocalDateTime cutoff, JobLeases.Lease lease, Budget budget) {
        String table = "chat_attachment_dirs";
        TableOutcome outcome = new TableOutcome(table);
        if (!Files.isDirectory(attachmentRoot)) {
            outcome.finished = true;
            return outcome;
        }
        long cutoffMillis =
                cutoff.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        try (Stream<Path> sessionDirs = Files.list(attachmentRoot)) {
            for (Path sessionDir : sessionDirs.toList()) {
                if (!Files.isDirectory(sessionDir) || !isNumeric(sessionDir.getFileName().toString())) {
                    continue;
                }
                if (budget.exhausted() || (lease != null && !lease.renew())) {
                    outcome.finished = false;
                    return outcome;
                }
                try (Stream<Path> childDirs = Files.list(sessionDir)) {
                    for (Path child : childDirs.toList()) {
                        String name = child.getFileName().toString();
                        if (!Files.isDirectory(child) || !isUuid(name)) {
                            continue;
                        }
                        long mtime;
                        try {
                            mtime = Files.getLastModifiedTime(child).toMillis();
                        } catch (IOException e) {
                            continue;
                        }
                        if (mtime >= cutoffMillis) {
                            continue;
                        }
                        String key = sessionDir.getFileName() + "/" + name;
                        if (store.attachmentKeyExists(key)) {
                            continue;
                        }
                        outcome.selected++;
                        if (deleteRecursively(child)) {
                            outcome.deleted++;
                        } else {
                            outcome.failedBatches++;
                            outcome.note = "目录删除失败：" + key;
                        }
                    }
                }
            }
            outcome.finished = true;
            return outcome;
        } catch (IOException e) {
            outcome.failedBatches++;
            outcome.note = e.getMessage();
            outcome.finished = markFailure(outcome);
            return outcome;
        }
    }

    private boolean beforeBatch(
            String table, TableOutcome outcome, JobLeases.Lease lease, Budget budget) {
        if (budget.exhausted()) {
            outcome.note = "时间预算耗尽";
            return false;
        }
        if (Integer.valueOf(2).equals(consecutiveFailures.get(table))) {
            outcome.skipped = true;
            outcome.finished = true;
            counter("payroll.retention.skipped", table, "skipped").increment();
            log.warn("[保留任务] {} 连续失败 2 轮，本轮跳过", table);
            return false;
        }
        if (lease != null && !lease.renew()) {
            outcome.note = "租约丢失，任务中止";
            return false;
        }
        pauseBetweenBatches();
        return true;
    }

    /** 成功删除返回 true；首次失败结束当前表，连续第 2 次失败标记跳过。 */
    private boolean markFailure(TableOutcome outcome) {
        int failures =
                consecutiveFailures.merge(outcome.table, 1, Integer::sum);
        if (failures >= 2) {
            outcome.skipped = true;
            counter("payroll.retention.skipped", outcome.table, "skipped").increment();
            log.warn(
                    "[保留任务] {} 连续失败 {} 轮，跳过到下一天：{}",
                    outcome.table,
                    failures,
                    outcome.note);
        } else {
            log.warn(
                    "[保留任务] {} 第 {} 轮失败，结束本表：{}",
                    outcome.table,
                    failures,
                    outcome.note);
        }
        return true;
    }

    private boolean deleteAttachmentFile(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return true;
        }
        Path target = attachmentRoot.resolve(storageKey).normalize();
        if (!target.startsWith(attachmentRoot)) {
            log.warn("[保留任务] 附件路径越界，跳过：{}", storageKey);
            return false;
        }
        if (!Files.exists(target)) {
            return true;
        }
        return deleteRecursively(target);
    }

    private static boolean deleteRecursively(Path path) {
        try {
            if (!Files.exists(path)) {
                return true;
            }
            try (Stream<Path> walk = Files.walk(path)) {
                List<Path> items = walk.sorted(java.util.Comparator.reverseOrder()).toList();
                for (Path item : items) {
                    Files.deleteIfExists(item);
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private TableReport stat(
            String table, String pk, String timeColumn, LocalDateTime cutoff) {
        RetentionStore.TableStat stat = timed(table, () -> store.tableStat(table, pk, timeColumn, cutoff));
        return new TableReport(
                table,
                stat.rows(),
                0,
                0,
                false,
                "最早="
                        + stat.earliest()
                        + "，最晚="
                        + stat.latest()
                        + "，样例="
                        + stat.samples());
    }

    private TableReport proposalStat(LocalDateTime cutoff) {
        RetentionStore.TableStat stat = timed("chat_action_proposals", () -> store.proposalStat(cutoff));
        return new TableReport(
                "chat_action_proposals",
                stat.rows(),
                0,
                0,
                false,
                "最早=" + stat.earliest() + "，最晚=" + stat.latest());
    }

    private void record(String table, long startedAt, int deleted) {
        Timer.builder("payroll.retention.duration")
                .description("保留任务单表批耗")
                .tag("table", table)
                .register(registry)
                .record(Duration.ofNanos(System.nanoTime() - startedAt));
        counter("payroll.retention.rows", table, "deleted").increment(deleted);
    }

    private Counter counter(String name, String table, String action) {
        return Counter.builder(name)
                .description("保留任务行数")
                .tag("table", table)
                .tag("action", action)
                .register(registry);
    }

    private void pauseBetweenBatches() {
        if (settings.batchPauseMillis() <= 0) {
            return;
        }
        try {
            Thread.sleep(settings.batchPauseMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void add(List<TableReport> reports, TableOutcome outcome) {
        reports.add(outcome.toReport());
    }

    /** dry-run 的每张表前置续租；租约被接管或统计失败时记录未完成表并继续下一张。 */
    private void statsStep(
            JobLeases.Lease lease, List<String> skipped, String table, Runnable stat) {
        if (lease != null && !lease.renew()) {
            skipped.add(table);
            return;
        }
        try {
            stat.run();
        } catch (RuntimeException e) {
            skipped.add(table);
            log.warn("[保留任务] 统计 {} 失败：{}", table, e.getMessage());
        }
    }

    private static LocalDateTime cutoff(LocalDateTime now, int days) {
        return days <= 0 ? null : now.minusDays(days);
    }

    private static String summary(List<TableReport> reports) {
        StringBuilder builder = new StringBuilder();
        for (TableReport report : reports) {
            if (builder.length() > 0) {
                builder.append("；");
            }
            builder.append(report.table())
                    .append("=")
                    .append(report.deleted())
                    .append("/")
                    .append(report.selected());
            if (report.skipped()) {
                builder.append("(跳过)");
            }
        }
        return builder.toString();
    }

    private static boolean isNumeric(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUuid(String value) {
        return value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    private <T> T timed(String table, java.util.function.Supplier<T> supplier) {
        long startedAt = System.nanoTime();
        try {
            return supplier.get();
        } finally {
            Timer.builder("payroll.retention.duration")
                    .description("保留任务统计耗时")
                    .tag("table", table)
                    .register(registry)
                    .record(Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

    private static final class Budget {
        private final long deadline;

        private Budget(long deadline) {
            this.deadline = deadline;
        }

        private boolean exhausted() {
            return System.nanoTime() >= deadline;
        }
    }

    private static final class TableOutcome {
        private final String table;
        private long selected;
        private long deleted;
        private int failedBatches;
        private boolean skipped;
        private boolean finished;
        private String note = "";

        private TableOutcome(String table) {
            this.table = table;
        }

        private TableReport toReport() {
            return new TableReport(table, selected, deleted, failedBatches, skipped, note);
        }

        private long selected() {
            return selected;
        }

        private long deleted() {
            return deleted;
        }

        private boolean finished() {
            return finished;
        }
    }

    private record AttachmentOutcome(TableOutcome report, long files, long bytes) {}
}
