package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.examples.payroll.service.KbSkillGateway;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KbToolsTest {

    private static final RuntimeContext USER =
            RuntimeContext.builder().userId("7").sessionId("s-1").build();

    @Test
    @DisplayName("只读工具直接执行，不做权限预检")
    void readToolSkipsPrecheck() {
        FakeGateway gateway = new FakeGateway(true);
        gateway.result = "{\"status\":\"OK\",\"data\":[]}";
        KbTools tools = new KbTools(gateway);

        String reply = tools.listLibraries(null, null, null, null, null, USER);

        assertEquals(gateway.result, reply);
        assertEquals(1, gateway.calls);
        assertEquals(0, gateway.checks);
        assertEquals(KbTools.LIST_LIBRARIES, gateway.lastTool);
        assertEquals(7L, gateway.lastUserId);
    }

    @Test
    @DisplayName("写工具预检被拒时不生成提案也不执行")
    void deniedWriteDoesNotExecute() {
        FakeGateway gateway = new FakeGateway(true);
        gateway.checkResult = KbSkillGateway.Check.denied("当前账号无权管理该知识库");
        KbTools tools = new KbTools(gateway);

        String reply =
                tools.saveConfig(3L, 8, null, null, null, null, null, null, USER);

        assertTrue(reply.contains("\"status\":\"FORBIDDEN\""));
        assertTrue(reply.contains("当前账号无权管理该知识库"));
        assertEquals(1, gateway.checks);
        assertEquals(0, gateway.calls);
    }

    @Test
    @DisplayName("写工具预检通过后交由提案或执行通道")
    void grantedWriteReachesGateway() {
        FakeGateway gateway = new FakeGateway(true);
        gateway.result = "{\"status\":\"OK\"}";
        KbTools tools = new KbTools(gateway);

        String reply = tools.rebuildIndex(3L, "FULL", null, USER);

        assertEquals(gateway.result, reply);
        assertEquals(1, gateway.checks);
        assertEquals(1, gateway.calls);
        assertEquals("FULL", gateway.lastArgs.get("mode"));
    }

    @Test
    @DisplayName("通道未启用或缺少用户身份时返回明确错误")
    void disabledGatewayAndMissingUserAreExplicit() {
        FakeGateway disabled = new FakeGateway(false);
        KbTools tools = new KbTools(disabled);

        assertTrue(tools.listLibraries(null, null, null, null, null, USER).contains("未启用"));
        assertEquals(0, disabled.calls);

        FakeGateway gateway = new FakeGateway(true);
        String missingUser =
                new KbTools(gateway)
                        .listLibraries(
                                null,
                                null,
                                null,
                                null,
                                null,
                                RuntimeContext.builder().sessionId("s-1").build());
        assertTrue(missingUser.contains("缺少用户身份"));
        assertEquals(0, gateway.calls);
    }

    static final class FakeGateway implements KbSkillGateway {

        private final boolean enabled;
        private String result = "{\"status\":\"OK\"}";
        private KbSkillGateway.Check checkResult = KbSkillGateway.Check.granted();
        private int calls;
        private int checks;
        private String lastTool;
        private Map<String, Object> lastArgs = Map.of();
        private long lastUserId = -1L;

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
            checks++;
            lastTool = tool;
            lastArgs = Map.copyOf(arguments);
            lastUserId = userId;
            return checkResult;
        }
    }
}
