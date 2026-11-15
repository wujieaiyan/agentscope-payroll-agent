package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.Citation;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KbCitationCollectorTest {

    @Test
    @DisplayName("解析 kb_search 响应并按首次命中顺序重新编号")
    void parsesHits() {
        KbCitationCollector collector = new KbCitationCollector();
        collector.record(
                """
                {"status":"OK","data":{"query":"年假","results":[
                  {"series_id":7,"series_name":"人事制度","hits":[
                    {"chunk_id":101,"document_id":11,"document_name":"员工手册.pdf",
                     "page_no":5,"title_path":"第三章 > 休假","score":0.82,
                     "point_id":"K-ANNUAL","routes":["keyword","vector"],
                     "content":"员工年假按工龄计算。"},
                    {"chunk_id":102,"document_id":11,"document_name":"员工手册.pdf",
                     "page_no":6,"title_path":"第三章 > 休假","score":0.7,
                     "routes":["keyword"],"content":"调休需提前申请。"}]},
                  {"series_id":8,"series_name":"薪酬政策","hits":[
                    {"chunk_id":201,"document_id":21,"document_name":"薪酬细则.pdf",
                     "page_no":2,"title_path":"第一章","score":0.9,
                     "routes":["vector"],"content":"月薪按月发放。"}]}
                ]}}
                """);

        List<Citation> citations = collector.citations();
        assertEquals(3, citations.size());
        Citation first = citations.get(0);
        assertEquals(1, first.index());
        assertEquals("K-ANNUAL", first.pointId());
        assertEquals(7L, first.seriesId());
        assertEquals("人事制度", first.seriesName());
        assertEquals(11L, first.documentId());
        assertEquals(101L, first.chunkId());
        assertEquals(5, first.pageNo());
        assertEquals("第三章 > 休假", first.titlePath());
        assertEquals(0.82, first.score(), 0.0001);
        assertEquals(List.of("keyword", "vector"), first.routes());
        assertEquals("员工年假按工龄计算。", first.snippet());
        assertEquals(3, citations.get(2).index());
    }

    @Test
    @DisplayName("同一分块重复命中合并召回路径并取最高分")
    void mergesDuplicateChunks() {
        KbCitationCollector collector = new KbCitationCollector();
        collector.record(
                """
                {"status":"OK","data":{"results":[{"series_id":1,"series_name":"制度","hits":[
                  {"chunk_id":9,"document_id":3,"document_name":"a.pdf","score":0.4,
                   "routes":["keyword"],"content":"第一条"},
                  {"chunk_id":9,"document_id":3,"document_name":"a.pdf","score":0.75,
                   "routes":["vector"],"content":""}
                ]}]}}
                """);

        List<Citation> citations = collector.citations();
        assertEquals(1, citations.size());
        assertEquals(0.75, citations.get(0).score(), 0.0001);
        assertEquals(List.of("keyword", "vector"), citations.get(0).routes());
        assertEquals("第一条", citations.get(0).snippet());
    }

    @Test
    @DisplayName("非 OK、空响应与损坏 JSON 不产生引用")
    void ignoresFailures() {
        KbCitationCollector collector = new KbCitationCollector();
        collector.record(null);
        collector.record("");
        collector.record("not-json");
        collector.record(
                """
                {"status":"DENIED","data":{"results":[{"series_id":1,"hits":[
                  {"chunk_id":1,"document_id":1,"content":"x"}]}]}}
                """);
        assertTrue(collector.citations().isEmpty());
    }

    @Test
    @DisplayName("最多保留 8 条引用，清空后可重新采集")
    void capsAndClears() {
        KbCitationCollector collector = new KbCitationCollector();
        StringBuilder hits = new StringBuilder();
        for (int index = 1; index <= 12; index++) {
            if (index > 1) hits.append(',');
            hits.append(
                    """
                    {"chunk_id":%d,"document_id":1,"document_name":"a.pdf",
                     "score":0.5,"content":"片段%d"}
                    """
                            .formatted(index, index));
        }
        collector.record(
                "{\"status\":\"OK\",\"data\":{\"results\":[{\"series_id\":1,"
                        + "\"series_name\":\"制度\",\"hits\":["
                        + hits
                        + "]}]}}");

        assertEquals(KbCitationCollector.MAX_CITATIONS, collector.citations().size());
        collector.clear();
        assertTrue(collector.citations().isEmpty());
    }

    @Test
    @DisplayName("片段清洗控制字符、折叠空白并截断")
    void cleansSnippet() {
        KbCitationCollector collector = new KbCitationCollector();
        String content = "\u0000第一行\n\n  第二行\t" + "长".repeat(300);
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("chunk_id", 5);
        hit.put("document_id", 1);
        hit.put("document_name", "a.pdf");
        hit.put("score", 0.1);
        hit.put("content", content);
        collector.record(
                JsonSupport.toJson(
                        Map.of(
                                "status",
                                "OK",
                                "data",
                                Map.of(
                                        "results",
                                        List.of(
                                                Map.of(
                                                        "series_id",
                                                        1,
                                                        "series_name",
                                                        "制度",
                                                        "hits",
                                                        List.of(hit)))))));

        Citation citation = collector.citations().get(0);
        assertTrue(citation.snippet().startsWith("第一行 第二行"));
        assertTrue(citation.snippet().endsWith("…"));
        assertTrue(citation.snippet().length() <= 241);
    }

    @Test
    @DisplayName("采集查询词、检索时间与文档版本快照")
    void capturesProvenance() {
        KbCitationCollector collector = new KbCitationCollector();
        String content = "员工年假按工龄计算。" + "补充说明".repeat(400);
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("chunk_id", 31);
        hit.put("chunk_seq", 4);
        hit.put("document_id", 9);
        hit.put("document_name", "员工手册.pdf");
        hit.put("document_version", 17L);
        hit.put("page_no", 5);
        hit.put("title_path", "第三章 > 休假");
        hit.put("score", 0.9);
        hit.put("routes", List.of("vector"));
        hit.put("content", content);
        collector.record(
                JsonSupport.toJson(
                        Map.of(
                                "status",
                                "OK",
                                "data",
                                Map.of(
                                        "results",
                                        List.of(
                                                Map.of(
                                                        "series_id",
                                                        3,
                                                        "series_name",
                                                        "人事制度",
                                                        "hits",
                                                        List.of(hit)))))),
                "  年假  怎么算  ");

        Citation citation = collector.citations().get(0);
        assertEquals("年假 怎么算", citation.query());
        assertTrue(!citation.retrievedAt().isBlank());
        assertEquals(4, citation.chunkSeq());
        assertEquals(17L, citation.documentVersion());
        assertTrue(citation.contentSnapshot().length() > citation.snippet().length());
        assertTrue(citation.contentSnapshot().length() <= 2001);
        assertEquals(false, citation.lowConfidence());
    }

    @Test
    @DisplayName("问题与来源毫无字面重叠时标记低置信")
    void flagsUnrelatedQuery() {
        KbCitationCollector collector = new KbCitationCollector();
        collector.record(
                """
                {"status":"OK","data":{"results":[{"series_id":1,"series_name":"人事制度","hits":[
                  {"chunk_id":7,"document_id":1,"document_name":"员工手册.pdf",
                   "page_no":3,"title_path":"第三章 > 休假",
                   "score":0.31,"content":"员工年假按工龄计算。"}]}]}}
                """,
                "Kafka 消息丢失怎么排查");

        Citation citation = collector.citations().get(0);
        assertTrue(citation.lowConfidence());
        assertEquals("Kafka 消息丢失怎么排查", citation.query());
    }

    @Test
    @DisplayName("关闭护栏后不再标记低置信")
    void guardCanBeDisabled() {
        KbCitationCollector collector = new KbCitationCollector();
        collector.record(
                """
                {"status":"OK","data":{"results":[{"series_id":1,"series_name":"人事制度","hits":[
                  {"chunk_id":7,"document_id":1,"document_name":"员工手册.pdf",
                   "content":"员工年假按工龄计算。"}]}]}}
                """,
                "Kafka 消息丢失怎么排查");
        try {
            KbCitationCollector.setLowConfidenceGuard(false);
            assertEquals(false, collector.citations().get(0).lowConfidence());
        } finally {
            KbCitationCollector.setLowConfidenceGuard(true);
        }
    }

    @Test
    @DisplayName("同一分块号出现在不同知识库时分别保留")
    void keepsChunksFromDifferentLibraries() {
        KbCitationCollector collector = new KbCitationCollector();
        collector.record(
                """
                {"status":"OK","data":{"results":[
                  {"series_id":1,"series_name":"制度A","hits":[
                    {"chunk_id":5,"document_id":1,"document_name":"a.pdf","content":"甲"}]},
                  {"series_id":2,"series_name":"制度B","hits":[
                    {"chunk_id":5,"document_id":2,"document_name":"b.pdf","content":"乙"}]}
                ]}}
                """);

        List<Citation> citations = collector.citations();
        assertEquals(2, citations.size());
        assertEquals(1L, citations.get(0).seriesId());
        assertEquals(2L, citations.get(1).seriesId());
    }

    @Test
    @DisplayName("旧消息 JSON 缺少溯源字段时使用默认值")
    void readsLegacyJson() {
        Citation citation =
                JsonSupport.fromJson(
                        """
                        {"index":1,"point_id":"K1","series_id":1,"series_name":"制度",
                         "document_id":2,"document_name":"a.pdf","chunk_id":3,"page_no":4,
                         "title_path":"第一章","score":0.5,"routes":["keyword"],
                         "snippet":"历史片段"}
                        """,
                        Citation.class);

        assertEquals("历史片段", citation.snippet());
        assertEquals("", citation.query());
        assertEquals("", citation.retrievedAt());
        assertEquals(null, citation.chunkSeq());
        assertEquals(null, citation.documentVersion());
        assertEquals("", citation.contentSnapshot());
        assertEquals(false, citation.lowConfidence());
    }
}
