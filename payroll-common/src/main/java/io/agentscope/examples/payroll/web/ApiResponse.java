package io.agentscope.examples.payroll.web;

/** 管理台统一响应包：成功带 data，失败带 message。 */
public record ApiResponse(String status, Object data, String message) {

    public static ApiResponse ok(Object data) {
        return new ApiResponse("OK", data, null);
    }

    public static ApiResponse error(String message) {
        return new ApiResponse("ERROR", null, message);
    }
}
