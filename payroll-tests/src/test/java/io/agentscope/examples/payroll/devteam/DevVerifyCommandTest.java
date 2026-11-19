package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DevVerifyCommandTest {

    @Test
    @DisplayName("缺省超时 900 秒，名称与镜像去空白，二进制大小写不敏感")
    void appliesDefaults() {
        DevVerifyCommand command =
                new DevVerifyCommand(
                        "  backend-build ", List.of(" MVN ", "-o", "compile"), 0, "  maven:3.9 ");

        assertEquals("backend-build", command.name());
        assertEquals(List.of(" MVN ", "-o", "compile"), command.command());
        assertEquals(DevVerifyCommand.DEFAULT_TIMEOUT_SECONDS, command.timeoutSeconds());
        assertEquals("maven:3.9", command.image());
    }

    @Test
    @DisplayName("超时上限 1800 秒，避免单条命令长期占用编码面")
    void capsTimeout() {
        DevVerifyCommand command =
                new DevVerifyCommand("build", List.of("npm", "run", "build"), 7200, "");
        assertEquals(DevVerifyCommand.MAX_TIMEOUT_SECONDS, command.timeoutSeconds());
    }

    @Test
    @DisplayName("非 allowlist 二进制、空 argv、空参数与空名称一律拒绝")
    void rejectsUnsafeCommands() {
        assertThrows(
                PayrollException.class,
                () -> new DevVerifyCommand("x", List.of("bash", "-c", "id"), 0, ""));
        assertThrows(
                PayrollException.class,
                () -> new DevVerifyCommand("x", List.of("sh", "-c", "id"), 0, ""));
        assertThrows(
                PayrollException.class,
                () -> new DevVerifyCommand("x", List.of("curl", "http://example.test"), 0, ""));
        assertThrows(
                PayrollException.class, () -> new DevVerifyCommand("x", List.of(), 0, ""));
        assertThrows(
                PayrollException.class,
                () -> new DevVerifyCommand("x", List.of("mvn", " "), 0, ""));
        assertThrows(
                PayrollException.class,
                () -> new DevVerifyCommand(" ", List.of("mvn", "-v"), 0, ""));
    }

    @Test
    @DisplayName("allowlist 覆盖 mvn/npm/node/java")
    void allowsBuildBinaries() {
        for (String binary : new String[] {"mvn", "npm", "node", "java"}) {
            DevVerifyCommand command =
                    new DevVerifyCommand("build", List.of(binary, "--version"), 60, "");
            assertTrue(DevVerifyCommand.ALLOWED_BINARIES.contains(binary));
            assertEquals(60, command.timeoutSeconds());
        }
    }
}
