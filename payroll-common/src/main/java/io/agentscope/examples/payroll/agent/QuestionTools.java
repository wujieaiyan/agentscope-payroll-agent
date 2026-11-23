package io.agentscope.examples.payroll.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 让模型把“需要用户逐项拍板或补充信息”的诉求变成可提交的选择卡片，而不是正文清单。
 *
 * <p>与 {@link ConfirmationTools} 一样没有服务端副作用：调用只生成提案；用户在卡片上逐项选择后
 * 提交，执行器把答案拼成 {@code followup}，前端作为新一轮用户输入发送。
 */
public final class QuestionTools {

    public static final String REQUEST_INPUT = "request_input";

    private static final int MAX_QUESTIONS = 8;
    private static final int MAX_OPTIONS = 8;
    private static final int MAX_QUESTION_CHARS = 300;
    private static final int MAX_OPTION_CHARS = 80;
    private static final int MAX_ANSWER_CHARS = 500;

    /** 卡片模板自带序号，模型若也写了“1. ”会重复，归一化时去掉前缀序号。 */
    private static final Pattern LEADING_INDEX = Pattern.compile("^\\s*\\d{1,2}\\s*[.、)）:：]\\s*");

    /**
     * 卡片问题定义。字段名即 JSON 字段名，避免嵌套类型经过 Schema 生成/反序列化时被改名。
     */
    public record Question(
            String id,
            String question,
            List<String> options,
            Boolean multiple,
            Boolean allow_free_text,
            String default_value) {}

    @Tool(
            name = REQUEST_INPUT,
            description =
                    "当需要用户在界面上逐项拍板、在多个方案间选择或补充必须由用户决定的信息时调用。"
                            + "把每个待决问题拆成 questions 数组：question 为问题文本；options 为可选项；"
                            + "multiple 表示是否多选；allow_free_text 表示是否允许自由输入；"
                            + "default_value 为默认选项。title 是卡片标题。"
                            + "调用后停止输出，等待用户提交；禁止在正文里列出“以下 N 项需要你拍板”的清单。",
            readOnly = false)
    public String requestInput(
            @ToolParam(
                            name = "title",
                            description = "可选，卡片标题，例如：开始生成前需要你拍板的 6 个问题",
                            required = false)
                    String title,
            @ToolParam(
                            name = "questions",
                            description =
                                    "问题数组，每项包含 question（必填）、options（可选项，可空）、"
                                            + "multiple（是否多选）、allow_free_text（是否允许自由输入）、"
                                            + "default_value（默认选中项）")
                    List<Question> questions,
            RuntimeContext context) {
        List<Map<String, Object>> normalized = normalize(questions);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("kind", "question");
        args.put("title", cardTitle(title, normalized.size()));
        args.put("questions", normalized);
        ActionGuard guard = context == null ? null : context.get(ActionGuard.class);
        String pending =
                guard == null
                        ? null
                        : guard.intercept(REQUEST_INPUT, args, false, false, false);
        if (pending != null) {
            return pending;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "PENDING_CONFIRM");
        payload.put("message", "该请求需要用户在会话内逐项选择，未提交前不会继续。");
        return JsonSupport.toJson(payload);
    }

    /** 把卡片问题与用户答案拼成用户口吻的下一轮输入。 */
    public static String followupText(Map<String, Object> params) {
        if (params == null) {
            return "";
        }
        String title = text(params.get("title"), "待决事项");
        List<Map<String, Object>> questions = questionMaps(params.get("questions"));
        Map<String, Object> answers = answers(params.get("answers"));
        StringBuilder text = new StringBuilder();
        text.append("关于「").append(title).append("」，我的选择：");
        int index = 0;
        for (Map<String, Object> question : questions) {
            index++;
            String id = text(question.get("id"), "q" + index);
            text.append('\n')
                    .append(index)
                    .append(". ")
                    .append(text(question.get("question"), "问题 " + index))
                    .append("：")
                    .append(answerText(answers.get(id)));
        }
        if (index == 0) {
            return "";
        }
        text.append("\n请按以上选择继续。");
        return text.toString();
    }

