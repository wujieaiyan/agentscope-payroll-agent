package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KbContextPrefetcherTest {

    private static final KbChatSettings SETTINGS =
            new KbChatSettings(true, true, 3, 200, 900, 0.0d, 1, 2000L, false, false);

    private static final String HIT_RESPONSE =
            "{\"status\":\"OK\",\"data\":{\"results\":[{\"series_id\":1,\"series_name\":\"制度库\","
                    + "\"hits\":[{\"chunk_id\":101,\"chunk_seq\":1,\"document_id\":1,"
                    + "\"document_name\":\"员工手册\",\"page_no\":3,\"title_path\":\"休假\","
                    + "\"score\":0.9,\"routes\":[\"bm25\"],\"content\":\"年假 5 天\"}]}]}}";

    @Test
    @DisplayName("预取通过 kb_search 只发起一次，参数携带查询、限定库与 top_k")
    void callsKbSearchOnceWithArguments() {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        KbContextPrefetcher prefetcher =
                new KbContextPrefetcher(
                        new StubGateway(true, HIT_RESPONSE, captured), SETTINGS);

        KbContextPrefetcher.Result result =
                prefetcher.prefetch("年假政策", "", List.of(7L), 42L, false);

        assertTrue(result.attempted());
        assertEquals(KbContextPacket.STATUS_OK, result.status());
        assertEquals(1, result.hits());
        assertEquals(List.of(7L), captured.get().get("series_ids"));
        assertEquals(Integer.valueOf(3), captured.get().get("top_k"));
        assertEquals("年假政策", captured.get().get("query"));
        assertTrue(result.material().contains("年假 5 天"));
        assertEquals(1, result.citations().size());
    }

    @Test
    @DisplayName("超时后按截止时间返回 timeout，不阻塞回答")
    void timeoutReturnsPromptly() {
        KbContextPrefetcher shortDeadline =
                new KbContextPrefetcher(
                        new StubGateway(true, HIT_RESPONSE, null) {
                            @Override
                            public String call(
                                    String tool,
                                    Map<String, Object> arguments,
                                    long userId,
                                    Duration timeout) {
                                try {
                                    Thread.sleep(1500L);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                return HIT_RESPONSE;
                            }
                        },
                        new KbChatSettings(true, true, 3, 200, 900, 0.0d, 1, 100L, false, false));

        long startedAt = System.currentTimeMillis();
        KbContextPrefetcher.Result result =
                shortDeadline.prefetch("年假政策", "", List.of(), 42L, false);

        assertTrue(result.attempted());
        assertEquals(KbContextPacket.STATUS_TIMEOUT, result.status());
        assertFalse(result.injected());
        assertTrue(System.currentTimeMillis() - startedAt < 1400L);
    }

    @Test
    @DisplayName("开关关闭或通道不可用时不发起检索")
    void disabledDoesNotCall() {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        KbContextPrefetcher off =
                new KbContextPrefetcher(
                        new StubGateway(true, HIT_RESPONSE, captured),
                        new KbChatSettings(true, false, 3, 200, 900, 0.0d, 1, 2000L, false, false));
        assertFalse(off.enabled());
        assertFalse(off.prefetch("年假政策", "", List.of(), 42L, false).attempted());

        KbContextPrefetcher noGateway =
                new KbContextPrefetcher(new StubGateway(false, HIT_RESPONSE, captured), SETTINGS);
        assertFalse(noGateway.prefetch("年假政策", "", List.of(), 42L, false).attempted());
    }

    @Test
    @DisplayName("通道异常折成 error，不向外抛")
    void gatewayFailureDegrades() {
        KbContextPrefetcher prefetcher =
                new KbContextPrefetcher(
                        new StubGateway(true, null, null) {
                            @Override
                            public String call(
                                    String tool, Map<String, Object> arguments, long userId) {
                                throw new IllegalStateException("内部通道不可用");
                            }
                        },
                        SETTINGS);

        KbContextPrefetcher.Result result =
                prefetcher.prefetch("年假政策", "", List.of(), 42L, false);

        assertTrue(result.attempted());
        assertEquals(KbContextPacket.STATUS_ERROR, result.status());
        assertFalse(result.injected());
    }

    @Test
    @DisplayName("短问与指代开头的追问补充上一轮问题，整句不拼接")
    void enrichesOnlyReferentialFollowUps() {
        assertEquals(
                "陪产假有几天；那陪产假呢",
                KbContextPrefetcher.buildQuery("那陪产假呢", "陪产假有几天"));
        assertEquals(
                "年假 5 天怎么计算工资基数",
                KbContextPrefetcher.buildQuery("年假 5 天怎么计算工资基数", "年假有几天"));
        assertEquals("还有呢", KbContextPrefetcher.buildQuery("还有呢", ""));
        assertEquals(
                "陪产假有几天；那需要提交哪些材料给人事部门",
                KbContextPrefetcher.buildQuery("那需要提交哪些材料给人事部门", "陪产假有几天"));
    }

    @Test
    @DisplayName("创作类智能体按名称、描述与标签识别")
    void detectsCreativeAgents() {
        assertTrue(KbContextPrefetcher.isCreativeAgent(agent("诗词专家", "写诗与鉴赏", List.of())));
        assertTrue(
                KbContextPrefetcher.isCreativeAgent(
                        agent("助手", "文案与小说", List.of("写作"))));
        assertFalse(KbContextPrefetcher.isCreativeAgent(agent("制度专家", "休假制度", List.of("kb"))));
        assertFalse(KbContextPrefetcher.isCreativeAgent(null));
    }

    private static AgentDefinition agent(String name, String description, List<String> tags) {
        return new AgentDefinition(
                "a-1",
                name,
                null,
                description,
                tags,
                List.of(),
                AgentDefinition.VISIBILITY_PUBLIC,
                AgentDefinition.STATUS_ONLINE,
                "tester",
                null,
                null);
    }

    private static class StubGateway implements KbSkillGateway {

        private final boolean enabled;
        private final String response;
        private final AtomicReference<Map<String, Object>> captured;

        StubGateway(
                boolean enabled, String response, AtomicReference<Map<String, Object>> captured) {
            this.enabled = enabled;
            this.response = response;
            this.captured = captured;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public String call(String tool, Map<String, Object> arguments, long userId) {
            if (captured != null) {
                captured.set(arguments);
            }
            return response;
        }

        @Override
        public String call(
                String tool, Map<String, Object> arguments, long userId, Duration timeout) {
            return call(tool, arguments, userId);
        }

        @Override
        public Check check(String tool, Map<String, Object> arguments, long userId) {
            return Check.granted();
        }
    }
}
