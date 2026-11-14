package io.agentscope.examples.payroll.kb;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 多路召回与知识点融合的服务级开关；库级阈值/召回量仍沿用当前生效配置。 */
@Component
public class KbRetrievalSettings {

    public enum Route {
        VECTOR,
        BM25,
        TITLE_PATH
    }

    private static final List<Route> ALL_ROUTES =
            List.of(Route.VECTOR, Route.BM25, Route.TITLE_PATH);

    private final boolean fusionEnabled;
    private final List<Route> recallRoutes;
    private final int rrfK;
    private final double vectorWeight;
    private final double bm25Weight;
    private final double titleWeight;
    private final double keywordThreshold;
    private final double titleThreshold;
    private final int maxPerDocument;
    private final int maxMergeChunks;

    public KbRetrievalSettings(
            @Value("${payroll.kb.retrieval.fusion-enabled:true}") boolean fusionEnabled,
            @Value("${payroll.kb.retrieval.recall-routes:VECTOR,BM25,TITLE_PATH}") String recallRoutes,
            @Value("${payroll.kb.retrieval.rrf-k:60}") int rrfK,
            @Value("${payroll.kb.retrieval.vector-weight:1.0}") double vectorWeight,
            @Value("${payroll.kb.retrieval.bm25-weight:0.8}") double bm25Weight,
            @Value("${payroll.kb.retrieval.title-weight:0.5}") double titleWeight,
            @Value("${payroll.kb.retrieval.keyword-threshold:0.3}") double keywordThreshold,
            @Value("${payroll.kb.retrieval.title-threshold:0.3}") double titleThreshold,
            @Value("${payroll.kb.retrieval.max-per-document:3}") int maxPerDocument,
            @Value("${payroll.kb.retrieval.max-merge-chunks:3}") int maxMergeChunks) {
        this.fusionEnabled = fusionEnabled;
        this.recallRoutes = parseRoutes(recallRoutes);
        this.rrfK = Math.max(1, rrfK);
        this.vectorWeight = weight(vectorWeight);
        this.bm25Weight = weight(bm25Weight);
        this.titleWeight = weight(titleWeight);
        this.keywordThreshold = clamp01(keywordThreshold);
        this.titleThreshold = clamp01(titleThreshold);
        this.maxPerDocument = Math.max(1, maxPerDocument);
        this.maxMergeChunks = Math.max(1, maxMergeChunks);
    }

    public boolean fusionEnabled() {
        return fusionEnabled;
    }

    public List<Route> recallRoutes() {
        return recallRoutes;
    }

    public boolean routeEnabled(Route route) {
        return recallRoutes.contains(route);
    }

    public int rrfK() {
        return rrfK;
    }

    public double weight(Route route) {
        return switch (route) {
            case VECTOR -> vectorWeight;
            case BM25 -> bm25Weight;
            case TITLE_PATH -> titleWeight;
        };
    }

    public double keywordThreshold() {
        return keywordThreshold;
    }

    public double titleThreshold() {
        return titleThreshold;
    }

    public int maxPerDocument() {
        return maxPerDocument;
    }

    public int maxMergeChunks() {
        return maxMergeChunks;
    }

    private static List<Route> parseRoutes(String csv) {
        if (csv == null || csv.isBlank()) {
            return ALL_ROUTES;
        }
        Set<Route> routes = new LinkedHashSet<>();
        for (String token : csv.split(",")) {
            String value = token.trim().toUpperCase(Locale.ROOT);
            if (value.isEmpty()) {
                continue;
            }
            try {
                routes.add(Route.valueOf(value));
            } catch (IllegalArgumentException ignored) {
                // 未知路由名忽略，避免配置错误阻断检索。
            }
        }
        return routes.isEmpty() ? ALL_ROUTES : List.copyOf(new ArrayList<>(routes));
    }

    private static double weight(double value) {
        return Math.max(0, value);
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
