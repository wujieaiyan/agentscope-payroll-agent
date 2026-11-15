package io.agentscope.examples.payroll.domain;

import java.util.List;

/**
 * 一次回答引用的知识库来源；index 与正文角标 [n] 对应，point_id 为检索返回的知识点编号。
 */
public record Citation(
        int index,
        String pointId,
        long seriesId,
        String seriesName,
        long documentId,
        String documentName,
        long chunkId,
        Integer pageNo,
        String titlePath,
        double score,
        List<String> routes,
        String snippet,
        String query,
        String retrievedAt,
        Integer chunkSeq,
        Long documentVersion,
        String contentSnapshot,
        boolean lowConfidence) {

    public Citation {
        pointId = pointId == null ? "" : pointId;
        seriesName = seriesName == null ? "" : seriesName;
        documentName = documentName == null ? "" : documentName;
        titlePath = titlePath == null ? "" : titlePath;
        routes = routes == null ? List.of() : List.copyOf(routes);
        snippet = snippet == null ? "" : snippet;
        query = query == null ? "" : query;
        retrievedAt = retrievedAt == null ? "" : retrievedAt;
        contentSnapshot = contentSnapshot == null ? "" : contentSnapshot;
    }

    /** 兼容旧构造：历史代码与旧消息没有溯源快照字段。 */
    public Citation(
            int index,
            String pointId,
            long seriesId,
            String seriesName,
            long documentId,
            String documentName,
            long chunkId,
            Integer pageNo,
            String titlePath,
            double score,
            List<String> routes,
            String snippet) {
        this(
                index,
                pointId,
                seriesId,
                seriesName,
                documentId,
                documentName,
                chunkId,
                pageNo,
                titlePath,
                score,
                routes,
                snippet,
                "",
                "",
                null,
                null,
                "",
                false);
    }
}
