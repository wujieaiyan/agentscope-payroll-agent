package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.tool.Tool;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuiltinToolCatalogTest {

    @Test
    @DisplayName("目录与 PayrollTools 的 @Tool 声明一致，任何一侧漂移都失败")
    void catalogMatchesPayrollTools() {
        Set<String> declared =
                Arrays.stream(PayrollTools.class.getDeclaredMethods())
                        .map(BuiltinToolCatalogTest::toolName)
                        .filter(name -> name != null)
                        .collect(Collectors.toSet());

        assertEquals(BuiltinToolCatalog.TOOL_NAMES, declared);
    }

    @Test
    @DisplayName("声明去空白去重；未知工具拒绝；空声明视为未声明")
    void normalizeRules() {
        assertEquals(
                List.of("get_payroll_policy"),
                BuiltinToolCatalog.normalize(
                        List.of(" get_payroll_policy ", "get_payroll_policy", " ")));
        assertEquals(List.of(), BuiltinToolCatalog.normalize(null));
        assertEquals(List.of(), BuiltinToolCatalog.normalize(List.of()));

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> BuiltinToolCatalog.normalize(List.of("drop_table")));
        assertTrue(error.getMessage().contains("未知业务工具"), error.getMessage());
    }

    private static String toolName(Method method) {
        Tool annotation = method.getAnnotation(Tool.class);
        return annotation == null ? null : annotation.name();
    }
}
