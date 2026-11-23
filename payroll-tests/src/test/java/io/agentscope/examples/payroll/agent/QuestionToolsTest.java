package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.examples.payroll.domain.ActionProposal;
import io.agentscope.examples.payroll.service.ChatStreamListener;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QuestionToolsTest {

    private final List<ActionProposal> events = new ArrayList<>();

    private final ChatStreamListener listener =
            new ChatStreamListener() {
                @Override
                public void onActionProposal(ActionProposal proposal) {
                    events.add(proposal);
                }
            };

    @Test
    @DisplayName("生成选择题卡片：补齐 id、去重选项、无选项时允许自由填写")
    void buildsCardWithNormalizedQuestions() {
        ActionGuard guard = new ActionGuard(7L, 1001L, "AGENT", false, 15, ToolSafety.defaults(), listener);

        String result =
                new QuestionTools()
                        .requestInput(
                                "开始生成前需要你拍板",
                                List.of(
                                        new QuestionTools.Question(
                                                null,
                                                "1. 工资批次口径",
                                                List.of("按自然月", "按自然月", " "),
                                                false,
                                                false,
                                                "按自然月"),
                                        new QuestionTools.Question(
                                                "x", "补充说明", List.of(), null, null, null)),
                                context(guard));

        Map<String, Object> payload =
                JsonSupport.fromJson(result, new TypeReference<Map<String, Object>>() {});
        assertEquals("PENDING_CONFIRM", payload.get("status"));
        ActionProposal proposal = guard.firstProposal();
        assertNotNull(proposal);
        assertEquals("request_input", proposal.toolName());
        assertEquals("开始生成前需要你拍板", proposal.summary());
        assertEquals(1, events.size());
        assertTrue(events.get(0).paramsJson().contains("\"kind\":\"question\""));

        List<Map<String, Object>> questions = questions(proposal);
        assertEquals(2, questions.size());
        assertEquals("q1", questions.get(0).get("id"));
        assertEquals("工资批次口径", questions.get(0).get("question"));
        assertEquals(List.of("按自然月"), questions.get(0).get("options"));
        assertEquals(false, questions.get(0).get("allow_free_text"));
        assertEquals("按自然月", questions.get(0).get("default_value"));
        assertEquals("x", questions.get(1).get("id"));
        assertEquals(true, questions.get(1).get("allow_free_text"));
        assertFalse(questions.get(1).containsKey("options"));
    }

    @Test
    @DisplayName("重复 id 自动加后缀，空问题直接拒绝")
    void dedupesIdsAndRejectsEmptyQuestion() {
        ActionGuard guard = new ActionGuard(7L, 1001L, "AGENT", false, 15, ToolSafety.defaults(), listener);
        QuestionTools tools = new QuestionTools();

        tools.requestInput(
                "",
                List.of(
                        new QuestionTools.Question("dup", "问题一", List.of("A"), null, false, null),
                        new QuestionTools.Question("dup", "问题二", List.of("B"), null, false, null)),
                context(guard));
        assertEquals("需要你逐项确认 2 个问题", guard.firstProposal().summary());
        List<Map<String, Object>> questions = questions(guard.firstProposal());
        assertEquals("dup", questions.get(0).get("id"));
        assertEquals("dup_2", questions.get(1).get("id"));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        tools.requestInput(
                                "空问题",
                                List.of(new QuestionTools.Question(null, "  ", List.of(), null, null, null)),
                                context(guard)));
    }

    @Test
    @DisplayName("没有 ActionGuard 时仍返回待确认提示，不抛异常")
    void fallsBackWithoutGuard() {
        String result =
                new QuestionTools()
                        .requestInput(
                                "标题",
                                List.of(new QuestionTools.Question("q1", "问题", List.of("A"), false, false, null)),
                                null);

        Map<String, Object> payload =
                JsonSupport.fromJson(result, new TypeReference<Map<String, Object>>() {});
        assertEquals("PENDING_CONFIRM", payload.get("status"));
        assertTrue(String.valueOf(payload.get("message")).contains("未提交前不会继续"));
    }

    @Test
    @DisplayName("作答拼成用户口吻的 followup，多选按分号连接，缺失标注未填写")
    void buildsFollowupFromAnswers() {
        Map<String, Object> params =
                Map.of(
                        "title", "开始生成前需要你拍板",
                        "questions",
                                List.of(
                                        Map.of(
                                                "id", "q1",
                                                "question", "工资批次口径",
                                                "options", List.of("按自然月", "按考勤周期")),
                                        Map.of("id", "q2", "question", "补充说明")),
                        "answers", Map.of("q1", List.of("按自然月", "按考勤周期")));

        String followup = QuestionTools.followupText(params);

        assertTrue(followup.startsWith("关于「开始生成前需要你拍板」，我的选择："));
        assertTrue(followup.contains("1. 工资批次口径：按自然月；按考勤周期"));
        assertTrue(followup.contains("2. 补充说明：（未填写）"));
        assertTrue(followup.endsWith("请按以上选择继续。"));
        assertEquals("", QuestionTools.followupText(Map.of()));
        assertEquals("", QuestionTools.followupText(null));
    }

    private static RuntimeContext context(ActionGuard guard) {
        return RuntimeContext.builder()
                .userId("7")
                .sessionId("1001")
                .put(ActionGuard.class, guard)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> questions(ActionProposal proposal) {
        Map<String, Object> params =
                JsonSupport.fromJson(
                        proposal.paramsJson(), new TypeReference<Map<String, Object>>() {});
        return (List<Map<String, Object>>) params.get("questions");
    }
}
