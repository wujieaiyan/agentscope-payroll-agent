package io.agentscope.examples.payroll.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LlmUsageRecorderTest {

    @AfterEach
    void tearDown() {
        LlmUsageRecorder.reset();
    }

    @Test
    @DisplayName("未装配存储时为空操作，配置后恢复正常写入")
    void noopBeforeConfigure() {
        LlmUsageRecorder.reset();
        LlmUsageRecorder.record(record("未配置"));

        CapturingStore store = new CapturingStore();
        LlmUsageRecorder.configure(store, false);
        LlmUsageRecorder.record(record("已配置"));

        assertEquals(1, store.rows.size());
        assertEquals("已配置", store.rows.get(0).replyId());
    }

    @Test
    @DisplayName("同步模式下立即落库，空记录被忽略")
    void syncWritesImmediately() {
        CapturingStore store = new CapturingStore();
        LlmUsageRecorder.configure(store, false);

        LlmUsageRecorder.record(null);
        LlmUsageRecorder.record(record("sync"));

        assertEquals(1, store.rows.size());
        assertEquals("sync", store.rows.get(0).replyId());
    }

    @Test
    @DisplayName("异步模式下最终落库")
    void asyncWritesEventually() throws Exception {
        CapturingStore store = new CapturingStore();
        LlmUsageRecorder.configure(store, true);

        LlmUsageRecorder.record(record("async"));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (store.rows.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1, store.rows.size());
        assertEquals("async", store.rows.get(0).replyId());
    }

    @Test
    @DisplayName("reset 后不再写入，写入异常不向上抛出")
    void resetAndFailuresAreSwallowed() {
        CapturingStore store = new CapturingStore();
        LlmUsageRecorder.configure(store, false);
        LlmUsageRecorder.reset();
        LlmUsageRecorder.record(record("after-reset"));
        assertTrue(store.rows.isEmpty());

        LlmUsageRecorder.configure(
                new LlmUsageStore() {
                    @Override
                    public long insert(LlmUsageRecord record) {
                        throw new IllegalStateException("db down");
                    }

                    @Override
                    public List<LlmUsageRecord> find(LlmUsageQuery query, int limit) {
                        return List.of();
                    }
                },
                false);
        LlmUsageRecorder.record(record("boom"));
    }

    private static LlmUsageRecord record(String replyId) {
        return new LlmUsageRecord(
                0L,
                LocalDateTime.now(),
                "7",
                "42",
                "control",
                "薪酬助手",
                "implementer",
                "dev.task",
                9L,
                "qwen2.5:3b",
                "ollama",
                "控制面",
                8,
                4,
                2,
                12,
                240,
                true,
                null,
                replyId);
    }

    private static final class CapturingStore implements LlmUsageStore {

        private final List<LlmUsageRecord> rows = new CopyOnWriteArrayList<>();

        @Override
        public long insert(LlmUsageRecord record) {
            rows.add(record);
            return rows.size();
        }

        @Override
        public List<LlmUsageRecord> find(LlmUsageQuery query, int limit) {
            return List.copyOf(rows);
        }
    }
}
