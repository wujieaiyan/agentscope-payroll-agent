package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** argv 执行器：合并输出、超时强杀与输出上限；测试命令按操作系统选择。 */
class ProcessCommandLauncherTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    @Test
    @DisplayName("命令成功时返回退出码与完整输出")
    void capturesOutput() throws Exception {
        ProcessCommandLauncher launcher = new ProcessCommandLauncher(4000);
        DevCommandResult result = launcher.execute(echo("hello-runner"), Duration.ofSeconds(10));

        assertTrue(result.success());
        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("hello-runner"));
    }

    @Test
    @DisplayName("超过超时时间强制终止并标记 timedOut")
    void killsOnTimeout() throws Exception {
        ProcessCommandLauncher launcher = new ProcessCommandLauncher(4000);
        DevCommandResult result = launcher.execute(sleep(), Duration.ofMillis(300));

        assertFalse(result.success());
        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
    }

    @Test
    @DisplayName("输出按字符上限截断，避免撑爆任务备注")
    void truncatesOutput() throws Exception {
        ProcessCommandLauncher launcher = new ProcessCommandLauncher(50);
        DevCommandResult result =
                launcher.execute(echo("x".repeat(500)), Duration.ofSeconds(10));

        assertTrue(result.success());
        assertTrue(result.output().length() <= 50, result.output());
    }

    @Test
    @DisplayName("空命令与非法超时直接拒绝")
    void rejectsInvalidArguments() {
        ProcessCommandLauncher launcher = new ProcessCommandLauncher(100);
        assertThrows(
                IllegalArgumentException.class,
                () -> launcher.execute(List.of(), Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> launcher.execute(echo("x"), Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> launcher.execute(echo("x"), Duration.ofSeconds(-1)));
    }

    private static List<String> echo(String text) {
        return WINDOWS
                ? List.of("cmd.exe", "/c", "echo " + text)
                : List.of("/bin/sh", "-c", "echo " + text);
    }

    private static List<String> sleep() {
        return WINDOWS
                ? List.of("cmd.exe", "/c", "ping -n 20 127.0.0.1")
                : List.of("/bin/sh", "-c", "sleep 5");
    }
}
