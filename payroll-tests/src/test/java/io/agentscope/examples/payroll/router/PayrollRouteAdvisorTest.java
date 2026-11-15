package io.agentscope.examples.payroll.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.agent.PayrollRouteBucket;
import io.agentscope.examples.payroll.agent.PayrollRouteCache;
import io.agentscope.examples.payroll.agent.PayrollRouteMode;
import io.agentscope.examples.payroll.agent.RouteIntentClassifier;
import io.agentscope.examples.payroll.agent.SemanticRouteClassifier;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayrollRouteAdvisorTest {

    private static final String LONG_QUESTION = "请帮我计算一下本部门本月所有员工的应发工资合计金额";
    private static final List<AgentDefinition> CANDIDATES = List.of(online("agent-1", "核算员"));

    @Test
    @DisplayName("off 档不碰语义层与缓存，行为与改造前一致")
    void offModeCallsOnlyLlm() {
        AtomicInteger llmCalls = new AtomicInteger();
        MemoryStore store = new MemoryStore();
        PayrollRouteAdvisor advisor =
                advisor(PayrollRouteMode.OFF, confident("payroll_calc"), store, 20);

        PayrollRouteAdvisor.Outcome outcome =
                advisor.decide(
                        LONG_QUESTION, CANDIDATES, true, "", "", () -> llmDecision(llmCalls));

        assertEquals(1, llmCalls.get());
        assertFalse(outcome.cacheHit());
        assertTrue(outcome.bucket().isEmpty());
        assertEquals("agent-1", outcome.decision().agent().id());
    }

    @Test
    @DisplayName("语义未就绪时直接走模型路由")
    void notReadyFallsBackToLlm() {
        AtomicInteger llmCalls = new AtomicInteger();
        PayrollRouteAdvisor advisor =
                new PayrollRouteAdvisor(
                        PayrollRouteMode.CACHE,
                        new RouteIntentClassifier() {
                            @Override
                            public boolean ready() {
                                return false;
                            }

                            @Override
                            public SemanticRouteClassifier.Decision classify(String question) {
                                throw new IllegalStateException("不应被调用");
                            }
                        },
                        new PayrollRouteCache(new MemoryStore()),
                        "model",
                        20);

        advisor.decide(LONG_QUESTION, CANDIDATES, true, "", "", () -> llmDecision(llmCalls));

        assertEquals(1, llmCalls.get());
    }

    @Test
    @DisplayName("低置信或不可缓存桶直接走模型路由，不写缓存")
    void unusableBucketSkipsCache() {
        MemoryStore store = new MemoryStore();
        PayrollRouteAdvisor lowConfidence =
                advisor(PayrollRouteMode.CACHE, lowConfidence("payroll_calc"), store, 20);
        AtomicInteger llmCalls = new AtomicInteger();

        lowConfidence.decide(LONG_QUESTION, CANDIDATES, true, "", "", () -> llmDecision(llmCalls));
        lowConfidence.decide(LONG_QUESTION, CANDIDATES, true, "", "", () -> llmDecision(llmCalls));

        assertEquals(2, llmCalls.get());
        assertTrue(store.keys().isEmpty());

        PayrollRouteAdvisor action =
                advisor(PayrollRouteMode.CACHE, confident(PayrollRouteBucket.ACTION), store, 20);
        action.decide(LONG_QUESTION, CANDIDATES, true, "", "", () -> llmDecision(llmCalls));
        action.decide(LONG_QUESTION, CANDIDATES, true, "", "", () -> llmDecision(llmCalls));

        assertEquals(4, llmCalls.get());
        assertTrue(store.keys().isEmpty());
    }

    @Test
    @DisplayName("短问句与追问指代不读也不写缓存")
    void unsafeQuestionNeverTouchesCache() {
        MemoryStore store = new MemoryStore();
        PayrollRouteAdvisor advisor =
                advisor(PayrollRouteMode.CACHE, confident("payroll_calc"), store, 20);
        AtomicInteger llmCalls = new AtomicInteger();

        for (int i = 0; i < 4; i++) {
            advisor.decide("帮我核算工资", CANDIDATES, true, "", "", () -> llmDecision(llmCalls));
            advisor.decide("上面那个再算一遍", CANDIDATES, true, "", "", () -> llmDecision(llmCalls));
        }

        assertEquals(8, llmCalls.get());
        assertTrue(store.keys().isEmpty());
    }

    @Test
    @DisplayName("连续两次一致后写缓存，第三次命中文案不再调用模型")
    void cacheHitSkipsLlm() {
        MemoryStore store = new MemoryStore();
        PayrollRouteAdvisor advisor =
                advisor(PayrollRouteMode.CACHE, confident("payroll_calc"), store, 20);
        AtomicInteger llmCalls = new AtomicInteger();

        PayrollRouteAdvisor.Outcome first =
                advisor.decide(LONG_QUESTION, CANDIDATES, true, "", "", () -> llmDecision(llmCalls));
        PayrollRouteAdvisor.Outcome second =
                advisor.decide(LONG_QUESTION, CANDIDATES, true, "", "", () -> llmDecision(llmCalls));
        PayrollRouteAdvisor.Outcome third =
                advisor.decide(LONG_QUESTION, CANDIDATES, true, "", "", () -> llmDecision(llmCalls));

        assertEquals(2, llmCalls.get());
        assertFalse(first.cacheHit());
        assertFalse(second.cacheHit());
        assertTrue(third.cacheHit());
        assertEquals("payroll_calc", third.bucket());
        assertEquals(RouteDecision.KB_SOURCE_CACHE, third.decision().kbSource());
        assertEquals("agent-1", third.decision().agent().id());
    }

    @Test
    @DisplayName("缓存命中的智能体已不在当前目录时回退模型路由")
    void missingAgentInvalidatesHit() {
        MemoryStore store = new MemoryStore();
        PayrollRouteCache cache = new PayrollRouteCache(store);
        PayrollRouteAdvisor advisor =
                new PayrollRouteAdvisor(
                        PayrollRouteMode.CACHE, confident("payroll_calc"), cache, "model", 20);
        PayrollRouteCache.DecisionKey key =
                new PayrollRouteCache.DecisionKey(
                        PayrollRouteBucket.CALC,
                        PayrollRouteAdvisor.signature(CANDIDATES),
                        "",
                        "",
                        true,
                        "model");
        PayrollRouteCache.Entry ghost =
                new PayrollRouteCache.Entry("agent", "ghost", "已下线", 0.8);
        cache.record(key, ghost);
        cache.record(key, ghost);

        AtomicInteger llmCalls = new AtomicInteger();
        PayrollRouteAdvisor.Outcome outcome =
                advisor.decide(LONG_QUESTION, CANDIDATES, true, "", "", () -> llmDecision(llmCalls));

        assertFalse(outcome.cacheHit());
        assertEquals(1, llmCalls.get());
        assertEquals("agent-1", outcome.decision().agent().id());
    }

    @Test
    @DisplayName("shadow 档只读缓存做比对，绝不写任何键")
    void shadowPeekNeverWrites() {
        MemoryStore store = new MemoryStore();
        PayrollRouteAdvisor cacheAdvisor =
                advisor(PayrollRouteMode.CACHE, confident("payroll_calc"), store, 20);
        AtomicInteger seedCalls = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            cacheAdvisor.decide(
                    LONG_QUESTION, CANDIDATES, true, "", "", () -> llmDecision(seedCalls));
        }
        int keysAfterSeed = store.keys().size();

        PayrollRouteAdvisor shadowAdvisor =
                advisor(PayrollRouteMode.SHADOW, confident("payroll_calc"), store, 20);
        AtomicInteger llmCalls = new AtomicInteger();
        PayrollRouteAdvisor.Outcome outcome =
                shadowAdvisor.decide(
                        LONG_QUESTION,
                        CANDIDATES,
                        true,
                        "",
                        "",
                        () -> llmDecision(llmCalls, "agent-2"));

        assertEquals(1, llmCalls.get());
        assertFalse(outcome.cacheHit());
        assertTrue(outcome.shadowDiff());
        assertEquals(keysAfterSeed, store.keys().size());
    }

    @Test
    @DisplayName("缓存介质异常时 fail-open，仍按模型结果返回")
    void cacheFailureFailsOpen() {
        PayrollRouteAdvisor advisor =
                new PayrollRouteAdvisor(
                        PayrollRouteMode.CACHE,
                        confident("payroll_calc"),
                        new PayrollRouteCache(
                                new PayrollRouteCache.Store() {
                                    @Override
                                    public String get(String key) {
                                        throw new IllegalStateException("redis down");
                                    }

                                    @Override
                                    public void set(String key, String value, Duration ttl) {
                                        throw new IllegalStateException("redis down");
                                    }
                                }),
                        "model",
                        20);
        AtomicInteger llmCalls = new AtomicInteger();

        PayrollRouteAdvisor.Outcome outcome =
                advisor.decide(LONG_QUESTION, CANDIDATES, true, "", "", () -> llmDecision(llmCalls));

        assertEquals(1, llmCalls.get());
        assertFalse(outcome.cacheHit());
        assertEquals("agent-1", outcome.decision().agent().id());
    }

    @Test
    @DisplayName("目录签名随目录内容变化")
    void signatureChangesWithCatalog() {
        String first = PayrollRouteAdvisor.signature(CANDIDATES);
        assertEquals(first, PayrollRouteAdvisor.signature(List.copyOf(CANDIDATES)));
        assertFalse(first.equals(PayrollRouteAdvisor.signature(List.of(online("agent-1", "改名")))));
        assertEquals("empty", PayrollRouteAdvisor.signature(List.of()));
    }

    private static PayrollRouteAdvisor advisor(
            PayrollRouteMode mode, RouteIntentClassifier semantic, MemoryStore store, int maxChars) {
        return new PayrollRouteAdvisor(
                mode, semantic, new PayrollRouteCache(store), "model", maxChars);
    }

    private static RouteIntentClassifier confident(String label) {
        return classifier(label, true);
    }

    private static RouteIntentClassifier lowConfidence(String label) {
        return classifier(label, false);
    }

    private static RouteIntentClassifier classifier(String label, boolean confident) {
        return new RouteIntentClassifier() {
            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public SemanticRouteClassifier.Decision classify(String question) {
                return new SemanticRouteClassifier.Decision(
                        confident, label, 0.82, 0.19, confident ? "" : "低置信");
            }
        };
    }

    private static RouteDecision llmDecision(AtomicInteger calls) {
        return llmDecision(calls, "agent-1");
    }

    private static RouteDecision llmDecision(AtomicInteger calls, String agentId) {
        calls.incrementAndGet();
        AgentDefinition agent =
                agentId.equals("agent-9")
                        ? online("agent-9", "旧核算员")
                        : CANDIDATES.stream()
                                .filter(item -> item.id().equals(agentId))
                                .findFirst()
                                .orElse(online(agentId, "候选"));
        return new RouteDecision("agent", agent, "模型选择", 0.8, null, false, "none", "");
    }

    private static AgentDefinition online(String id, String name) {
        return new AgentDefinition(
                id,
                name,
                null,
                "测试智能体",
                List.of(),
                List.of(),
                AgentDefinition.VISIBILITY_PUBLIC,
                AgentDefinition.STATUS_ONLINE,
                "tester",
                null,
                null,
                List.of());
    }

    private static final class MemoryStore implements PayrollRouteCache.Store {

        private final Map<String, String> values = new HashMap<>();

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            values.put(key, value);
        }

        List<String> keys() {
            return new ArrayList<>(values.keySet());
        }
    }
}
