package io.agentscope.examples.payroll.domain;

/** 工资批次与邮件状态常量。 */
public final class EmailStatus {

    public static final String PENDING = "PENDING";
    public static final String SIMULATED_SUCCESS = "SIMULATED_SUCCESS";
    public static final String FAILED = "FAILED";

    private EmailStatus() {
    }

    public static boolean isSuccess(String status) {
        return SIMULATED_SUCCESS.equals(status);
    }
}
