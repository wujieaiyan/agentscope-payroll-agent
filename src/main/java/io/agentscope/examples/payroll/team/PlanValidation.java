package io.agentscope.examples.payroll.team;

import java.util.List;
import java.util.stream.Collectors;

/** 计划校验结果；错误列表可用于计划修复提示与审计。 */
public record PlanValidation(List<PlanError> errors) {

    public PlanValidation {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public boolean valid() {
        return errors.isEmpty();
    }

    public String summary() {
        return errors.stream().map(PlanError::toString).collect(Collectors.joining("；"));
    }

    public static PlanValidation ok() {
        return new PlanValidation(List.of());
    }
}
