package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RouteIntentSeedsTest {

    @Test
    @DisplayName("类路径种子齐备：3 标签各 30 条，版本非空")
    void classpathCatalogIsComplete() {
        RouteIntentSeeds.Catalog catalog = RouteIntentSeeds.loadClasspath();
        assertFalse(catalog.version().isBlank());
        assertEquals(3, catalog.labels().size());
        assertEquals(30, catalog.count(SemanticRouteClassifier.LABEL_KB_READ));
        assertEquals(30, catalog.count(SemanticRouteClassifier.LABEL_KB_ADMIN));
        assertEquals(30, catalog.count(SemanticRouteClassifier.LABEL_OTHER));
        assertEquals(90, catalog.samples().size());
    }

    @Test
    @DisplayName("缺少 version 时拒绝加载")
    void rejectsMissingVersion() {
        String json = "{\"labels\":[\"kb_read\",\"kb_admin\",\"other\"],\"samples\":[]}";
        assertThrows(IllegalStateException.class, () -> RouteIntentSeeds.parse(stream(json)));
    }

    @Test
    @DisplayName("缺少必需标签时拒绝加载")
    void rejectsMissingLabel() {
        String json =
                "{\"version\":\"v1\",\"labels\":[\"kb_read\",\"kb_admin\"],"
                        + "\"samples\":[{\"label\":\"kb_read\",\"question\":\"制度在哪\"}]}";
        assertThrows(IllegalStateException.class, () -> RouteIntentSeeds.parse(stream(json)));
    }

    @Test
    @DisplayName("未知标签与空问句都拒绝加载")
    void rejectsUnknownLabelAndBlankQuestion() {
        String unknownLabel =
                "{\"version\":\"v1\",\"labels\":[\"kb_read\",\"kb_admin\",\"other\"],"
                        + "\"samples\":[{\"label\":\"kb_write\",\"question\":\"x\"}]}";
        assertThrows(IllegalStateException.class, () -> RouteIntentSeeds.parse(stream(unknownLabel)));

        String blankQuestion =
                "{\"version\":\"v1\",\"labels\":[\"kb_read\",\"kb_admin\",\"other\"],"
                        + "\"samples\":[{\"label\":\"kb_read\",\"question\":\"  \"}]}";
        assertThrows(IllegalStateException.class, () -> RouteIntentSeeds.parse(stream(blankQuestion)));
    }

    @Test
    @DisplayName("标签无样本时拒绝加载")
    void rejectsEmptyLabel() {
        String json =
                "{\"version\":\"v1\",\"labels\":[\"kb_read\",\"kb_admin\",\"other\"],"
                        + "\"samples\":[{\"label\":\"kb_read\",\"question\":\"年假几天\"}]}";
        assertThrows(IllegalStateException.class, () -> RouteIntentSeeds.parse(stream(json)));
    }

    @Test
    @DisplayName("合法最小样本可解析")
    void parsesMinimalCatalog() throws Exception {
        String json =
                "{\"version\":\"v1\",\"labels\":[\"kb_read\",\"kb_admin\",\"other\"],"
                        + "\"samples\":["
                        + "{\"label\":\"kb_read\",\"question\":\"年假几天\"},"
                        + "{\"label\":\"kb_admin\",\"question\":\"新建知识库\"},"
                        + "{\"label\":\"other\",\"question\":\"讲个笑话\"}]}";
        RouteIntentSeeds.Catalog catalog = RouteIntentSeeds.parse(stream(json));
        assertEquals("v1", catalog.version());
        assertEquals(1, catalog.count(SemanticRouteClassifier.LABEL_OTHER));
    }

    private static ByteArrayInputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("关闭状态不抛异常，判定为 unavailable")
    void disabledClassifierReturnsUnavailable() {
        SemanticRouteClassifier classifier = SemanticRouteClassifier.disabled("未启用");
        assertFalse(classifier.ready());
        SemanticRouteClassifier.Decision decision = classifier.classify("年假几天");
        assertFalse(decision.confident());
        assertEquals(SemanticRouteClassifier.LABEL_UNAVAILABLE, decision.label());
        assertEquals("未启用", decision.reason());
        assertTrue(SemanticRouteClassifier.disabled(null).classify(null).reason().isEmpty());
    }
}
