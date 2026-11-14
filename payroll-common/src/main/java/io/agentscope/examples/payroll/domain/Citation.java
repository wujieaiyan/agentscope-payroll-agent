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
        String snippet) {

    public Citation {
        pointId = pointId == null ? "" : pointId;
        seriesName = seriesName == null ? "" : seriesName;
        documentName = documentName == null ? "" : documentName;
        titlePath = titlePath == null ? "" : titlePath;
        routes = routes == null ? List.of() : List.copyOf(routes);
        snippet = snippet == null ? "" : snippet;
    }
}
