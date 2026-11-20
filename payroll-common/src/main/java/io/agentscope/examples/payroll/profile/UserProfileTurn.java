package io.agentscope.examples.payroll.profile;

import java.time.LocalDateTime;

/** 抽取窗口内的一轮：只带用户问题，助手答复不参与偏好抽取。 */
public record UserProfileTurn(String turnId, long userId, LocalDateTime startedAt, String message) {
}
