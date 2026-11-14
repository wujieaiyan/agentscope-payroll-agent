package io.agentscope.examples.payroll.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SemanticCachePolicyTest {

    @Test
    @DisplayName("AGENT 路径无工具调用才可缓存")
    void cachesAgentAnswersWithoutTools() {
        assertTrue(SemanticCachePolicy.cacheable("AGENT", List.of(), false, "答案"));
        assertFalse(SemanticCachePolicy.cacheable("PIPELINE", List.of(), false, "答案"));
        assertFalse(SemanticCachePolicy.cacheable("FLOW", List.of(), false, "答案"));
        assertFalse(SemanticCachePolicy.cacheable("TEAM_ACTIVE", List.of(), false, "答案"));
        assertFalse(
                SemanticCachePolicy.cacheable("AGENT", List.of("get_payroll_policy"), false, "答案"));
    }

    @Test
    @DisplayName("提案、空答复与超长答复不缓存")
    void skipsProposalAndInvalidAnswers() {
        assertFalse(SemanticCachePolicy.cacheable("AGENT", List.of(), true, "答案"));
        assertFalse(SemanticCachePolicy.cacheable("AGENT", List.of(), false, ""));
        assertFalse(SemanticCachePolicy.cacheable("AGENT", List.of(), false, "   "));
        assertFalse(SemanticCachePolicy.cacheable("AGENT", List.of(), false, null));
        assertFalse(
                SemanticCachePolicy.cacheable("AGENT", List.of(), false, "答".repeat(4001)));
    }

    @Test
    @DisplayName("问题归一会去掉空白、标点并统一大小写")
    void normalizesQuestion() {
        assertEquals("社保基数怎么定", SemanticCacheService.normalize(" 社保基数，怎么定？ "));
        assertEquals("payrollpolicy", SemanticCacheService.normalize("Payroll Policy!"));
        assertEquals(
                SemanticCacheService.questionHash("s1", "社保基数怎么定"),
                SemanticCacheService.questionHash("s1", " 社保基数, 怎么定。"));
        assertNotEquals(
                SemanticCacheService.questionHash("s1", "社保基数怎么定"),
                SemanticCacheService.questionHash("s2", "社保基数怎么定"));
    }

    @Test
    @DisplayName("权限指纹区分部门与权限集合")
    void fingerprintSeparatesPermissions() {
        String admin = io.agentscope.examples.payroll.service.SemanticCacheContext.fingerprint(
                1L, Set.of("chat:use", "payroll:read"));
        String same = io.agentscope.examples.payroll.service.SemanticCacheContext.fingerprint(
                1L, Set.of("payroll:read", "chat:use"));
        String otherDept = io.agentscope.examples.payroll.service.SemanticCacheContext.fingerprint(
                2L, Set.of("chat:use", "payroll:read"));
        String less = io.agentscope.examples.payroll.service.SemanticCacheContext.fingerprint(
                1L, Set.of("chat:use"));

        assertEquals(admin, same);
        assertNotEquals(admin, otherDept);
        assertNotEquals(admin, less);
    }

    @Test
    @DisplayName("关闭状态下永远未命中且不保存")
    void disabledCacheNeverHits() {
        AnswerCache cache = AnswerCache.disabled();
        assertFalse(cache.enabled());
        cache.store("scope", "问题", "答案");
        assertTrue(cache.lookup("scope", "问题").isEmpty());
    }
}
