package io.agentscope.examples.payroll.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.support.PayrollMappers;
import io.agentscope.examples.payroll.domain.AuditEntry;
import io.agentscope.examples.payroll.domain.AuditQuery;
import io.agentscope.examples.payroll.usage.LlmUsageQuery;
import io.agentscope.examples.payroll.usage.LlmUsageRecord;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P0-9 三项指标基线（规格 §5.2 / §5.7 门槛）：固定工作负载下量
 * Mapper 调用次数、{@code connection()} 获取次数与语句 P95，并断言
 * 「单语句 Mapper 方法 = 1 次调用 = 1 次连接」这一迁移中性前提。
 *
 * <p>数值输出到 surefire 报告；后续批次用同一命令复测，比较连接次数不得超过 1.2×、
 * P95 不得劣化 20%。基线是环境相关的，只用于同机前后对比，不跨机器引用。
 */
class P0MetricsBaselineTest {

    private static final int ROUNDS = 200;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 12, 0);

    @Test
    @DisplayName("试点仓储：调用次数、连接次数、语句 P95 基线")
    void recordsPilotBaseline() {
        PayrollDatabase database = PayrollDatabase.inMemory("metrics-baseline-" + UUID.randomUUID());
        try {
            PayrollMappers mappers = PayrollMappers.forDatabase(database);
            H2AuditStore auditStore = mappers.auditStore();
            H2LlmUsageStore usageStore = mappers.llmUsageStore();

            warmUp(auditStore, usageStore);
            DbMetrics.reset();
            long connectionsBefore = DbMetrics.connectionAcquiredCount();

            for (int i = 0; i < ROUNDS; i++) {
                auditStore.insert(audit("baseline-insert-" + i));
            }
            for (int i = 0; i < ROUNDS; i++) {
                auditStore.find(AuditQuery.firstPage(20));
            }
            for (int i = 0; i < ROUNDS; i++) {
                usageStore.insert(usage("baseline-reply-" + i));
            }
            for (int i = 0; i < ROUNDS; i++) {
                usageStore.find(LlmUsageQuery.lastHours(24, NOW.plusHours(1), null, null, null, null), 20);
            }

            long connections = DbMetrics.connectionAcquiredCount() - connectionsBefore;
            Map<String, long[]> perStatement = new LinkedHashMap<>();
            perStatement.put("AuditMapper.insert", stats("AuditMapper.insert"));
            perStatement.put("AuditMapper.find", stats("AuditMapper.find"));
            perStatement.put("LlmUsageMapper.insert", stats("LlmUsageMapper.insert"));
            perStatement.put("LlmUsageMapper.find", stats("LlmUsageMapper.find"));

            perStatement.forEach(
                    (id, stat) -> {
                        assertEquals(ROUNDS, stat[0], id + " 调用次数");
                        long p95Micros = stat[1] / 1_000;
                        assertTrue(p95Micros > 0, id + " 未记录到耗时样本");
                        assertTrue(p95Micros < 100_000, id + " P95 异常偏高：" + p95Micros + "us");
                    });

            long totalCalls = perStatement.values().stream().mapToLong(stat -> stat[0]).sum();
            assertEquals(totalCalls, connections, "单语句路径上每次 Mapper 调用应恰好取一次连接");

            System.out.println(
                    "[P0-METRICS] rounds="
                            + ROUNDS
                            + " mapperCalls="
                            + totalCalls
                            + " connections="
                            + connections
                            + " connectionsPerCall="
                            + String.format("%.3f", (double) connections / totalCalls));
            perStatement.forEach(
                    (id, stat) ->
                            System.out.println(
                                    "[P0-METRICS] "
                                            + id
                                            + " calls="
                                            + stat[0]
                                            + " p95us="
                                            + stat[1] / 1_000));
        } finally {
            database.close();
        }
    }

    /** 先跑一轮不计入基线，避免 JIT/预热把首次测量的 P95 抬高。 */
    private static void warmUp(H2AuditStore auditStore, H2LlmUsageStore usageStore) {
        for (int i = 0; i < 100; i++) {
            auditStore.insert(audit("warmup-insert-" + i));
            auditStore.find(AuditQuery.firstPage(20));
            usageStore.insert(usage("warmup-reply-" + i));
            usageStore.find(LlmUsageQuery.lastHours(24, NOW.plusHours(1), null, null, null, null), 20);
        }
    }

    /** {调用次数, P95 纳秒}。 */
    private static long[] stats(String statementId) {
        return new long[] {
            DbMetrics.mapperCallCount(statementId), DbMetrics.statementP95Nanos(statementId)
        };
    }

    private static AuditEntry audit(String resourceId) {
        return new AuditEntry(
                0L,
                NOW,
                7L,
                "王五",
                "TEST",
                "BASELINE",
                resourceId,
                null,
                null,
                null,
                "127.0.0.1",
                "junit",
                AuditEntry.SUCCESS,
                200,
                "基线",
                "trace-" + resourceId,
                null,
                AuditEntry.WRITE_OK);
    }

    private static LlmUsageRecord usage(String replyId) {
        return new LlmUsageRecord(
                0L,
                NOW,
                "7",
                "session",
                "control",
                "薪酬助手",
                "implementer",
                "dev.task",
                1L,
                "qwen2.5:3b",
                "ollama",
                "控制面",
                8,
                4,
                0,
                12,
                120L,
                true,
                null,
                replyId);
    }
}