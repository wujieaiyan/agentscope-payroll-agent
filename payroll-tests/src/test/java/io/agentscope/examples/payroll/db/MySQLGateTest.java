package io.agentscope.examples.payroll.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.examples.payroll.db.mapper.fixture.DepartmentProbeInsert;
import io.agentscope.examples.payroll.db.mapper.fixture.DepartmentProbeMapper;
import io.agentscope.examples.payroll.db.mapper.fixture.LeaseProbeMapper;
import io.agentscope.examples.payroll.db.support.PayrollMappers;
import io.agentscope.examples.payroll.domain.AuditEntry;
import io.agentscope.examples.payroll.domain.AuditQuery;
import io.agentscope.examples.payroll.usage.LlmUsageQuery;
import io.agentscope.examples.payroll.usage.LlmUsageRecord;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * P0-10 MySQL 闸门（规格 §5.2）：在真实 MySQL 上跑「建表自检 → 审计写入 → 分页查询 →
 * 约束异常类型 → D6 主键回填/upsert 分支」，把 §5.0.2 与 §5.0.4 的待验项落成证据。
 *
 * <p>默认跳过，避免 CI / 离线构建依赖数据库；运行方式：
 * {@code mvn -o -B -pl payroll-tests -am test -Dtest=MySQLGateTest -Dpayroll.gate.mysql=true}。
 * 连接参数取 {@code PAYROLL_DB_URL/USER/PASSWORD}，缺省与 {@code application-mysql.yml}
 * 的本地开发默认值一致。探针行（审计、用量、租约、部门）在 finally 里按唯一令牌删除。
 */
class MySQLGateTest {

    private static final String DEFAULT_URL =
            "jdbc:mysql://127.0.0.1:3306/payroll?useUnicode=true&characterEncoding=UTF-8"
                    + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&rewriteBatchedStatements=true";
    private static final String LEASE_XML = "classpath:constraint-probe/LeaseProbeMapper.xml";
    private static final String DEPARTMENT_XML =
            "classpath:constraint-probe/DepartmentProbeMapper.xml";

    @Test
    @DisplayName("MySQL：建表自检、审计写读、分页、异常类型与 D6 回填")
    void gate() throws Exception {
        assumeTrue(
                "true".equalsIgnoreCase(setting("payroll.gate.mysql", "PAYROLL_GATE_MYSQL")),
                "未开启 MySQL 闸门：-Dpayroll.gate.mysql=true");

        PayrollDatabase database =
                PayrollDatabase.open(
                        setting("payroll.gate.db.url", "PAYROLL_DB_URL", DEFAULT_URL),
                        setting("payroll.gate.db.user", "PAYROLL_DB_USER", "payroll"),
                        setting("payroll.gate.db.password", "PAYROLL_DB_PASSWORD", "payroll"),
                        4);
        String token = "mysql-gate-" + UUID.randomUUID();
        String leaseJob = token + "-lease";
        String department = token + "-dept";
        try {
            assertTrue(database.isMysql(), "闸门必须连 MySQL，实际：" + database.url());

            PayrollMappers mappers = PayrollMappers.withProbes(database, LEASE_XML, DEPARTMENT_XML);
            long auditId = auditWriteAndPage(mappers, token);
            long usageId = usageWriteAndRead(mappers, token);
            verifyExceptionMapping(mappers, leaseJob);
            verifyUpsertGeneratedKeys(mappers, department);

            System.out.println(
                    "[MYSQL-GATE] auditId="
                            + auditId
                            + " usageId="
                            + usageId
                            + " url="
                            + database.url());
        } finally {
            deleteAuditRows(database, token);
            deleteUsageRows(database, token);
            database.close();
        }
    }

    private static long auditWriteAndPage(PayrollMappers mappers, String token) {
        H2AuditStore store = mappers.auditStore();
        long first = store.insert(audit(token, "first"));
        store.insert(audit(token, "second"));
        store.insert(audit(token, "third"));
        assertTrue(first > 0, "MySQL 未回填 audit_log 自增主键");

        AuditQuery filter = new AuditQuery(null, null, null, null, null, token, null, null, 1, 2);
        List<AuditEntry> page = store.find(filter);
        assertEquals(2, page.size(), "LIMIT/OFFSET 分页未按 size 截断");
        assertEquals(3, store.count(filter), "count 与写入行数不一致");
        assertTrue(
                page.stream().allMatch(entry -> token.equals(entry.resourceId())),
                "分页结果混入了非本次写入的行");
        return first;
    }

