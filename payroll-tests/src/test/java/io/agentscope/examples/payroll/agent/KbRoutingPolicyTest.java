package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KbRoutingPolicyTest {

    private static final KbRoutingPolicy.Options ENABLED =
            new KbRoutingPolicy.Options(true, true, false, true, 20);

    private static KbRoutingPolicy.Options enabledWithCandidate() {
        return new KbRoutingPolicy.Options(true, true, true, true, 20);
    }

    @Test
    @DisplayName("开关关闭时与 KbChatIntent 逐条一致")
    void disabledMatchesLegacyKeywordIntent() {
        List<String> questions =
                List.of(
                        "",
                        "你好",
                        "研发部年假怎么算？",
                        "报销流程是什么",
                        "核算 EMP001 2026-10 的工资",
                        "个税政策怎么算",
                        "Kafka 分区机制再简要说明一下");
        for (String question : questions) {
            KbRoutingPolicy.Result result =
                    KbRoutingPolicy.decide(
                            question, true, true, KbRoutingPolicy.Options.disabled());
            KbRoutingPolicy.Decision expected =
                    KbChatIntent.matches(question)
                            ? KbRoutingPolicy.Decision.FORCE_KEYWORD
                            : KbRoutingPolicy.Decision.NONE;
            assertEquals(expected, result.decision(), question);
            assertEquals(KbRoutingPolicy.Source.DISABLED, result.source(), question);
            assertFalse(result.actionGuard(), question);
        }
        assertEquals(
                KbRoutingPolicy.Decision.NONE,
                KbRoutingPolicy.decide(null, true, true, KbRoutingPolicy.Options.disabled())
                        .decision());
    }

    @Test
    @DisplayName("薪酬词一票终止，追问与绑定都不改变结论")
    void payrollAlwaysSkips() {
        KbRoutingPolicy.Result result =
                KbRoutingPolicy.decide(
                        "核算 EMP001 2026-10 的工资，再说明一下", true, true, enabledWithCandidate());
        assertEquals(KbRoutingPolicy.Decision.SKIP_PAYROLL, result.decision());
        assertEquals(KbRoutingPolicy.Source.PAYROLL, result.source());
        assertFalse(result.forced());
    }

    @Test
    @DisplayName("行动词只压制追问延续，不打断知识库管理链路")
    void actionGuardDoesNotTerminateKbAdmin() {
        KbRoutingPolicy.Result upload =
                KbRoutingPolicy.decide("上传文档到知识库", false, false, ENABLED);
        assertEquals(KbRoutingPolicy.Decision.FORCE_KEYWORD, upload.decision());
        assertTrue(upload.actionGuard());

        KbRoutingPolicy.Result rebuild =
                KbRoutingPolicy.decide("提交重建索引", false, false, ENABLED);
        assertEquals(KbRoutingPolicy.Decision.FORCE_KEYWORD, rebuild.decision());
        assertTrue(rebuild.actionGuard());
    }

    @Test
    @DisplayName("显式检索要求优先于行动词")
    void explicitWinsOverActionGuard() {
        KbRoutingPolicy.Result result =
                KbRoutingPolicy.decide("开始并按知识库回答 Kafka 分区机制", false, false, ENABLED);
        assertEquals(KbRoutingPolicy.Decision.FORCE_EXPLICIT, result.decision());
        assertEquals(KbRoutingPolicy.Source.EXPLICIT, result.source());
        assertTrue(result.actionGuard());
    }

    @Test
    @DisplayName("上一轮带引用 + 追问特征强制检索")
    void followUpAfterCitationsForcesRetrieval() {
        KbRoutingPolicy.Result result =
                KbRoutingPolicy.decide("Kafka 分区机制再简要说明一下", false, true, ENABLED);
        assertEquals(KbRoutingPolicy.Decision.FORCE_FOLLOW_UP, result.decision());
        assertEquals(KbRoutingPolicy.Source.FOLLOW_UP, result.source());

        KbRoutingPolicy.Result shortQuestion =
                KbRoutingPolicy.decide("具体呢", false, true, ENABLED);
        assertEquals(KbRoutingPolicy.Decision.FORCE_FOLLOW_UP, shortQuestion.decision());
    }

    @Test
    @DisplayName("行动指令压制追问延续")
    void actionGuardSuppressesFollowUp() {
        KbRoutingPolicy.Result result =
                KbRoutingPolicy.decide("开始生成视频", false, true, ENABLED);
        assertEquals(KbRoutingPolicy.Decision.NONE, result.decision());
        assertEquals(KbRoutingPolicy.Source.ACTION_GUARD, result.source());
        assertTrue(result.actionGuard());
    }

    @Test
    @DisplayName("引用后的闲聊或换话题不改道")
    void chitChatAfterCitationsStaysPut() {
        assertEquals(
                KbRoutingPolicy.Decision.NONE,
                KbRoutingPolicy.decide("今天天气怎么样", false, true, ENABLED).decision());
        assertEquals(
                KbRoutingPolicy.Decision.NONE,
                KbRoutingPolicy.decide("你好", false, true, ENABLED).decision());
    }

    @Test
    @DisplayName("绑定只在开启候选且属于追问/短问题时生效")
    void boundCandidateIsOptIn() {
        assertEquals(
                KbRoutingPolicy.Decision.CANDIDATE_BOUND,
                KbRoutingPolicy.decide("具体呢", true, false, enabledWithCandidate()).decision());
        assertEquals(
                KbRoutingPolicy.Decision.NONE,
                KbRoutingPolicy.decide("具体呢", true, false, ENABLED).decision());
        assertEquals(
                KbRoutingPolicy.Decision.NONE,
                KbRoutingPolicy.decide("生成一段视频", true, false, enabledWithCandidate())
                        .decision());
    }
}
