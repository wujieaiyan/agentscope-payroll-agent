package io.agentscope.examples.payroll.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.examples.payroll.domain.Citation;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 采集本轮 {@code kb_search} 命中的来源，供回答结束后统一下发 citation 事件并随消息落库。
 *
 * <p>工具层只写集合、不直接操作 SSE；同一分块多次命中按 chunkId 去重并合并召回路径，
 * 引用序号在读取时重新编号，保证正文中的 [n] 与最终列表一致。
 */
public final class KbCitationCollector {

    private static final Logger log = LoggerFactory.getLogger(KbCitationCollector.class);

    public static final int MAX_CITATIONS = 8;
    private static final int MAX_SNIPPET = 240;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Map<Long, Seed> seeds = new LinkedHashMap<>();

    /** 解析 Web 侧 kb_search 的响应；错误响应与无命中不产生引用。 */
    public synchronized void record(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) {
            return;
        }
        Map<String, Object> payload;
        try {
            payload = JsonSupport.fromJson(responseJson, MAP_TYPE);
        } catch (RuntimeException e) {
            log.debug("[知识库引用] 响应无法解析，忽略：{}", e.getMessage());
            return;
        }
        if (!"OK".equalsIgnoreCase(text(payload.get("status")))) {
            return;
        }
        Object data = payload.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return;
        }
        Object results = dataMap.get("results");
        if (!(results instanceof List<?> libraries)) {
            return;
        }
        for (Object library : libraries) {
            if (!(library instanceof Map<?, ?> libraryMap)) {
                continue;
            }
            long seriesId = number(libraryMap.get("series_id"), 0L);
            String seriesName = clean(text(libraryMap.get("series_name")), 120);
            Object hits = libraryMap.get("hits");
            if (!(hits instanceof List<?> items)) {
                continue;
            }
            for (Object item : items) {
                if (item instanceof Map<?, ?> hit) {
                    add(seriesId, seriesName, hit);
                }
            }
        }
    }

    /** 按首次命中顺序返回重新编号后的引用，最多 {@link #MAX_CITATIONS} 条。 */
    public synchronized List<Citation> citations() {
        List<Citation> result = new ArrayList<>(seeds.size());
        int index = 1;
        for (Seed seed : seeds.values()) {
            result.add(
                    new Citation(
                            index++,
                            seed.pointId,
                            seed.seriesId,
                            seed.seriesName,
                            seed.documentId,
                            seed.documentName,
                            seed.chunkId,
                            seed.pageNo,
                            seed.titlePath,
                            seed.score,
                            List.copyOf(seed.routes),
                            seed.snippet));
        }
        return List.copyOf(result);
    }

    public synchronized void clear() {
        seeds.clear();
    }

    public synchronized int size() {
        return seeds.size();
    }

    private void add(long seriesId, String seriesName, Map<?, ?> hit) {
        long chunkId = number(hit.get("chunk_id"), 0L);
        long documentId = number(hit.get("document_id"), 0L);
        if (chunkId <= 0 && documentId <= 0) {
            return;
        }
        long key =
                chunkId > 0
                        ? chunkId
                        : -(seriesId * 1_000_000L + documentId * 1_000L + seeds.size());
        Seed existing = seeds.get(key);
        if (existing != null) {
            existing.merge(hit);
            return;
        }
        if (seeds.size() >= MAX_CITATIONS) {
            return;
        }
        seeds.put(
                key,
                new Seed(
                        seriesId,
                        seriesName,
                        documentId,
                        clean(text(hit.get("document_name")), 160),
                        chunkId,
                        pageNo(hit.get("page_no")),
                        clean(text(hit.get("title_path")), 200),
                        number(hit.get("score"), 0.0d),
                        strings(hit.get("routes")),
                        clean(text(hit.get("content")), MAX_SNIPPET),
                        pointId(hit, seeds.size() + 1)));
    }

    private static String pointId(Map<?, ?> hit, int fallback) {
        String value = text(hit.get("point_id"));
        return value.isBlank() ? "K" + fallback : value;
    }

    private static final class Seed {

        private final long seriesId;
        private final String seriesName;
        private final long documentId;
        private final String documentName;
        private final long chunkId;
        private final Integer pageNo;
        private final String titlePath;
        private final String pointId;
        private final Set<String> routes;
        private double score;
        private String snippet;

        private Seed(
                long seriesId,
                String seriesName,
                long documentId,
                String documentName,
                long chunkId,
                Integer pageNo,
                String titlePath,
                double score,
                Set<String> routes,
                String snippet,
                String pointId) {
            this.seriesId = seriesId;
            this.seriesName = seriesName;
            this.documentId = documentId;
            this.documentName = documentName;
            this.chunkId = chunkId;
            this.pageNo = pageNo;
            this.titlePath = titlePath;
            this.score = score;
            this.routes = routes;
            this.snippet = snippet;
            this.pointId = pointId;
        }

        private void merge(Map<?, ?> hit) {
            routes.addAll(strings(hit.get("routes")));
            score = Math.max(score, number(hit.get("score"), 0.0d));
            if (snippet.isBlank()) {
                snippet = clean(text(hit.get("content")), MAX_SNIPPET);
            }
        }
    }

    private static Set<String> strings(Object value) {
        Set<String> result = new LinkedHashSet<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String text = clean(text(item), 40);
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    /** 文档内容是不可信输入：去掉控制字符并折叠空白，避免破坏 SSE 与前端渲染。 */
    private static String clean(String value, int limit) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized =
                value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                        .replaceAll("\\s+", " ")
                        .trim();
        return normalized.length() <= limit
                ? normalized
                : normalized.substring(0, limit) + "…";
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Integer pageNo(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
