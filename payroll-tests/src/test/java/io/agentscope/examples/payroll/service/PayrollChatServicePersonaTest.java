package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.ChatContextSnapshot;
import io.agentscope.examples.payroll.domain.ChatMessage;
import io.agentscope.examples.payroll.domain.PayrollPolicy;
import io.agentscope.examples.payroll.memory.VectorMemoryService;
import io.agentscope.examples.payroll.repository.ChatContextStore;
import io.agentscope.examples.payroll.repository.JsonPayrollRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayrollChatServicePersonaTest {

    private final PayrollEngine engine =
            new PayrollEngine(JsonPayrollRepository.demo(), PayrollPolicy.demoPolicy());

    @Test
    @DisplayName("首次绑定角色不清空上下文，切换角色时清空并重写角色键")
    void switchingPersonaResetsConversationContext() {
        PersonaStore store = new PersonaStore();
        PayrollChatService service =
                new PayrollChatService(engine, store, 11, 7, false, VectorMemoryService.disabled());

        service.applyPersona("flow:emotion", ChatStreamListener.NOOP);
        assertEquals("flow:emotion", store.persona);
        assertTrue(store.saved.isEmpty(), "首次绑定角色不应清空刚加载的上下文");

        service.applyPersona("flow:payroll", ChatStreamListener.NOOP);
        assertEquals("flow:payroll", store.persona);
        assertEquals(1, store.saved.size());
        assertEquals(ChatContextSnapshot.EMPTY, store.saved.get(0));

        service.applyPersona("flow:payroll", ChatStreamListener.NOOP);
        assertEquals(1, store.saved.size(), "角色未变化时不应重复清空上下文");
    }

    @Test
    @DisplayName("没有会话 id 的 CLI 场景不读写角色绑定")
    void personaIsSkippedWithoutSessionId() {
        PersonaStore store = new PersonaStore();
        PayrollChatService service =
                new PayrollChatService(engine, store, 0, 0, false, VectorMemoryService.disabled());

        service.applyPersona("flow:emotion", ChatStreamListener.NOOP);

        assertEquals("", store.persona);
        assertTrue(store.saved.isEmpty());
    }

    private static final class PersonaStore implements ChatContextStore {

        private String persona = "";
        private final List<ChatContextSnapshot> saved = new ArrayList<>();

        @Override
        public ChatContextSnapshot loadContext(long sessionId) {
            return ChatContextSnapshot.EMPTY;
        }

        @Override
        public void saveContext(long sessionId, ChatContextSnapshot snapshot) {
            saved.add(snapshot);
        }

        @Override
        public List<ChatMessage> messages(long sessionId) {
            return List.of();
        }

        @Override
        public String persona(long sessionId) {
            return persona;
        }

        @Override
        public void savePersona(long sessionId, String persona) {
            this.persona = persona;
        }
    }
}
