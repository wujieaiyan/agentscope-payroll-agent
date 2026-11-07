package io.agentscope.examples.payroll.domain;

import java.util.List;

/** 全员发送报告：哪些成功、哪些失败以及失败原因。 */
public record PayrollEmailReport(
        long runId,
        String period,
        boolean dryRun,
        String sentAt,
        int total,
        int successCount,
        int failureCount,
        List<PayrollEmailResult> results) {

    public PayrollEmailReport {
        results = List.copyOf(results);
    }
}
