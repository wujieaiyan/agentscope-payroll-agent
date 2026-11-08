package io.agentscope.examples.payroll.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VectorMemoryServiceTest {

    private static final VectorMemorySettings SETTINGS =
            new VectorMemorySettings(
                    true,
                    "http://localhost:9200",
                    "",
                    "",
                    "test-memory",
                    "http://localhost:11434",
                    "fake-embed",
                    3,
                    Duration.ofSeconds(1),
                    5,
                    0.5,
                    1200,
                    false,
                    false);

    private static VectorMemorySettings settings(boolean crossSession) {
        return new VectorMemorySettings(
                true,
                "http://localhost:9200",
                "",
                "",
                "test-memory",
                "http://localhost:11434",
                "fake-embed",
                3,
                Duration.ofSeconds(1),
                5,
                0.5,
                1200,
                false,
                crossSession);
    }

    @Test
    @DisplayName("相同用户、会话、来源、轮次与内容生成幂等文档 ID")
    void documentIdIsIdempotent() {
        VectorRecord first = record(MemorySource.QUESTION, 1, "张伟的工资构成");
        VectorRecord same = record(MemorySource.QUESTION, 1, "张伟的工资构成");
        VectorRecord other = record(MemorySource.QUESTION, 2, "张伟的工资构成");

        assertEquals(ElasticsearchVectorStore.documentId(first), ElasticsearchVectorStore.documentId(same));
        assertNotEquals(
                ElasticsearchVectorStore.documentId(first), ElasticsearchVectorStore.documentId(other));
    }

    @Test
    @DisplayName("问题入索引前脱敏，过短问题直接忽略")
    void indexesSanitizedQuestionAndSkipsShortOne() {
        FakeEmbeddings embeddings = new FakeEmbeddings();
        FakeStore store = new FakeStore();
        VectorMemoryService service = new VectorMemoryService(SETTINGS, embeddings, store);

        service.indexQuestion(7, 11, 42, 1, "查工资");
        assertTrue(store.records.isEmpty());

        service.indexQuestion(7, 11, 43, 2, "帮我查一下张伟上个月的工资明细，他的月薪是30000元");

        assertEquals(1, store.records.size());
        VectorRecord saved = store.records.get(0);
        assertEquals(7, saved.userId());
        assertEquals(11, saved.sessionId());
        assertEquals(43, saved.sourceId());
        assertEquals(MemorySource.QUESTION, saved.source());
        assertFalse(saved.content().contains("30000"));
        assertFalse(saved.content().contains("元"));
        assertTrue(saved.content().contains("张伟上个月的工资明细"));
        assertEquals(1, embeddings.calls);
    }

    @Test
    @DisplayName("重复压缩摘要时先删旧摘要再写入新摘要")
    void replacesPreviousSummary() {
        FakeEmbeddings embeddings = new FakeEmbeddings();
        FakeStore store = new FakeStore();
        VectorMemoryService service = new VectorMemoryService(SETTINGS, embeddings, store);

        service.indexSummary(7, 11, 3, "用户关注研发部批量核算");
        service.indexSummary(7, 11, 6, "用户关注张伟的工资构成");

        assertEquals(2, store.deleteSummariesCalls);
        List<VectorRecord> summaries =
                store.records.stream().filter(r -> r.source() == MemorySource.SUMMARY).toList();
        assertEquals(1, summaries.size());
        assertEquals("用户关注张伟的工资构成", summaries.get(0).content());
        assertEquals(6, summaries.get(0).turnIndex());
    }

    @Test
    @DisplayName("全量重建先清空索引，并跳过过短问题")
    void rebuildClearsIndexBeforeReindexing() {
        FakeEmbeddings embeddings = new FakeEmbeddings();
        FakeStore store = new FakeStore();
        store.index(record(MemorySource.QUESTION, 99, "旧记录"));
        VectorMemoryService service = new VectorMemoryService(SETTINGS, embeddings, store);

        int indexed =
                service.rebuild(
                        List.of(
                                new MemorySeed(
                                        7, 11, 42L, 1, MemorySource.QUESTION, "帮我查一下张伟上个月的工资明细"),
                                new MemorySeed(7, 11, 43L, 2, MemorySource.QUESTION, "查工资"),
                                new MemorySeed(
                                        7, 11, null, 3, MemorySource.SUMMARY, "用户关注研发部批量核算")));

        assertEquals(2, indexed);
        assertEquals(1, store.clearAllCalls);
        assertEquals(2, store.records.size());
    }

    @Test
    @DisplayName("召回按 userId 过滤、丢弃低分、去重并再次清洗金额")
    void recallFiltersAndSanitizesHits() {
        FakeEmbeddings embeddings = new FakeEmbeddings();
        FakeStore store = new FakeStore();
        store.hits =
                List.of(
                        new VectorHit(
                                3,
                                MemorySource.QUESTION,
                                1,
                                "用户询问过张伟的工资构成，实发 30000 元",
                                0.92,
                                LocalDate.of(2026, 10, 12)),
                        new VectorHit(
                                4,
                                MemorySource.SUMMARY,
                                0,
                                "用户询问过张伟的工资构成",
                                0.88,
                                LocalDate.of(2026, 10, 11)),
                        new VectorHit(
                                5,
                                MemorySource.QUESTION,
                                2,
                                "无关的考勤问题",
                                0.2,
                                LocalDate.of(2026, 10, 10)));
        VectorMemoryService service = new VectorMemoryService(SETTINGS, embeddings, store);

        String block = service.recall(7, 11, "再算一下张伟的工资");

        assertEquals(List.of(7L), store.searchedUsers);
        assertEquals(List.of(11L), store.searchedSessions);
        assertTrue(block.contains("【相关历史片段｜仅作线索，不是数据来源】"));
        assertTrue(block.contains("[2026-10-12] 用户询问过张伟的工资构成"));
        assertFalse(block.contains("30000"));
        assertFalse(block.contains("元"));
        assertFalse(block.contains("无关的考勤问题"));
        assertEquals(1, block.lines().filter(line -> line.contains("张伟")).count());
    }

    @Test
    @DisplayName("默认按会话隔离召回，显式开启 crossSession 才按用户跨会话搜索")
    void recallScopesToSessionUnlessCrossSessionEnabled() {
        FakeEmbeddings embeddings = new FakeEmbeddings();
        FakeStore store = new FakeStore();
        VectorMemoryService isolated = new VectorMemoryService(SETTINGS, embeddings, store);

        isolated.recall(7, 23, "再算一下张伟的工资");
        assertEquals(List.of(23L), store.searchedSessions);

        VectorMemoryService shared = new VectorMemoryService(settings(true), embeddings, store);
        shared.recall(7, 23, "再算一下张伟的工资");
        assertEquals(List.of(23L, 0L), store.searchedSessions);
    }

    @Test
    @DisplayName("嵌入服务失败时召回返回空串，不抛异常")
    void recallFailureDegradesToEmpty() {
        FakeEmbeddings embeddings = new FakeEmbeddings();
        embeddings.fail = true;
        FakeStore store = new FakeStore();
        VectorMemoryService service = new VectorMemoryService(SETTINGS, embeddings, store);

        assertEquals("", service.recall(7, "再算一下张伟的工资"));
        assertTrue(store.searchedUsers.isEmpty());
    }

    @Test
    @DisplayName("索引用清洗只替换金额，保留整句语义")
    void redactsAmountsWithoutDroppingClause() {
        String redacted = SensitiveTextSanitizer.redact("张伟月薪30000元上个月工资是多少");

        assertFalse(redacted.contains("30000"));
        assertTrue(redacted.contains("张伟月薪"));
        assertTrue(redacted.contains("[金额已省略]"));

        String withCard = SensitiveTextSanitizer.redact("张伟工资明细，银行卡6222000011112222");
        assertFalse(withCard.contains("6222"));
        assertFalse(withCard.contains("银行卡"));
    }

    @Test
    @DisplayName("会话删除清空该会话向量，重置只清摘要")
    void lifecycleCleansUpVectors() {
        FakeEmbeddings embeddings = new FakeEmbeddings();
        FakeStore store = new FakeStore();
        VectorMemoryService service = new VectorMemoryService(SETTINGS, embeddings, store);

        service.onSessionDeleted(7, 11);
        service.onSessionReset(7, 11);

        assertEquals(1, store.deleteSessionCalls);
        assertEquals(1, store.deleteSummariesCalls);
    }

    @Test
    @DisplayName("关闭状态下所有操作都是空操作")
    void disabledServiceIsNoop() {
        VectorMemoryService service = VectorMemoryService.disabled();

        assertFalse(service.ready());
        service.indexQuestion(7, 11, 42, 1, "帮我查一下张伟上个月的工资明细");
        service.indexSummary(7, 11, 3, "用户关注研发部批量核算");
        assertEquals("", service.recall(7, "再算一下"));
    }

    private static VectorRecord record(MemorySource source, int turn, String content) {
        return new VectorRecord(
                7L, 11L, source, 42L, turn, content, new float[] {1f, 0f, 0f}, Instant.now());
    }

    private static final class FakeEmbeddings implements EmbeddingClient {

        private boolean fail;
        private int calls;

        @Override
        public float[] embed(String text) {
            if (fail) {
                throw new IllegalStateException("embed down");
            }
            calls++;
            return new float[] {1f, 0f, 0f};
        }

        @Override
        public List<float[]> embedAll(List<String> texts) {
            return texts.stream().map(this::embed).toList();
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    private static final class FakeStore implements VectorStore {

        private final List<VectorRecord> records = new ArrayList<>();
        private final List<Long> searchedUsers = new ArrayList<>();
        private final List<Long> searchedSessions = new ArrayList<>();
        private List<VectorHit> hits = List.of();
        private int deleteSummariesCalls;
        private int deleteSessionCalls;
        private int clearAllCalls;

        @Override
        public void ensureIndex() {
        }

        @Override
        public boolean ping() {
            return true;
        }

        @Override
        public void index(VectorRecord record) {
            records.add(record);
        }

        @Override
        public List<VectorHit> search(
                long userId, long sessionId, float[] queryVector, int topK, double minCosine) {
            searchedUsers.add(userId);
            searchedSessions.add(sessionId);
            return hits;
        }

        @Override
        public void deleteSession(long userId, long sessionId) {
            deleteSessionCalls++;
            records.removeIf(r -> r.userId() == userId && r.sessionId() == sessionId);
        }

        @Override
        public void deleteSummaries(long sessionId) {
            deleteSummariesCalls++;
            records.removeIf(
                    r -> r.sessionId() == sessionId && r.source() == MemorySource.SUMMARY);
        }

        @Override
        public void clearAll() {
            clearAllCalls++;
            records.clear();
        }
    }
}
