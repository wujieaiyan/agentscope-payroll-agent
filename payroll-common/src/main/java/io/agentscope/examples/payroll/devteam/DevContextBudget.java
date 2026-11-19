package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.domain.PayrollException;

/**
 * 编码面上下文预算（P0-9）：按有效上下文推导压缩触发点、保留量与工具结果淘汰阈值。
 *
 * <p>输出预算优先保留：先扣掉 {@code numPredict} 给生成留位，再按可用窗口的 80% 触发压缩、
 * 25% 保留最近消息；工具结果超过 {@link #toolResultMaxChars()} 时只留预览，避免单次
 * 读取把整个窗口挤爆。
 *
 * @param maxContextTokens 模型有效上下文（与 Ollama {@code num_ctx} 对齐）
 * @param compactionTriggerTokens 达到该 token 数触发压缩
 * @param compactionKeepTokens 压缩后保留的最近内容 token 上限
 * @param compactionTriggerMessages 达到该消息条数也触发压缩
 * @param toolResultMaxChars 工具结果落盘淘汰阈值（字符）
 * @param toolResultPreviewChars 淘汰后在上下文里保留的预览字符数
 */
public record DevContextBudget(
        int maxContextTokens,
        int compactionTriggerTokens,
        int compactionKeepTokens,
        int compactionTriggerMessages,
        int toolResultMaxChars,
        int toolResultPreviewChars) {

    public static final int MIN_USABLE_TOKENS = 4_096;
    public static final int DEFAULT_TRIGGER_MESSAGES = 40;
    public static final int DEFAULT_TOOL_RESULT_MAX_CHARS = 24_000;
    public static final int DEFAULT_TOOL_RESULT_PREVIEW_CHARS = 2_000;

    /** 压缩摘要只保留研发任务继续执行所需的信息。 */
    public static final String SUMMARY_PROMPT =
            """
            你正在压缩一个研发任务的上下文。请提取并能直接继续工作的信息：
            1. 任务目标、接口/数据契约与验收口径；
            2. 已修改或读取的文件路径与关键改动（含函数名、行号）；
            3. 已执行命令及成功/失败原因；
            4. 未完成事项、阻塞点与风险。
            丢弃问候、重复日志、已否决方案与与任务无关的寒暄。只输出摘要正文。
            """;

    public DevContextBudget {
        maxContextTokens = Math.max(MIN_USABLE_TOKENS, maxContextTokens);
        compactionTriggerTokens =
                clamp(compactionTriggerTokens, MIN_USABLE_TOKENS, maxContextTokens);
        compactionKeepTokens = clamp(compactionKeepTokens, 1_024, compactionTriggerTokens);
        compactionTriggerMessages =
                compactionTriggerMessages <= 0 ? DEFAULT_TRIGGER_MESSAGES : compactionTriggerMessages;
        toolResultMaxChars =
                toolResultMaxChars <= 0 ? DEFAULT_TOOL_RESULT_MAX_CHARS : toolResultMaxChars;
        toolResultPreviewChars =
                clamp(
                        toolResultPreviewChars <= 0
                                ? DEFAULT_TOOL_RESULT_PREVIEW_CHARS
                                : toolResultPreviewChars,
                        256,
                        toolResultMaxChars);
    }

    public static DevContextBudget of(DevModelSettings model) {
        if (model == null || !model.writeCapable()) {
            throw new PayrollException("编码模型未就绪，无法计算上下文预算");
        }
        return of(model.numCtx(), model.numPredict());
    }

    /** 按模型声明窗口与生成预留推导；生成预留不会把可用窗口压到下限以下。 */
    public static DevContextBudget of(int numCtx, int numPredict) {
        int usable = Math.max(MIN_USABLE_TOKENS, numCtx - Math.max(0, numPredict));
        int trigger = Math.max(MIN_USABLE_TOKENS, (int) Math.round(usable * 0.8D));
        int keep = (int) Math.round(usable * 0.25D);
        return new DevContextBudget(
                numCtx,
                Math.min(trigger, usable),
                keep,
                DEFAULT_TRIGGER_MESSAGES,
                DEFAULT_TOOL_RESULT_MAX_CHARS,
                DEFAULT_TOOL_RESULT_PREVIEW_CHARS);
    }

    /** 系统提示 + workspace 上下文可用的上限：压缩触发点之前的全部空间。 */
    public int promptBudgetTokens() {
        return compactionTriggerTokens;
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }
}
