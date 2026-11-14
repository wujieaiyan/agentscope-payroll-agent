package io.agentscope.examples.payroll.service;

import java.time.Duration;
import java.util.Map;

/** 知识库工具的执行通道；Web 侧按当前用户权限落实，runtime 只负责发起调用。 */
public interface KbSkillGateway {

    boolean enabled();

    /** 执行工具，返回 Web 节点的 JSON 结果（含 status/data 或 FORBIDDEN）。 */
    String call(String tool, Map<String, Object> arguments, long userId);

    /** 带业务超时的执行；默认实现忽略超时，供检索工具收紧等待。 */
    default String call(
            String tool, Map<String, Object> arguments, long userId, Duration timeout) {
        return call(tool, arguments, userId);
    }

    /** 预检写工具权限；拒绝时不生成提案。 */
    Check check(String tool, Map<String, Object> arguments, long userId);

    record Check(boolean allowed, String message) {

        public static Check granted() {
            return new Check(true, "");
        }

        public static Check denied(String message) {
            return new Check(false, message == null ? "" : message);
        }
    }
}
