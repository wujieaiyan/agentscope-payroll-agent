package io.agentscope.examples.payroll.agent;

import java.util.List;
import java.util.Locale;

/**
 * 绑定知识库后判断问题是否应走知识库检索。
 *
 * <p>薪酬数据类问题优先：只要出现工资/核算/个税等词，即使同时提到“政策”也不进入 KB 路由，
 * 避免把工资计算拉进制度问答；其余命中制度、流程、条款、休假、报销等关键词的按 KB 问题处理。
 */
public final class KbChatIntent {

    private static final List<String> PAYROLL_MARKERS =
            List.of(
                    "工资", "薪资", "薪酬", "核算", "个税", "社保", "公积金", "计算", "应发", "实发",
                    "扣款", "工资条", "发薪", "发放", "打款", "入账");

    private static final List<String> KB_MARKERS =
            List.of(
                    "知识库", "制度", "规定", "条款", "手册", "指南", "流程", "政策", "年假", "请假",
                    "休假", "报销", "差旅", "考勤", "加班", "调休", "入职", "离职", "转正", "培训",
                    "福利", "权限", "目录", "分块", "检索", "召回", "topk", "top k", "rerank",
                    "重建索引", "解析任务", "入库任务");

    private KbChatIntent() {
    }

    public static boolean matches(String question) {
        String text = question == null ? "" : question.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return false;
        }
        if (containsAny(text, PAYROLL_MARKERS)) {
            return false;
        }
        return containsAny(text, KB_MARKERS);
    }

    private static boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
