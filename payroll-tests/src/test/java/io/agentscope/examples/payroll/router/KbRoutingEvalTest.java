package io.agentscope.examples.payroll.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.examples.payroll.agent.KbChatIntent;
import io.agentscope.examples.payroll.agent.KbRoutingPolicy;
import io.agentscope.examples.payroll.agent.SemanticRouteClassifier;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * P3 路由回归评测：默认跳过，{@code -Dpayroll.eval.routing=true} 执行。
 *
 * <p>只覆盖确定性层：L0 规则 + 可选的语义桩（{@code fixture.semantic_label}）。
 * L2 模型路由与全链路指标由 {@code scripts/route-eval.sh} 对运行期实例测量。
 */
@Tag("eval")
class KbRoutingEvalTest {

    private static final String CASES = "/routing/kb-routing-cases.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final KbRoutingPolicy.Options OPTIONS =
            new KbRoutingPolicy.Options(true, true, false, true, 20);

    private static JsonNode cases;

    @BeforeAll
    static void loadCases() throws IOException {
        Assumptions.assumeTrue(
                Boolean.getBoolean("payroll.eval.routing"),
                "评测集默认跳过：加 -Dpayroll.eval.routing=true 执行");
        try (InputStream in = KbRoutingEvalTest.class.getResourceAsStream(CASES)) {
            assertTrue(in != null, "缺少评测集 " + CASES);
            cases = JSON.readTree(in).path("cases");
        }
    }

    @Test
    @DisplayName("评测集为 150 条且六类数量与规格一致")
    void caseSetMatchesSpec() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JsonNode node : cases) {
            counts.merge(node.path("category").asText(""), 1, Integer::sum);
        }
        assertEquals(150, cases.size());
        assertEquals(50, counts.get("payroll"));
        assertEquals(30, counts.get("kb_direct"));
        assertEquals(20, counts.get("kb_followup"));
        assertEquals(20, counts.get("kb_synonym"));
        assertEquals(15, counts.get("kb_negative"));
        assertEquals(15, counts.get("mixed"));
    }

    @Test
    @DisplayName("逐条 L0 决策与 need_kb 与期望一致")
    void layerZeroMatchesExpectations() {
        StringBuilder failures = new StringBuilder();
        for (JsonNode node : cases) {
            String id = node.path("id").asText();
            String question = node.path("question").asText();
            JsonNode fixture = node.path("fixture");
            JsonNode expect = node.path("expect");
            boolean bound = fixture.path("bound").asBoolean(false);
            boolean lastCited = fixture.path("last_cited").asBoolean(false);

            KbRoutingPolicy.Result l0 = KbRoutingPolicy.decide(question, bound, lastCited, OPTIONS);
            SemanticRouteClassifier.Decision semantic = semanticStub(fixture);
            KbRoutePlanner.Plan plan =
                    KbRoutePlanner.plan(KbChatIntent.isPayroll(question), l0, semantic, null);

            String wantPolicy = expect.path("policy").asText();
            boolean wantNeedKb = expect.path("need_kb").asBoolean();
            if (!l0.decision().name().equals(wantPolicy)) {
                failures.append(id)
                        .append(" policy 期望 ")
                        .append(wantPolicy)
                        .append(" 实际 ")
                        .append(l0.decision().name())
                        .append('\n');
            }
            if (plan.need() != wantNeedKb) {
                failures.append(id)
                        .append(" need_kb 期望 ")
                        .append(wantNeedKb)
                        .append(" 实际 ")
                        .append(plan.need())
                        .append('\n');
            }
        }
        assertEquals("", failures.toString(), "存在与期望不一致的用例：\n" + failures);
    }

    @Test
    @DisplayName("分类召回与误检索率满足 P3 门槛")
    void categoryGates() {
        int payrollHits = 0;
        int directHits = 0;
        int directTotal = 0;
        int followupHits = 0;
        int followupTotal = 0;
        int synonymHits = 0;
        int synonymTotal = 0;
        int negativeHits = 0;
        int negativeTotal = 0;
        int videoWritingFalsePositive = 0;

        for (JsonNode node : cases) {
            String category = node.path("category").asText();
            String question = node.path("question").asText();
            JsonNode fixture = node.path("fixture");
            boolean payroll = KbChatIntent.isPayroll(question);
            KbRoutingPolicy.Result l0 =
                    KbRoutingPolicy.decide(
                            question,
                            fixture.path("bound").asBoolean(false),
                            fixture.path("last_cited").asBoolean(false),
                            OPTIONS);
            boolean need =
                    KbRoutePlanner.plan(payroll, l0, semanticStub(fixture), null).need();
            switch (category) {
                case "payroll" -> payrollHits += payroll ? 1 : 0;
                case "kb_direct" -> {
                    directTotal++;
                    directHits += need ? 1 : 0;
                }
                case "kb_followup" -> {
                    followupTotal++;
                    followupHits += need ? 1 : 0;
                }
                case "kb_synonym" -> {
                    synonymTotal++;
                    synonymHits += need ? 1 : 0;
                }
                case "kb_negative" -> {
                    negativeTotal++;
                    negativeHits += need ? 1 : 0;
                    if (need && (question.contains("视频") || question.contains("文案"))) {
                        videoWritingFalsePositive++;
                    }
                }
                default -> {
                    // mixed 只参与逐条断言，不纳入召回率。
                }
            }
        }

        assertEquals(50, payrollHits, "薪酬 50 题必须全部识别为薪酬");
        assertTrue(directHits >= directTotal * 0.95D, "kb_direct ≥95%：" + directHits + "/" + directTotal);
        assertEquals(followupTotal, followupHits, "kb_followup 必须 100% 命中检索");
        assertTrue(synonymHits >= synonymTotal * 0.85D, "kb_synonym ≥85%：" + synonymHits + "/" + synonymTotal);
        assertTrue(
                negativeHits <= negativeTotal * 0.05D,
                "kb_negative 误检索 ≤5%：" + negativeHits + "/" + negativeTotal);
        assertEquals(0, videoWritingFalsePositive, "生成视频/写文案必须 0 次误检索");
    }

    private static SemanticRouteClassifier.Decision semanticStub(JsonNode fixture) {
        String label = fixture.path("semantic_label").asText("");
        if (label.isBlank() || !fixture.path("semantic_confident").asBoolean(false)) {
            return null;
        }
        return new SemanticRouteClassifier.Decision(true, label, 0.9D, 0.3D, "eval-stub");
    }
}
