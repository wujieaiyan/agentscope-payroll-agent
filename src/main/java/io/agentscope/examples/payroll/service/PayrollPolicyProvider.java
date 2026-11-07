package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.domain.PayrollPolicy;

/** 按员工所在城市解析社保/公积金政策；未维护的城市必须失败关闭。 */
@FunctionalInterface
public interface PayrollPolicyProvider {

    PayrollPolicy policyFor(String city);
}
