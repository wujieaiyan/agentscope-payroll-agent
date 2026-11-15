package io.agentscope.examples.payroll.agent;

import java.util.List;

/**
 * 薪酬语义桶（规格 7.2）：桶是缓存复用单位，粒度与"决策可复用"对齐。
 *
 * <p>{@code payroll_action} 涉及确认、审批与审计上下文，任何档位都不缓存。
 */
public final class PayrollRouteBucket {

    public static final String CALC = "payroll_calc";
    public static final String QUERY = "payroll_query";
    public static final String POLICY = "payroll_policy";
    public static final String ACTION = "payroll_action";

    public static final List<String> ALL = List.of(CALC, QUERY, POLICY, ACTION);

    private PayrollRouteBucket() {
    }

    public static boolean known(String bucket) {
        return bucket != null && ALL.contains(bucket);
    }

    public static boolean cacheable(String bucket) {
        return CALC.equals(bucket) || QUERY.equals(bucket) || POLICY.equals(bucket);
    }
}
