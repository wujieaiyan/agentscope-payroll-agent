package io.agentscope.examples.payroll.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 让模型把“需要用户点头才能继续”的诉求变成会话内卡片，而不是在正文里问一句。
 *
 * <p>工具本身没有副作用：调用只触发 {@link ActionGuard} 生成提案；确认后由执行器返回
 * {@code followup}，前端把它作为新一轮用户输入发送，对话才会继续。
 */
public final class ConfirmationTools {

    public static final String REQUEST_CONFIRMATION = "request_confirmation";

    @Tool(
            name = REQUEST_CONFIRMATION,
            description =
                    "当你需要用户确认或授权后才能继续时调用；需要用户逐项选择或补充信息时改用 request_input。"
                            + "summary 是卡片标题（用户看到的一句话）；detail 说明将要做什么；"
                            + "followup 是用户确认后自动发送的下一句输入，应写成用户口吻且自包含。"
                            + "调用后停止输出，等待用户点击确认；不要把同样的请求再用正文问一遍。",
            readOnly = false)
    public String requestConfirmation(
            @ToolParam(name = "summary", description = "一句话说明需要用户确认什么")
                    String summary,
            @ToolParam(
                            name = "detail",
                            description = "可选，操作细节与影响，例如将创建什么、改动哪些数据",
                            required = false)
                    String detail,
            @ToolParam(
                            name = "followup",
                            description = "可选，用户确认后自动发送的下一句输入（用户口吻、自包含）",
                            required = false)
                    String followup,
            RuntimeContext context) {
        Map<String, Object> args = new LinkedHashMap<>();
        String title = summary == null || summary.isBlank() ? "需要你确认后继续" : summary.trim();
        args.put("summary", title);
        if (detail != null && !detail.isBlank()) {
            args.put("detail", detail.trim());
        }
        if (followup != null && !followup.isBlank()) {
            args.put("followup", followup.trim());
        }
        ActionGuard guard = context == null ? null : context.get(ActionGuard.class);
        String pending =
                guard == null
                        ? null
                        : guard.intercept(REQUEST_CONFIRMATION, args, false, false, false);
        if (pending != null) {
            return pending;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "CONFIRMED");
        payload.put("followup", args.getOrDefault("followup", ""));
        return JsonSupport.toJson(payload);
    }
}
