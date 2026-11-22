package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KbChatIntentTest {

    @Test
    @DisplayName("制度、流程、休假类问题进入知识库路由")
    void matchesInstitutionQuestions() {
        assertTrue(KbChatIntent.matches("研发部年假怎么算？"));
        assertTrue(KbChatIntent.matches("报销流程是什么"));
        assertTrue(KbChatIntent.matches("考勤制度有哪些要求"));
        assertTrue(KbChatIntent.matches("知识库怎么建"));
    }

    @Test
    @DisplayName("工资与核算类问题优先走薪酬链路，不进入知识库路由")
    void payrollQuestionsStayPayroll() {
        assertFalse(KbChatIntent.matches("核算 EMP001 2026-10 的工资"));
        assertFalse(KbChatIntent.matches("个税政策怎么算"));
        assertFalse(KbChatIntent.matches("社保公积金扣款明细"));
        assertFalse(KbChatIntent.matches("你好"));
        assertFalse(KbChatIntent.matches(""));
        assertFalse(KbChatIntent.matches(null));
    }

    @Test
    @DisplayName("文学创作请求不因主题词命中而强制检索")
    void creativeRequestsDoNotForceKnowledgeBase() {
        assertFalse(KbChatIntent.matches("写一首关于加班的诗词"));
        assertFalse(KbChatIntent.matches("帮我写一段加班的歌词"));
        assertFalse(KbChatIntent.matches("以考勤为主题写一篇小说"));
        assertTrue(KbChatIntent.matches("加班制度是怎么规定的"));
        assertTrue(KbChatIntent.matches("请根据制度写一首关于年假的诗"));
    }
}
