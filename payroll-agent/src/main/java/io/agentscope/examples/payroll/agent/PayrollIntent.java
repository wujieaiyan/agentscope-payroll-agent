package io.agentscope.examples.payroll.agent;

/**
 * 自然语言薪酬诉求的结构化抽取结果。
 *
 * <p>用于“先理解、再执行”的场景：模型只负责把一句话解析成结构化意图，
 * 后续金额计算仍交由确定性引擎完成。
 */
public class PayrollIntent {

    public String action;
    public String employeeId;
    public String employeeName;
    public String department;
    public String period;
    public String reason;

    public PayrollIntent() {
    }
}
