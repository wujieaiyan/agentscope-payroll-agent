package io.agentscope.examples.payroll.domain;

/** 工资批次状态：草稿 → 待审批 → 已审批 → 已出账 → 已发送；审批环节可驳回。 */
public final class PayrollRunStatus {

    public static final String DRAFT = "DRAFT";
    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String FINALIZED = "FINALIZED";
    public static final String SENT = "SENT";

    private PayrollRunStatus() {
    }

    public static boolean isFinal(String status) {
        return FINALIZED.equals(status) || SENT.equals(status);
    }
}
