package io.agentscope.examples.payroll.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.ActionProposal;
import io.agentscope.examples.payroll.support.Permissions;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultActionExecutorTest {

    @Test
    @DisplayName("本地动作优先执行并透传参数与用户")
    void executesRegisteredLocalAction() {
        List<Map<String, Object>> calls = new ArrayList<>();
        List<Long> users = new ArrayList<>();
        DefaultActionExecutor executor =
                new DefaultActionExecutor(
                        Map.of(
                                "disable_employee",
                                (params, userId) -> {
                                    calls.add(params);
                                    users.add(userId);
                                    return Map.of("status", "OK");
                                }),
                        Map.of("disable_employee", Permissions.EMPLOYEE_WRITE),
                        List::of);

        Object result = executor.execute(proposal("disable_employee", "{\"id\":\"E-001\"}"), 42L);

        assertEquals(Map.of("id", "E-001"), calls.get(0));
        assertEquals(List.of(42L), users);
        assertEquals(Map.of("status", "OK"), result);
        assertEquals(Permissions.EMPLOYEE_WRITE, executor.requiredPermission("disable_employee"));
        assertEquals(Permissions.CHAT_USE, executor.requiredPermission("unknown_tool"));
    }

    @Test
    @DisplayName("未注册且无外部服务时直接失败，不静默成功")
    void failsWhenNoHandlerAvailable() {
        DefaultActionExecutor executor = new DefaultActionExecutor();

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> executor.execute(proposal("disable_employee", "{}"), 7L));

        assertTrue(error.getMessage().contains("未配置可执行该操作的外部服务"));
    }

    @Test
    @DisplayName("提案参数损坏时拒绝执行")
    void rejectsMalformedParams() {
        DefaultActionExecutor executor = new DefaultActionExecutor();
        ActionProposal broken = proposal("disable_employee", "not-json");

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> executor.execute(broken, 7L));

        assertTrue(error.getMessage().contains("参数无法解析"));
    }

    @Test
    @DisplayName("空参数按空 Map 处理")
    void treatsBlankParamsAsEmpty() {
        List<Map<String, Object>> calls = new ArrayList<>();
        DefaultActionExecutor executor =
                new DefaultActionExecutor(
                        Map.of(
                                "ping",
                                (params, userId) -> {
                                    calls.add(params);
                                    return "pong";
                                }),
                        Map.of(),
                        List::of);

        assertEquals("pong", executor.execute(proposal("ping", null), 1L));
        assertTrue(calls.get(0).isEmpty());
    }

    private static ActionProposal proposal(String toolName, String paramsJson) {
        LocalDateTime now = LocalDateTime.now();
        return new ActionProposal(
                "p-" + toolName,
                7L,
                1001L,
                0L,
                "AGENT",
                toolName,
                paramsJson,
                "执行工具 " + toolName,
                ActionProposal.RISK_NORMAL,
                "DEFAULT_MUTATING",
                ActionProposal.STATUS_PENDING,
                "key",
                "hash",
                now,
                now.plusMinutes(15),
                0L,
                null,
                null,
                null,
                null);
    }
}
