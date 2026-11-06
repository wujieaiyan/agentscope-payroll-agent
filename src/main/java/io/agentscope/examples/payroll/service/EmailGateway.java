package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.domain.PayrollEmailResult;
import io.agentscope.examples.payroll.domain.PayrollRunItem;
import java.util.List;

/** 工资条邮件发送边界：真实实现可替换为 SMTP 或企业邮件网关。 */
@FunctionalInterface
public interface EmailGateway {

    List<PayrollEmailResult> send(String period, List<PayrollRunItem> items);
}
