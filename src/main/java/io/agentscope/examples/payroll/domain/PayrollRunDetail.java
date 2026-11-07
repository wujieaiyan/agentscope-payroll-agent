package io.agentscope.examples.payroll.domain;

import java.util.List;

/** 批次详情：表头 + 成功明细 + 核算失败清单。 */
public record PayrollRunDetail(
        PayrollRun run, List<PayrollRunItem> items, List<PayrollRunFailure> failures) {

    public PayrollRunDetail {
        items = List.copyOf(items);
        failures = List.copyOf(failures);
    }
}