    private static long usageWriteAndRead(PayrollMappers mappers, String token) {
        H2LlmUsageStore store = mappers.llmUsageStore();
        long id = store.insert(usage(token));
        assertTrue(id > 0, "MySQL 未回填 llm_usage 自增主键");
        List<LlmUsageRecord> rows =
                store.find(
                        LlmUsageQuery.lastHours(
                                1, LocalDateTime.now().plusMinutes(5), null, null, null, token),
                        10);
        assertTrue(
                rows.stream().anyMatch(record -> token.equals(record.replyId())),
                "写入的用量行未按用户查回");
        return id;
    }

    private static void verifyExceptionMapping(PayrollMappers mappers, String leaseJob)
            throws Exception {
        LeaseProbeMapper lease = mappers.mapper(LeaseProbeMapper.class);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        lease.insert(leaseJob, "gate", now, now.plusMinutes(5));
        try {
            DataIntegrityViolationException exception =
                    assertThrows(
                            DuplicateKeyException.class,
                            () -> lease.insert(leaseJob, "gate", now, now.plusMinutes(5)),
                            "MySQL 重复键未翻成 DuplicateKeyException");
            assertInstanceOf(DataIntegrityViolationException.class, exception);
            System.out.println(
                    "[MYSQL-GATE] duplicateKey=" + exception.getClass().getName());
        } finally {
            lease.delete(leaseJob);
        }
    }

    private static void verifyUpsertGeneratedKeys(PayrollMappers mappers, String department) {
        DepartmentProbeMapper probe = mappers.mapper(DepartmentProbeMapper.class);
        try {
            DepartmentProbeInsert insert = new DepartmentProbeInsert(department, "gate-insert");
            assertEquals(1, probe.upsertDepartment(insert));
            assertTrue(insert.getId() > 0, "MySQL INSERT 分支未回填自增主键");

            DepartmentProbeInsert update = new DepartmentProbeInsert(department, "gate-update");
            int affected = probe.upsertDepartment(update);
            assertTrue(affected >= 1, "ON DUPLICATE KEY UPDATE 未生效");
            System.out.println(
                    "[MYSQL-GATE] upsert insertBranchId="
                            + insert.getId()
                            + " updateBranchRows="
                            + affected
                            + " updateBranchKey="
                            + update.getId());
        } finally {
            probe.deleteDepartment(department);
        }
    }

    private static void deleteAuditRows(PayrollDatabase database, String token) {
        deleteByColumn(database, "DELETE FROM audit_log WHERE resource_id = ?", token);
    }

    private static void deleteUsageRows(PayrollDatabase database, String token) {
        deleteByColumn(database, "DELETE FROM llm_usage WHERE reply_id = ?", token);
    }

    private static void deleteByColumn(PayrollDatabase database, String sql, String value) {
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("[MYSQL-GATE] 清理探针行失败：" + e.getMessage());
        }
    }

    private static String setting(String property, String env) {
        String value = System.getProperty(property);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        String fromEnv = System.getenv(env);
        return fromEnv == null || fromEnv.isBlank() ? null : fromEnv.trim();
    }

    private static String setting(String property, String env, String fallback) {
        String value = setting(property, env);
        return value == null ? fallback : value;
    }

    private static AuditEntry audit(String token, String message) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        return new AuditEntry(
                0L,
                now,
                7L,
                "gate",
                "SELECT",
                "mysql_gate",
                token,
                null,
                null,
                null,
                "127.0.0.1",
                "junit",
                AuditEntry.SUCCESS,
                200,
                "mysql-gate-" + message,
                token,
                null,
                AuditEntry.WRITE_OK);
    }

    private static LlmUsageRecord usage(String token) {
        return new LlmUsageRecord(
                0L,
                LocalDateTime.now().withNano(0),
                token,
                "gate-session",
                "control",
                "闸门",
                "gate",
                "gate.step",
                1L,
                "gate-model",
                "gate-provider",
                "控制面",
                4,
                2,
                0,
                6,
                42L,
                true,
                null,
                token);
    }
}