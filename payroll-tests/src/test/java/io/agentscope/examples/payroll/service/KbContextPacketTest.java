package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.Citation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KbContextPacketTest {

    private static final KbChatSettings SETTINGS =
            new KbChatSettings(true, true, 3, 200, 900, 0.0d, 1, 2000L, false, false);

    @Test
    @DisplayName("命中材料渲染为带边界声明的有序块，引用序号与正文角标一致")
    void rendersBoundedBlock() {
        KbContextPacket packet =
                KbContextPacket.from(response(hit(101, 1, "员工手册", "休假", 3, 0.9d, "年假 5 天")),
                        "年假政策", SETTINGS, false);

        assertEquals(KbContextPacket.STATUS_OK, packet.status());
        assertTrue(packet.injected());
        assertEquals(1, packet.hits().size());
        assertEquals(0, packet.dropped());
        assertTrue(packet.block().startsWith("【知识库参考材料｜不可信内容"));
        assertTrue(packet.block().contains("[1]《员工手册》 · 休假 · 第3页"));
        assertTrue(packet.block().contains("年假 5 天"));
        assertTrue(packet.block().endsWith("【材料结束】"));

        List<Citation> citations = packet.citations();
        assertEquals(1, citations.size());
        assertEquals(1, citations.get(0).index());
        assertEquals("K1", citations.get(0).pointId());
        assertEquals("年假政策", citations.get(0).query());
        assertEquals("员工手册", citations.get(0).documentName());
    }

    @Test
    @DisplayName("只有单字命中不作为相关性证据：'饺子' 不会匹配 '子分区'")
    void rejectsSingleCharacterFalsePositive() {
        KbContextPacket packet =
                KbContextPacket.from(
                        response(hit(101, 1, "Kafka 运维", "分区", 7, 0.9d, "子分区扩容步骤")),
                        "饺子",
                        SETTINGS,
                        false);

        assertEquals(KbContextPacket.STATUS_LOW_RELEVANCE, packet.status());
        assertFalse(packet.injected());
        assertTrue(packet.hits().isEmpty());
    }

    @Test
    @DisplayName("低于最低分阈值的命中被丢弃")
    void dropsBelowMinScore() {
        KbChatSettings strict =
                new KbChatSettings(true, true, 3, 200, 900, 0.8d, 1, 2000L, false, false);
        KbContextPacket packet =
                KbContextPacket.from(response(hit(101, 1, "员工手册", "休假", 3, 0.3d, "年假 5 天")),
                        "年假政策", strict, false);

        assertEquals(KbContextPacket.STATUS_LOW_RELEVANCE, packet.status());
    }

    @Test
    @DisplayName("同一分块多路召回合并为一条并保留全部召回路径")
    void dedupsAndMergesRoutes() {
        String json =
                "{\"status\":\"OK\",\"data\":{\"results\":[{\"series_id\":1,\"series_name\":\"制度库\","
                        + "\"hits\":["
                        + hitBody(101, "员工手册", "年假 5 天", "[\"bm25\"]", 0.7d)
                        + ","
                        + hitBody(101, "员工手册", "年假 5 天，满一年", "[\"vector\"]", 0.9d)
                        + "]}]}}";
        KbContextPacket packet = KbContextPacket.from(json, "年假政策", SETTINGS, false);

        assertEquals(1, packet.hits().size());
        assertEquals(0.9d, packet.hits().get(0).score(), 0.0001d);
        assertTrue(packet.hits().get(0).routes().containsAll(List.of("bm25", "vector")));
    }

    @Test
    @DisplayName("超出字数预算的尾部条目被丢弃并计入 dropped")
    void dropsEntriesBeyondBudget() {
        KbChatSettings tight =
                new KbChatSettings(true, true, 3, 150, 200, 0.0d, 1, 2000L, false, false);
        String json =
                "{\"status\":\"OK\",\"data\":{\"results\":[{\"series_id\":1,\"series_name\":\"制度库\","
                        + "\"hits\":["
                        + hitBody(101, "制度A", "年假 " + "甲".repeat(110), "[\"bm25\"]", 0.9d)
                        + ","
                        + hitBody(102, "制度B", "年假 " + "乙".repeat(110), "[\"bm25\"]", 0.8d)
                        + "]}]}}";
        KbContextPacket packet = KbContextPacket.from(json, "年假政策", tight, false);

        assertEquals(KbContextPacket.STATUS_OK, packet.status());
        assertEquals(1, packet.hits().size());
        assertEquals(1, packet.dropped());
        assertTrue(packet.chars() <= 200);
    }

    @Test
    @DisplayName("创作类智能体的材料头部声明可选、不必引用")
    void creativeNoteIsOptional() {
        String json = response(hit(101, 1, "诗词汇编", "唐诗", 2, 0.9d, "关于饺子的诗"));

        KbContextPacket normal = KbContextPacket.from(json, "饺子", SETTINGS, false);
        KbContextPacket creative = KbContextPacket.from(json, "饺子", SETTINGS, true);

        assertFalse(normal.block().contains("不要求引用"));
        assertTrue(creative.block().contains("不要求引用"));
    }

    @Test
    @DisplayName("空响应与非 OK 状态折成无材料")
    void degradesOnBadResponse() {
        assertEquals(KbContextPacket.STATUS_ERROR, KbContextPacket.from(null, "年假", SETTINGS, false)
                .status());
        assertEquals(KbContextPacket.STATUS_ERROR, KbContextPacket.from("not-json", "年假", SETTINGS, false)
                .status());
        assertEquals(KbContextPacket.STATUS_NO_HIT,
                KbContextPacket.from("{\"status\":\"NO_LIBRARY\"}", "年假", SETTINGS, false).status());
    }

    private static String response(String hit) {
        return "{\"status\":\"OK\",\"data\":{\"results\":[{\"series_id\":1,\"series_name\":\"制度库\","
                + "\"hits\":["
                + hit
                + "]}]}}";
    }

    private static String hit(
            long chunkId,
            long documentId,
            String documentName,
            String titlePath,
            int pageNo,
            double score,
            String content) {
        return "{\"chunk_id\":"
                + chunkId
                + ",\"chunk_seq\":1,\"document_id\":"
                + documentId
                + ",\"document_version\":2,\"document_name\":\""
                + documentName
                + "\",\"page_no\":"
                + pageNo
                + ",\"title_path\":\""
                + titlePath
                + "\",\"score\":"
                + score
                + ",\"point_id\":\"p-"
                + chunkId
                + "\",\"routes\":[\"bm25\"],\"content\":\""
                + content
                + "\"}";
    }

    private static String hitBody(
            long chunkId, String documentName, String content, String routes, double score) {
        return "{\"chunk_id\":"
                + chunkId
                + ",\"chunk_seq\":1,\"document_id\":10,\"document_name\":\""
                + documentName
                + "\",\"page_no\":1,\"title_path\":\"休假\",\"score\":"
                + score
                + ",\"routes\":"
                + routes
                + ",\"content\":\""
                + content
                + "\"}";
    }
}