    private static List<Map<String, Object>> normalize(List<Question> questions) {
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("questions 至少需要一个条目");
        }
        if (questions.size() > MAX_QUESTIONS) {
            throw new IllegalArgumentException("questions 最多 " + MAX_QUESTIONS + " 个条目");
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        Set<String> usedIds = new LinkedHashSet<>();
        for (int i = 0; i < questions.size(); i++) {
            Question source = questions.get(i);
            if (source == null) {
                throw new IllegalArgumentException("questions 第 " + (i + 1) + " 项为空");
            }
            String question = clip(source.question(), MAX_QUESTION_CHARS);
            String withoutIndex = LEADING_INDEX.matcher(question).replaceFirst("");
            if (!withoutIndex.isEmpty()) {
                question = withoutIndex;
            }
            if (question.isEmpty()) {
                throw new IllegalArgumentException("questions 第 " + (i + 1) + " 项缺少 question");
            }
            List<String> options = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            if (source.options() != null) {
                for (String option : source.options()) {
                    String value = clip(option, MAX_OPTION_CHARS);
                    if (!value.isEmpty() && seen.add(value)) {
                        options.add(value);
                    }
                    if (options.size() >= MAX_OPTIONS) {
                        break;
                    }
                }
            }
            String id = uniqueId(source.id(), "q" + (i + 1), usedIds);
            boolean multiple = Boolean.TRUE.equals(source.multiple());
            boolean allowFreeText = Boolean.TRUE.equals(source.allow_free_text()) || options.isEmpty();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("question", question);
            if (!options.isEmpty()) {
                item.put("options", options);
            }
            item.put("multiple", multiple);
            item.put("allow_free_text", allowFreeText);
            String defaultValue = clip(source.default_value(), MAX_OPTION_CHARS);
            if (!defaultValue.isEmpty()) {
                item.put("default_value", defaultValue);
            }
            normalized.add(item);
        }
        return normalized;
    }

    private static String cardTitle(String title, int count) {
        String text = clip(title, 120);
        if (!text.isEmpty()) {
            return text;
        }
        return count == 1 ? "需要你确认 1 个问题" : "需要你逐项确认 " + count + " 个问题";
    }

    private static String uniqueId(String id, String fallback, Set<String> used) {
        String base = sanitizeId(id);
        if (base.isEmpty()) {
            base = fallback;
        }
        String candidate = base;
        int suffix = 2;
        while (!used.add(candidate)) {
            candidate = base + "_" + suffix;
            suffix++;
        }
        return candidate;
    }

    private static String sanitizeId(String id) {
        if (id == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (char ch : id.trim().toCharArray()) {
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') {
                text.append(ch);
            }
            if (text.length() >= 40) {
                break;
            }
        }
        return text.toString();
    }

    private static List<Map<String, Object>> questionMaps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(value instanceof Iterable<?> iterable)) {
            return result;
        }
        for (Object item : iterable) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, entry) -> copy.put(String.valueOf(key), entry));
                result.add(copy);
            }
        }
        return result;
    }

    private static Map<String, Object> answers(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> map)) {
            return result;
        }
        map.forEach((key, entry) -> result.put(String.valueOf(key), entry));
        return result;
    }

    private static String answerText(Object value) {
        List<String> parts = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String text = clip(item == null ? null : String.valueOf(item), MAX_ANSWER_CHARS);
                if (!text.isEmpty()) {
                    parts.add(text);
                }
            }
        } else if (value != null) {
            String text = clip(String.valueOf(value), MAX_ANSWER_CHARS);
            if (!text.isEmpty()) {
                parts.add(text);
            }
        }
        return parts.isEmpty() ? "（未填写）" : String.join("；", parts);
    }

    private static String text(Object value, String fallback) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    public QuestionTools() {}
}
