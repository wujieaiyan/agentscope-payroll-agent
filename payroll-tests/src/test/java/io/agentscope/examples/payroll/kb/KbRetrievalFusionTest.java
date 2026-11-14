package io.agentscope.examples.payroll.kb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.kb.KbConfigModels.ConfigPayload;
import io.agentscope.examples.payroll.kb.KbConfigModels.RetrievalConfig;
import io.agentscope.examples.payroll.kb.KbDtos.RetrievalHit;
import io.agentscope.examples.payroll.kb.KbDtos.RetrievalResult;
import io.agentscope.examples.payroll.kb.KbDtos.RetrievalTestRequest;
import io.agentscope.examples.payroll.kb.KbEnums.ChunkLevel;
import io.agentscope.examples.payroll.kb.KbEnums.DocStatus;
import io.agentscope.examples.payroll.kb.KbEnums.IndexStrategy;
import io.agentscope.examples.payroll.kb.KbEnums.KbRole;
import io.agentscope.examples.payroll.kb.KbModels.Chunk;
import io.agentscope.examples.payroll.kb.KbModels.ConfigVersion;
import io.agentscope.examples.payroll.kb.KbModels.Document;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KbRetrievalFusionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long SERIES_ID = 1L;

    @Test
    @DisplayName("向量不可用时 BM25 与标题路仍能召回，并写入融合诊断")
    void bm25AndTitleRoutesRecallWithoutVector() {
        List<Chunk> chunks =
                List.of(
                        chunk(1, 41, 1, "员工累计工作满一年不满十年的，年休假五天。", "第三章/休假"),
                        chunk(2, 41, 2, "员工累计工作满十年不满二十年的，年休假十天。", "第三章/休假"));

        RetrievalResult result = search(chunks, List.of(document(41)), "年休假多少天", settings(3), 5);

        assertFalse(result.hits().isEmpty());
        assertTrue(result.hits().stream().anyMatch(hit -> hit.routes().contains("BM25")));
        assertTrue(result.hits().stream().anyMatch(hit -> hit.routes().contains("TITLE_PATH")));
        assertEquals(Boolean.TRUE, result.diagnostics().get("fusion"));
        assertEquals(60, result.diagnostics().get("rrf_k"));
        Map<?, ?> routes = (Map<?, ?>) result.diagnostics().get("routes");
        assertTrue(routes.containsKey("BM25"));
        assertTrue(routes.containsKey("TITLE_PATH"));
        Map<?, ?> bm25 = (Map<?, ?>) routes.get("BM25");
        assertTrue((Integer) bm25.get("candidates") > 0);
    }

    @Test
    @DisplayName("向量与 BM25 同时命中时融合为一个知识点，并保留各路名次")
    void vectorAndBm25HitsMergeIntoOnePoint() {
        List<Chunk> chunks =
                List.of(
                        chunk(50, 46, 1, "年假五天。", "第三章/休假", vectorJson(0)),
                        chunk(51, 46, 5, "婚庆福利申请流程。", "第四章/福利"));

        RetrievalResult result =
                search(
                        chunks,
                        List.of(document(46)),
                        "年假",
                        settings(3),
                        5,
                        vector(0));

        assertEquals(1, result.hits().size());
        RetrievalHit hit = result.hits().get(0);
        assertTrue(hit.routes().contains("VECTOR"));
        assertTrue(hit.routes().contains("BM25"));
        assertTrue(hit.routeRanks().containsKey("VECTOR"));
        assertTrue(hit.routeRanks().containsKey("BM25"));
        assertTrue(hit.vectorScore() > 0);
        assertTrue(hit.keywordScore() > 0);
    }

    @Test
    @DisplayName("同文档相同正文指纹合并为一个知识点")
    void duplicateContentMergesIntoOnePoint() {
        List<Chunk> chunks =
                List.of(
                        chunk(10, 42, 1, "婚假为三天。", "第四章/假期"),
                        chunk(11, 42, 5, "婚假为三天。", "第四章/假期"));

        RetrievalResult result = search(chunks, List.of(document(42)), "婚假", settings(3), 5);

        assertEquals(1, result.hits().size());
        assertEquals(2, result.hits().get(0).mergedChunks());
        assertTrue(result.hits().get(0).routes().contains("BM25"));
    }

    @Test
    @DisplayName("同标题相邻且正文重叠的块合并，正文保留两段")
    void adjacentBlocksMergeWithContent() {
        List<Chunk> chunks =
                List.of(
                        chunk(30, 44, 1, "年假五天", "第五章/年假"),
                        chunk(31, 44, 2, "年假五天可以分段安排", "第五章/年假"));

        RetrievalResult result = search(chunks, List.of(document(44)), "年假五天", settings(3), 5);

        assertEquals(1, result.hits().size());
        assertEquals(2, result.hits().get(0).mergedChunks());
        assertTrue(result.hits().get(0).content().contains("可以分段安排"));
    }

    @Test
    @DisplayName("单文档最多保留 max-per-document 条")
    void perDocumentCapLimitsHits() {
        List<Chunk> chunks =
                IntStream.range(0, 5)
                        .mapToObj(
                                index ->
                                        chunk(
                                                20 + index,
                                                43,
                                                index + 1,
                                                "年假条款" + (index + 1) + "：第" + (index + 1) + "项特殊规定。",
                                                "章节" + (char) ('A' + index)))
                        .toList();

        RetrievalResult result = search(chunks, List.of(document(43)), "年假", settings(3), 8);

        assertEquals(3, result.hits().size());
        assertTrue(result.hits().stream().allMatch(hit -> hit.documentId() == 43));
    }

    @Test
    @DisplayName("关闭融合时退回旧路径并标记 fusion=false")
    void legacyPathRemainsAvailable() {
        List<Chunk> chunks =
                List.of(chunk(40, 45, 1, "员工年休假五天。", "第三章/休假"));

        RetrievalResult result =
                search(
                        chunks,
                        List.of(document(45)),
                        "年休假",
                        new KbRetrievalSettings(
                                false,
                                "VECTOR,BM25,TITLE_PATH",
                                60,
                                1.0,
                                0.8,
                                0.5,
                                0.1,
                                0.1,
                                3,
                                3),
                        5);

        assertFalse(result.hits().isEmpty());
        assertEquals(Boolean.FALSE, result.diagnostics().get("fusion"));
    }

    private static KbRetrievalSettings settings(int maxPerDocument) {
        return new KbRetrievalSettings(
                true, "VECTOR,BM25,TITLE_PATH", 60, 1.0, 0.8, 0.5, 0.1, 0.1, maxPerDocument, 3);
    }

    private static RetrievalResult search(
            List<Chunk> chunks,
            List<Document> documents,
            String query,
            KbRetrievalSettings settings,
            int topK) {
        return search(chunks, documents, query, settings, topK, new float[64]);
    }

    private static RetrievalResult search(
            List<Chunk> chunks,
            List<Document> documents,
            String query,
            KbRetrievalSettings settings,
            int topK,
            float[] queryVector) {
        KbEmbeddingService embeddings =
                new KbEmbeddingService(MAPPER, "http://127.0.0.1:1", "test", 64) {
                    @Override
                    public Embedding embed(List<String> texts) {
                        return new Embedding(List.of(queryVector), "HASH", false);
                    }

                    @Override
                    public float[] fromJson(String json) {
                        if (json == null || json.isBlank()) {
                            return null;
                        }
                        try {
                            return MAPPER.readValue(json, float[].class);
                        } catch (Exception e) {
                            return null;
                        }
                    }
                };
        KbConfigService configService =
                new KbConfigService(null, null, null, null, MAPPER) {
                    @Override
                    public ConfigVersion requireActiveConfig(long seriesId) {
                        return new ConfigVersion(
                                1,
                                seriesId,
                                1,
                                IndexStrategy.HYBRID,
                                "{}",
                                "{}",
                                "{}",
                                null,
                                0,
                                Instant.now());
                    }

                    @Override
                    public ConfigPayload payload(ConfigVersion version) {
                        RetrievalConfig retrieval = KbConfigModels.defaultRetrieval();
                        retrieval.rerankEnabled = false;
                        retrieval.recallTopK = 20;
                        retrieval.rerankTopN = 5;
                        retrieval.similarityThreshold = 0.1;
                        ConfigPayload payload = new ConfigPayload();
                        payload.parse = KbConfigModels.defaultParse();
                        payload.chunk = KbConfigModels.defaultChunk();
                        payload.retrieval = retrieval;
                        return payload;
                    }
                };
        KbPermissionService permissions =
                new KbPermissionService(null, null) {
                    @Override
                    public KbRole requireRead(long seriesId, AuthUser user) {
                        return KbRole.READER;
                    }
                };
        KbChunkStore chunkStore =
                new KbChunkStore(null) {
                    @Override
                    public List<Chunk> findActiveBySeries(long seriesId) {
                        return chunks;
                    }

                    @Override
                    public List<Chunk> findByIds(Collection<Long> ids) {
                        return chunks.stream().filter(chunk -> ids.contains(chunk.id())).toList();
                    }
                };
        KbDocumentStore documentStore =
                new KbDocumentStore(null) {
                    @Override
                    public List<Document> findReadyBySeries(long seriesId) {
                        return documents;
                    }
                };
        KbElasticsearchService elasticsearch =
                new KbElasticsearchService(
                        MAPPER, embeddings, false, "http://127.0.0.1:1", "", "", "test-kb", 64);
        KbAnswerService answers =
                new KbAnswerService(
                        MAPPER, false, "http://127.0.0.1:1", "test", Duration.ofSeconds(1));
        KbRetrievalService service =
                new KbRetrievalService(
                        chunkStore,
                        documentStore,
                        configService,
                        permissions,
                        embeddings,
                        elasticsearch,
                        answers,
                        settings);
        return service.search(
                SERIES_ID,
                new RetrievalTestRequest(query, topK, null, false),
                AuthUser.system());
    }

    private static Chunk chunk(
            long id, long documentId, int seq, String content, String titlePath) {
        return chunk(id, documentId, seq, content, titlePath, null);
    }

    private static Chunk chunk(
            long id,
            long documentId,
            int seq,
            String content,
            String titlePath,
            String vector) {
        return new Chunk(
                id,
                SERIES_ID,
                documentId,
                null,
                ChunkLevel.SINGLE,
                seq,
                content,
                null,
                content.length(),
                null,
                null,
                1,
                null,
                titlePath,
                null,
                vector,
                "HASH",
                "ACTIVE",
                Instant.now());
    }

    private static float[] vector(int index) {
        float[] vector = new float[64];
        vector[index] = 1;
        return vector;
    }

    private static String vectorJson(int index) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < 64; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(i == index ? "1.0" : "0.0");
        }
        return builder.append(']').toString();
    }

    private static Document document(long id) {
        Instant now = Instant.now();
        return new Document(
                id,
                SERIES_ID,
                0L,
                "员工手册.md",
                "md",
                "text/markdown",
                1024L,
                "sha-" + id,
                "kb/" + id,
                3,
                1024L,
                DocStatus.READY,
                null,
                null,
                1,
                null,
                1L,
                1L,
                now,
                now,
                null);
    }
}
