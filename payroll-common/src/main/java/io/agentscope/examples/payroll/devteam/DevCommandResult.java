package io.agentscope.examples.payroll.devteam;

/**
 * 单条受限命令的执行结果；{@code exitCode = -1} 且 {@code timedOut = true} 表示被超时终止。
 */
public record DevCommandResult(int exitCode, boolean timedOut, String output) {

    public DevCommandResult {
        output = output == null ? "" : output;
    }

    public boolean success() {
        return !timedOut && exitCode == 0;
    }
}
