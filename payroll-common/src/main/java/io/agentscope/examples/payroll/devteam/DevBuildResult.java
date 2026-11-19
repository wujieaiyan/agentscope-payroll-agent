package io.agentscope.examples.payroll.devteam;

import java.util.Locale;

/** 一条构建校验命令的执行结果，供任务摘要与审计展示。 */
public record DevBuildResult(
        String name,
        String image,
        int exitCode,
        boolean timedOut,
        long durationMillis,
        String output) {

    public DevBuildResult {
        name = name == null ? "" : name.trim();
        image = image == null ? "" : image.trim();
        output = output == null ? "" : output;
    }

    public boolean success() {
        return !timedOut && exitCode == 0;
    }

    /** 一行摘要：命令名、镜像、耗时与结论。 */
    public String describe() {
        String seconds = String.format(Locale.ROOT, "%.1fs", durationMillis / 1000D);
        String verdict = timedOut ? "超时" : exitCode == 0 ? "通过" : "退出码 " + exitCode;
        return name + "（" + image + "，" + seconds + "）：" + verdict;
    }
}
