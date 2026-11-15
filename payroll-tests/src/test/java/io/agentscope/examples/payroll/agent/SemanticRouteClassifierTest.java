package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SemanticRouteClassifierTest {

    @Test
    @DisplayName("每标签取最高分后比较 top1 与 top2")
    void takesBestPerLabel() {
        SemanticRouteClassifier.Decision decision =
                SemanticRouteClassifier.decide(
                        List.of(
                                new SemanticRouteClassifier.Hit(SemanticRouteClassifier.LABEL_KB_READ, 0.71),
                                new SemanticRouteClassifier.Hit(SemanticRouteClassifier.LABEL_KB_READ, 0.62),
                                new SemanticRouteClassifier.Hit(SemanticRouteClassifier.LABEL_OTHER, 0.60)),
                        0.55,
                        0.05);
        assertTrue(decision.confident());
        assertEquals(SemanticRouteClassifier.LABEL_KB_READ, decision.label());
        assertEquals(0.71, decision.score(), 1e-9);
        assertEquals(0.11, decision.margin(), 1e-9);
    }

    @Test
    @DisplayName("top1 低于 min-score 不采用")
    void rejectsLowScore() {
        SemanticRouteClassifier.Decision decision =
                SemanticRouteClassifier.decide(
                        List.of(
                                new SemanticRouteClassifier.Hit(SemanticRouteClassifier.LABEL_OTHER, 0.54),
                                new SemanticRouteClassifier.Hit(SemanticRouteClassifier.LABEL_KB_ADMIN, 0.10)),
                        0.55,
                        0.05);
        assertFalse(decision.confident());
        assertEquals(SemanticRouteClassifier.LABEL_OTHER, decision.label());
    }

    @Test
    @DisplayName("与 top2 差距不足 margin 不采用")
    void rejectsSmallMargin() {
        SemanticRouteClassifier.Decision decision =
                SemanticRouteClassifier.decide(
                        List.of(
                                new SemanticRouteClassifier.Hit(SemanticRouteClassifier.LABEL_KB_READ, 0.72),
                                new SemanticRouteClassifier.Hit(SemanticRouteClassifier.LABEL_KB_ADMIN, 0.70)),
                        0.55,
                        0.05);
        assertFalse(decision.confident());
        assertEquals(0.02, decision.margin(), 1e-9);
    }

    @Test
    @DisplayName("单标签命中时 margin 等于其分数")
    void singleLabelMargin() {
        SemanticRouteClassifier.Decision decision =
                SemanticRouteClassifier.decide(
                        List.of(
                                new SemanticRouteClassifier.Hit(SemanticRouteClassifier.LABEL_KB_ADMIN, 0.66)),
                        0.55,
                        0.05);
        assertTrue(decision.confident());
        assertEquals(0.66, decision.margin(), 1e-9);
    }

    @Test
    @DisplayName("空命中与全空标签都判为不可用")
    void emptyHitsUnavailable() {
        assertEquals(
                SemanticRouteClassifier.LABEL_UNAVAILABLE,
                SemanticRouteClassifier.decide(List.of(), 0.55, 0.05).label());
        assertEquals(
                SemanticRouteClassifier.LABEL_UNAVAILABLE,
                SemanticRouteClassifier.decide(null, 0.55, 0.05).label());
        assertEquals(
                SemanticRouteClassifier.LABEL_UNAVAILABLE,
                SemanticRouteClassifier.decide(
                                List.of(new SemanticRouteClassifier.Hit(" ", 0.9)), 0.55, 0.05)
                        .label());
    }

    @Test
    @DisplayName("配置缺省值与越界值回落默认")
    void settingsDefaults() {
        SemanticRouteSettings defaults =
                new SemanticRouteSettings(false, 0, 0, "", 0, 0, "", null, null, "", "", 0);
        assertEquals(0.50, defaults.minScore(), 1e-9);
        assertEquals(0.05, defaults.margin(), 1e-9);
        assertEquals("payroll-route-intents", defaults.index());
        assertEquals(150, defaults.timeoutMs());
        assertEquals(60, defaults.seedTimeoutSeconds());
        assertEquals("http://localhost:9200", defaults.esUrl());
        assertEquals("http://localhost:11434", defaults.embeddingBaseUrl());
        assertEquals("qwen3-embedding:0.6b", defaults.embeddingModel());
        assertEquals(1024, defaults.embeddingDim());
        assertTrue(SemanticRouteSettings.disabled().esUsername().isEmpty());
    }
}
