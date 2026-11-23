package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import io.agentscope.examples.payroll.skill.JdbcSkillStore.Row;
import io.agentscope.examples.payroll.skill.SkillRuntimeReader.CatalogFingerprint;
import io.agentscope.examples.payroll.skill.SkillRuntimeReader.Runtime;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 跨实例目录失效轮询（§10）。
 *
 * <p>比对与埋点打桩读端口，好把「哪一轮变化、滞后多少」算准；最后一例换成真的 {@link JdbcSkillStore}
 * 加 {@link DbSkillRepository}，证明另一个实例的写入真能在下一轮被读到，而不是只证明了桩会换个字符串。
 * 「只刷目录不刷正文」归 {@code DbSkillRepositoryTest.refreshDropsCatalogOnly}，那里数得清回源批次。
 */
class SkillInvalidationPollerTest {

    private final Stub reader = new Stub();
    private final AtomicLong clock = new AtomicLong(0L);
    private final AtomicInteger refreshes = new AtomicInteger();

    private SimpleMeterRegistry registry;
    private SkillInvalidationPoller poller;

    @TempDir Path blobRoot;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        Metrics.globalRegistry.add(registry);
        // 间隔 0：不启线程，全部由测试自己推进，结论不随调度抖动。
        poller = newPoller(0L);
    }

    @AfterEach
    void tearDown() {
        poller.close();
        Metrics.globalRegistry.remove(registry);
    }

    private SkillInvalidationPoller newPoller(long pollMillis) {
        return new SkillInvalidationPoller(
                reader, refreshes::incrementAndGet, pollMillis, clock::get);
    }

    @Test
    @DisplayName("首轮只登记基线，不算一次失效")
    void firstPollSeedsBaseline() {
        assertFalse(poller.pollOnce());
        assertEquals(0, refreshes.get());
        assertEquals(0L, invalidations());
    }

    @Test
    @DisplayName("revision 没变化就不动缓存：轮询的常态是零失效")
    void unchangedRevisionDoesNotInvalidate() {
        poller.pollOnce();

        assertFalse(poller.pollOnce());
        assertFalse(poller.pollOnce());
        assertEquals(0, refreshes.get());
    }

    @Test
    @DisplayName("revision 变化触发一次失效，滞后按本节点与库的时钟差记录")
    void revisionChangeInvalidatesAndRecordsLag() {
        poller.pollOnce();

        reader.revision = "r2";
        reader.lastWriteMillis = 6_000L;
        clock.set(10_000L);

        assertTrue(poller.pollOnce());
        assertEquals(1, refreshes.get());
        assertEquals(1L, invalidations());
        Timer lag = lagTimer();
        assertEquals(1L, lag.count());
        assertEquals(4_000D, lag.max(TimeUnit.MILLISECONDS));

        assertFalse(poller.pollOnce(), "基线已推进，同一 revision 不再重复失效");
        assertEquals(1, refreshes.get());
    }

    @Test
    @DisplayName("库时钟超前于本节点时滞后截零，不给出无法解释的负数")
    void clockSkewIsClamped() {
        poller.pollOnce();

        reader.revision = "r2";
        reader.lastWriteMillis = 20_000L;
        clock.set(10_000L);

        assertTrue(poller.pollOnce());
        assertEquals(0D, lagTimer().max(TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("库不可用只记日志：基线不前进，恢复后补上这次失效")
    void pollFailureKeepsBaselineAndSurvives() {
        poller.pollOnce();

        reader.failure = new PayrollUnavailableException("连接超时");
        assertThrows(PayrollUnavailableException.class, () -> poller.pollOnce());
        poller.pollQuietly();
        assertEquals(0, refreshes.get());

        reader.failure = null;
        reader.revision = "r2";
        assertTrue(poller.pollOnce(), "失败轮次没推进基线，恢复的第一轮必须失效");
        assertEquals(1, refreshes.get());
    }

    @Test
    @DisplayName("间隔非正数不启线程，close 幂等")
    void nonPositiveIntervalStartsNoThread() throws Exception {
        long before = reader.fingerprintCalls.get();

        Thread.sleep(50L);

        assertEquals(before, reader.fingerprintCalls.get(), "关闭态不该有库查询");
        poller.close();
        poller.close();
    }

    @Test
    @DisplayName("间隔为正时调度线程自己会发现问题，不需要调用方推")
    void enabledPollerRunsByItself() throws Exception {
        SkillInvalidationPoller live = newPoller(20L);
        try {
            long deadline = System.currentTimeMillis() + 5_000L;
            while (reader.fingerprintCalls.get() == 0L && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            assertTrue(reader.fingerprintCalls.get() > 0L, "调度线程应在若干轮内跑起来");

            // 一直翻到线程真的读到两种取值为止：单次赋值可能正好赶上某一轮在飞，被当成基线吞掉。
            boolean flag = false;
            deadline = System.currentTimeMillis() + 5_000L;
            while (refreshes.get() == 0 && System.currentTimeMillis() < deadline) {
                reader.revision = (flag = !flag) ? "r-a" : "r-b";
                Thread.sleep(20L);
            }
            assertTrue(refreshes.get() > 0, "变化着的 revision 迟早会被某一轮看见");
        } finally {
            live.close();
        }
    }

    @Test
    @DisplayName("真库双腿：另一实例写入后，本实例下一轮轮询就读到新正文")
    void peerWriteReachesRealRepository() {
        PayrollDatabase database = PayrollDatabase.inMemory("poll-" + System.nanoTime());
        LocalDiskSkillBlobStore blobs =
                new LocalDiskSkillBlobStore(blobRoot.toString(), 1024 * 1024);
        JdbcSkillStore nodeA = new JdbcSkillStore(database, blobs, SkillFileSettings.defaults());
        JdbcSkillStore nodeB = new JdbcSkillStore(database, blobs, SkillFileSettings.defaults());
        nodeA.create("poll-alpha", "甲", body("旧版"), false);
        nodeA.create("poll-beta", "乙", body("备用"), false);

        DbSkillRepository repository = new DbSkillRepository(nodeB, 60_000L, clock::get);
        String before = repository.getSkill("poll-alpha").getSkillContent();

        nodeA.update("poll-alpha", "甲", body("过渡版"), false);
        assertEquals(
                before,
                repository.getSkill("poll-alpha").getSkillContent(),
                "TTL 未到且没轮询，本节点不该凭空看到新内容");

        try (SkillInvalidationPoller crossNode =
                new SkillInvalidationPoller(nodeB, repository::refresh, 0L, clock::get)) {
            assertFalse(crossNode.pollOnce(), "首轮只登记基线");

            nodeA.update("poll-alpha", "甲", body("更新版"), false);
            assertTrue(crossNode.pollOnce());
            String after = repository.getSkill("poll-alpha").getSkillContent();
            assertNotEquals(before, after);
            assertTrue(after.contains("更新版"), after);
            assertFalse(crossNode.pollOnce(), "同一 revision 不重复失效");
        }
    }

    /** 正文要过 §11 的质量门槛（≥200 字、有标题、含角色词），重复段只是把长度顶上去。 */
    private static String body(String marker) {
        return "## 角色\n" + marker + "\n\n## 规则\n" + "核对口径后按步骤输出发放结果。".repeat(16);
    }

    private long invalidations() {
        Counter counter = registry.find(SkillMetrics.INVALIDATIONS).tag("trigger", "poll").counter();
        return counter == null ? 0L : (long) counter.count();
    }

    private Timer lagTimer() {
        Timer timer = registry.find(SkillMetrics.INVALIDATION_LAG).timer();
        assertNotNull(timer, "滞后只由轮询腿记录，见 SkillMetrics#invalidationLag");
        return timer;
    }

    /** 手调指纹的桩：目录与正文对轮询无关，返回空即可。 */
    private static final class Stub implements SkillRuntimeReader {

        private volatile String revision = "r1";
        private volatile long lastWriteMillis;
        private volatile RuntimeException failure;
        private final AtomicInteger fingerprintCalls = new AtomicInteger();

        @Override
        public List<Row> catalog() {
            return List.of();
        }

        @Override
        public Optional<Row> find(String id) {
            return Optional.empty();
        }

        @Override
        public Map<String, Runtime> runtimeData(Collection<Row> rows) {
            return Map.of();
        }

        @Override
        public String location() {
            return "jdbc:h2:mem:skill-poll";
        }

        @Override
        public CatalogFingerprint fingerprint() {
            fingerprintCalls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return new CatalogFingerprint(revision, lastWriteMillis);
        }
    }
}
