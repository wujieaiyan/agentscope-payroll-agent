package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.examples.payroll.service.KbSkillGateway;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KbQueryToolsTest {

    private static final RuntimeContext USER =
            RuntimeContext.builder()
                    .userId("7")
                    .sessionId("s-1")
                    .put(KbSearchScope.class, new KbSearchScope(List.of(3L, 5L)))
                    .build();

    private static FakeGateway gateway(boolean enabled) {
        return new FakeGateway(enabled);
    }

    private static RuntimeContext scoped(KbSearchBudget budget) {
        return RuntimeContext.builder()
                .userId("7")
                .sessionId("s-1")
                .put(KbSearchScope.class, new KbSearchScope(List.of(3L, 5L)))
                .put(KbSearchBudget.class, budget)
                .build();
    }

    @Test
    @DisplayName("未绑定知识库时直接返回提示，不发起检索")
    void unboundScopeIsRejectedLocally() {
        FakeGateway gateway = gateway(true);
        KbQueryTools tools = new KbQueryTools(gateway);

        String reply =
                tools.search(
                        "年假多少天",
                        null,
                        null,
                        RuntimeContext.builder().userId("7").sessionId("s-1").build());

        assertTrue(reply.contains("本会话未启用知识库"), reply);
        assertEquals(0, gateway.calls);
    }

    @Test
    @DisplayName("越界库与会话绑定之外的 ID 在本地拒绝")
    void rejectsSeriesOutsideScope() {
        FakeGateway gateway = gateway(true);
        KbQueryTools tools = new KbQueryTools(gateway);

        String reply = tools.search("年假", List.of(9L), 3, USER);

        assertTrue(reply.contains("只能检索本会话已绑定的知识库"), reply);
        assertEquals(0, gateway.calls);
    }

    @Test
    @DisplayName("默认检索全部绑定库，top_k 被限制在 8 以内")
    void sendsBoundSeriesAndCapsTopK() {
        FakeGateway gateway = gateway(true);
        gateway.result =
                """
                {"status":"OK","data":{"results":[{"series_id":3,"series_name":"制度",
                 "hits":[{"chunk_id":1,"document_id":2,"content":"年假 5 天"}]}]}}
                """;
        KbQueryTools tools = new KbQueryTools(gateway);

        String reply = tools.search("年假", null, 99, USER);

        assertEquals(gateway.result, reply);
        assertEquals(KbQueryTools.KB_SEARCH, gateway.lastTool);
        assertEquals(7L, gateway.lastUserId);
        assertEquals(List.of(3L, 5L), gateway.lastArgs.get("series_ids"));
        assertEquals(KbQueryTools.MAX_TOP_K, gateway.lastArgs.get("top_k"));
        assertEquals("年假", gateway.lastArgs.get("query"));
    }

    @Test
    @DisplayName("OK 但没有命中时返回 NO_HIT，提示未收录")
    void emptyResultBecomesNoHit() {
        FakeGateway gateway = gateway(true);
        gateway.result = "{\"status\":\"OK\",\"data\":{\"results\":[]}}";

        String reply = new KbQueryTools(gateway).search("年假", null, null, USER);

        assertTrue(reply.contains("NO_HIT"), reply);
        assertTrue(reply.contains("未收录"), reply);
    }

    @Test
    @DisplayName("FORBIDDEN 原样转述，不当作成功结果")
    void forbiddenIsRelayed() {
        FakeGateway gateway = gateway(true);
        gateway.result = "{\"status\":\"FORBIDDEN\",\"message\":\"无权访问该知识库\"}";

        String reply = new KbQueryTools(gateway).search("年假", null, null, USER);

        assertEquals(gateway.result, reply);
    }

    @Test
    @DisplayName("显式传库时只检索子集，默认 top_k 为 5")
    void honoursSubsetAndDefaultTopK() {
        FakeGateway gateway = gateway(true);
        KbQueryTools tools = new KbQueryTools(gateway);

        tools.search("年假", List.of(5L, 5L), null, USER);

        assertEquals(List.of(5L), gateway.lastArgs.get("series_ids"));
        assertEquals(KbQueryTools.DEFAULT_TOP_K, gateway.lastArgs.get("top_k"));
    }

    @Test
    @DisplayName("通道未启用或问题为空时返回明确错误")
    void disabledGatewayAndBlankQueryAreExplicit() {
        FakeGateway disabled = gateway(false);
        String disabledReply = new KbQueryTools(disabled).search("年假", null, null, USER);
        assertTrue(disabledReply.contains("未启用"), disabledReply);
        assertEquals(0, disabled.calls);

        FakeGateway gateway = gateway(true);
        String blank = new KbQueryTools(gateway).search("  ", null, null, USER);
        assertTrue(blank.contains("请输入要检索的问题"), blank);
        assertEquals(0, gateway.calls);
    }

    @Test
    @DisplayName("同一轮相同查询复用首次结果，只调用一次后端")
    void repeatedQueryReusesFirstResult() {
        FakeGateway gateway = gateway(true);
        gateway.result =
                """
                {"status":"OK","data":{"results":[{"series_id":3,"series_name":"制度",
                 "hits":[{"chunk_id":1,"document_id":2,"content":"年假 5 天"}]}]}}
                """;
        KbSearchBudget budget = new KbSearchBudget();
        RuntimeContext context = scoped(budget);
        KbQueryTools tools = new KbQueryTools(gateway);

        String first = tools.search("年假", null, null, context);
        String second = tools.search("  年假  ", null, KbQueryTools.DEFAULT_TOP_K, context);

        assertEquals(gateway.result, first);
        assertEquals(first, second);
        assertEquals(1, gateway.calls);
        assertEquals(1, budget.used());
    }

    @Test
    @DisplayName("同一轮 top_k 不同视为新请求，不复用缓存")
    void differentTopKUsesNewRequest() {
        FakeGateway gateway = gateway(true);
        RuntimeContext context = scoped(new KbSearchBudget());
        KbQueryTools tools = new KbQueryTools(gateway);

        tools.search("年假", null, 3, context);
        tools.search("年假", null, 6, context);

        assertEquals(2, gateway.calls);
    }

    @Test
    @DisplayName("同一轮不同查询最多执行 3 次，超出直接拒绝")
    void distinctQueriesStopAtLimit() {
        FakeGateway gateway = gateway(true);
        RuntimeContext context = scoped(new KbSearchBudget());
        KbQueryTools tools = new KbQueryTools(gateway);

        tools.search("年假", null, null, context);
        tools.search("病假", null, null, context);
        tools.search("加班", null, null, context);
        String denied = tools.search("婚假", null, null, context);

        assertEquals(KbSearchBudget.MAX_SEARCHES, gateway.calls);
        assertTrue(denied.contains("上限"), denied);
    }

    @Test
    @DisplayName("团队多个节点共享同一轮额度")
    void teamNodesShareBudget() {
        FakeGateway gateway = gateway(true);
        RuntimeContext context = scoped(new KbSearchBudget());
        KbQueryTools firstNode = new KbQueryTools(gateway);
        KbQueryTools secondNode = new KbQueryTools(gateway);

        firstNode.search("年假", null, null, context);
        firstNode.search("病假", null, null, context);
        secondNode.search("加班", null, null, context);
        String denied = secondNode.search("婚假", null, null, context);

        assertEquals(KbSearchBudget.MAX_SEARCHES, gateway.calls);
        assertTrue(denied.contains("上限"), denied);
    }

    private static final class FakeGateway implements KbSkillGateway {

        private final boolean enabled;
        String result = "{\"status\":\"OK\"}";
        int calls;
        String lastTool;
        Map<String, Object> lastArgs = Map.of();
        long lastUserId = -1L;

        FakeGateway(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public String call(String tool, Map<String, Object> arguments, long userId) {
            calls++;
            lastTool = tool;
            lastArgs = Map.copyOf(arguments);
            lastUserId = userId;
            return result;
        }

        @Override
        public Check check(String tool, Map<String, Object> arguments, long userId) {
            return Check.granted();
        }
    }
}
