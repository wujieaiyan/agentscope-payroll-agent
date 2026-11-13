package io.agentscope.examples.payroll.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** 出账前校验结果：任何一项未通过都应由 HR 复核后再发薪。 */
public record PayrollValidation(
        @JsonProperty("employee_id") String employeeId,
        String period,
        boolean valid,
        List<CheckResult> checks,
        PayrollResult payroll) {

    public record CheckResult(
            String check, boolean passed, String detail) {
    }
}
