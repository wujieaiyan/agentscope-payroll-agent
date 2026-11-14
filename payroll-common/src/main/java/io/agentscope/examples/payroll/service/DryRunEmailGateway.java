package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.domain.EmailStatus;
import io.agentscope.examples.payroll.domain.PayrollEmailResult;
import io.agentscope.examples.payroll.domain.PayrollRunItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * dry-run 邮件网关：只做收件人校验与结果模拟，不建立任何网络连接。
 *
 * <p>这样可以先验证“谁发不出去、为什么”，确认无误后再替换为真实 SMTP 实现。
 */
public final class DryRunEmailGateway implements EmailGateway {

    private static final Pattern EMAIL =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    @Override
    public List<PayrollEmailResult> send(String period, List<PayrollRunItem> items) {
        Map<String, String> ownerByEmail = new HashMap<>();
        List<PayrollEmailResult> results = new ArrayList<>();
        for (PayrollRunItem item : items) {
            String email = item.email() == null ? "" : item.email().trim();
            String status;
            String reason;
            if (email.isEmpty()) {
                status = EmailStatus.FAILED;
                reason = "未维护邮箱地址，无法发送";
            } else if (!EMAIL.matcher(email).matches()) {
                status = EmailStatus.FAILED;
                reason = "邮箱格式不合法：" + email;
            } else {
                String key = email.toLowerCase();
                String owner = ownerByEmail.putIfAbsent(key, item.employeeId());
                if (owner != null) {
                    status = EmailStatus.FAILED;
                    reason = "邮箱与 " + owner + " 重复，请核对后再发送";
                } else {
                    status = EmailStatus.SIMULATED_SUCCESS;
                    reason = "dry-run 模拟发送成功（未真实投递）";
                }
            }
            results.add(
                    new PayrollEmailResult(
                            item.employeeId(), item.employeeName(), email, status, reason));
        }
        return List.copyOf(results);
    }
}
