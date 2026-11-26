package io.agentscope.examples.payroll.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.JobLeaseStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 任务租约（规格 C5-1a）：单行 PK 互斥、接管与释放。
 *
 * <p>接管用「把 {@code expires_at} 直接改到过去」触发，与节点 {@code kill -9} 后等 TTL 到期走的是同一条
 * SQL 判定（{@code expires_at < CURRENT_TIMESTAMP}），只是不真等 120 秒。所有时间都取自数据库时钟：
 * 测试只改库里的行，Java 侧没有任何时间入参，节点 JVM 时钟偏 ±5 分钟也影响不到互斥窗口。
 */
class JobLeaseStoreTest {

    private static final String JOB = "test.job";
    private static final int TTL_SECONDS = 120;

    private PayrollDatabase database;
    private JobLeaseStore store;

    @BeforeEach
    void setUp() {
        database = PayrollDatabase.inMemory("job-lease-" + System.nanoTime());
        store = new JobLeaseStore(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("两个节点抢同一任务：只有一个成功，主键冲突不再抛异常")
    void onlyOneNodeAcquires() {
        assertTrue(store.tryAcquire(JOB, "node-a", TTL_SECONDS));
        assertFalse(store.tryAcquire(JOB, "node-b", TTL_SECONDS));

        JobLeaseStore.LeaseRow row = store.find(JOB).orElseThrow();
        assertEquals("node-a", row.owner());
    }

    @Test
    @DisplayName("被接管后续租影响 0 行，JobLeases 句柄立即标记丢失")
    void takeoverInvalidatesTheOriginalHolder() {
        JobLeases nodeA = new JobLeases(store, true, "node-a", TTL_SECONDS);
        JobLeases nodeB = new JobLeases(store, true, "node-b", TTL_SECONDS);
        JobLeases.Lease lease = nodeA.tryAcquire(JOB);
        assertTrue(lease.held());

        expire(JOB);
        assertTrue(nodeB.tryAcquire(JOB).held(), "过期的租约应被接管");
        assertEquals(
                nodeB.owner(),
                store.find(JOB).orElseThrow().owner(),
                "接管后 owner 应换成 nodeB（A=" + nodeA.owner() + "）");

        assertFalse(lease.renew(), "续租影响行数为 0 即已被接管");
        assertFalse(lease.held(), "续租失败后句柄必须标记丢失，调用方据此中止");

        int processed = 0;
        // 长任务的按批执行形态：每批开头续租，失败即停，避免接管后双写。
        while (lease.renew()) {
            processed++;
        }
        assertEquals(0, processed);
        assertEquals(nodeB.owner(), store.find(JOB).orElseThrow().owner());
        lease.release();
        assertEquals(
                nodeB.owner(), store.find(JOB).orElseThrow().owner(), "丢失租约后释放不得误删他人行");
    }

    @Test
    @DisplayName("主动释放立即可被接管：过期时间置为过去")
    void releaseMakesTheLeaseReusable() {
        assertTrue(store.tryAcquire(JOB, "node-a", TTL_SECONDS));
        store.release(JOB, "node-a");

        assertTrue(store.find(JOB).orElseThrow().expiresAt().isBefore(dbNow()));
        assertTrue(store.tryAcquire(JOB, "node-b", TTL_SECONDS));
        assertEquals("node-b", store.find(JOB).orElseThrow().owner());
    }

    @Test
    @DisplayName("TTL 与过期判定全部在 SQL 侧完成（节点时钟偏移不参与）")
    void expiryIsComputedByTheDatabaseClock() {
        assertTrue(store.tryAcquire(JOB, "node-a", TTL_SECONDS));

        assertEquals(TTL_SECONDS, ttlSeconds(JOB), "expires_at 必须由 DATEADD/TIMESTAMPADD 现算");
        assertTrue(store.find(JOB).orElseThrow().acquiredAt().isBefore(dbNow().plusSeconds(1)));
        // Java 只传 TTL 秒数，没有「当前时间」参数：任何本地时钟偏移都无法扩大或缩小互斥窗口。
        assertFalse(store.tryAcquire(JOB, "node-b", TTL_SECONDS));
    }

    @Test
    @DisplayName("双副本同时执行启动任务：持约节点执行一次，另一节点直接跳过")
    void runIfAcquiredExecutesOnceAcrossNodes() throws Exception {
        JobLeases nodeA = new JobLeases(store, true, "node-a", TTL_SECONDS);
        JobLeases nodeB = new JobLeases(store, true, "node-b", TTL_SECONDS);
        AtomicInteger aRuns = new AtomicInteger();
        AtomicInteger bRuns = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder =
                    executor.submit(
                            () ->
                                    nodeA.runIfAcquired(
                                            JOB,
                                            lease -> {
                                                aRuns.incrementAndGet();
                                                entered.countDown();
                                                await(finish);
                                            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS), "节点 A 应拿到租约并开始执行");

            assertFalse(
                    nodeB.runIfAcquired(JOB, lease -> bRuns.incrementAndGet()),
                    "租约未释放前节点 B 不得执行");
            assertEquals(0, bRuns.get());

            finish.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertEquals(1, aRuns.get());

            // 释放（runIfAcquired 的 finally）之后，下一轮可以由另一节点接手。
            assertTrue(nodeB.runIfAcquired(JOB, lease -> bRuns.incrementAndGet()));
            assertEquals(1, bRuns.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("lease-enabled=false 退化为本地执行且不写库")
    void disabledLeasesRunLocally() {
        JobLeases local = new JobLeases(store, false, "node-a", TTL_SECONDS);
        AtomicInteger runs = new AtomicInteger();

        assertTrue(local.runIfAcquired(JOB, lease -> runs.incrementAndGet()));
        assertEquals(1, runs.get());
        assertTrue(store.find(JOB).isEmpty(), "关闭租约后不应产生 job_lease 行");

        JobLeases.Lease lease = local.tryAcquire(JOB);
        assertTrue(lease.held());
        assertTrue(lease.renew());
    }

    private void expire(String jobName) {
        String sql =
                "UPDATE job_lease SET expires_at="
                        + database.dialect(
                                "DATEADD('SECOND', -5, CURRENT_TIMESTAMP)",
                                "TIMESTAMPADD(SECOND, -5, CURRENT_TIMESTAMP)")
                        + " WHERE job_name=?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, jobName);
            assertEquals(1, statement.executeUpdate());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int ttlSeconds(String jobName) {
        String sql =
                "SELECT "
                        + database.dialect(
                                "DATEDIFF('SECOND', acquired_at, expires_at)",
                                "TIMESTAMPDIFF(SECOND, acquired_at, expires_at)")
                        + " FROM job_lease WHERE job_name=?";
        try (Connection connection = database.connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, jobName);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "job_lease 中应有 " + jobName);
                return rows.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private java.time.LocalDateTime dbNow() {
        try (Connection connection = database.connection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT CURRENT_TIMESTAMP");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getObject(1, java.time.LocalDateTime.class);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
