package io.agentscope.examples.payroll.kb.parse;

/** 远程解析被取消时抛出，沿用 {@link KbAbortSignal} 走任务取消清理路径。 */
public class KbParseCanceled extends RuntimeException implements KbAbortSignal {

    public KbParseCanceled(String message) {
        super(message);
    }
}
