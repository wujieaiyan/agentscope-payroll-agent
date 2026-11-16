package io.agentscope.examples.payroll.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.sandbox.SandboxProtocol;
import io.agentscope.payroll.sandbox.CommandExecutor;
import io.agentscope.payroll.sandbox.DockerSandboxRunner;
import io.agentscope.payroll.sandbox.SandboxRequest;
import io.agentscope.payroll.sandbox.SandboxResult;
import io.agentscope.payroll.sandbox.SandboxSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerSandboxRunnerTest {

    @TempDir
    Path workDir;

    @Test
    @DisplayName("容器命令固定隔离参数，脚本目录删除后不留临时文件")
    void buildsIsolatedCommand() throws IOException {
        FakeExecutor executor = new FakeExecutor(List.of(outcome(0, "ok\n", "")));
        DockerSandboxRunner runner = new DockerSandboxRunner(settings(), executor);

        SandboxResult result = runner.run(request(), "sha");

        assertEquals(SandboxResult.OK, result.status());
        assertEquals("ok\n", result.stdout());
        assertEquals("sha", result.scriptSha256());
        List<String> command = executor.commands.get(0);
        assertTrue(command.containsAll(List.of(
                "run", "--rm", "--network", "none", "--read-only", "--cap-drop", "ALL")), command.toString());
        assertTrue(
                command.contains("no-new-privileges"), command.toString());
        assertTrue(command.contains("65534:65534"), command.toString());
        assertTrue(command.contains("256m"), command.toString());
        String mount = command.stream().filter(value -> value.startsWith("type=bind,")).findFirst()
                .orElseThrow();
        assertTrue(mount.contains("target=/src,readonly"), mount);
        assertTrue(command.contains("payroll-sandbox-runtime:local"), command.toString());
        assertTrue(command.contains("python3"), command.toString());
        assertTrue(command.contains("/src/scripts/check.py"), command.toString());
        assertTrue(command.contains("--month"), command.toString());
        assertTrue(
                command.stream().noneMatch(value -> value.contains("target=/data")),
                "没有数据目录时不应挂载 /data");
        assertTrue(directories(workDir).isEmpty(), "执行后应清理工作目录");
    }

    @Test
    @DisplayName("技能数据目录存在时只读挂载到 /data")
    void mountsSkillDataReadOnly() throws IOException {
        Files.createDirectories(workDir.resolve("skills/payroll-auditor/data/poems"));
        FakeExecutor executor = new FakeExecutor(List.of(outcome(0, "", "")));
        DockerSandboxRunner runner = new DockerSandboxRunner(settings(), executor);

        runner.run(request(), "sha");

        List<String> command = executor.commands.get(0);
        String mount =
                command.stream()
                        .filter(value -> value.contains("target=/data"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("缺少 /data 挂载：" + command));
        assertTrue(mount.contains("readonly"), mount);
        assertTrue(mount.contains("payroll-auditor"), mount);
    }

    @Test
    @DisplayName("CLI 超时后按容器名强制 kill")
    void killsContainerOnTimeout() throws IOException {
        FakeExecutor executor =
                new FakeExecutor(List.of(outcome(-1, "", "", true), outcome(0, "", "")));
        DockerSandboxRunner runner = new DockerSandboxRunner(settings(), executor);

        SandboxResult result = runner.run(request(), "sha");

        assertEquals(SandboxResult.TIMEOUT, result.status());
        assertEquals(2, executor.commands.size());
        assertTrue(executor.commands.get(1).contains("kill"));
        assertTrue(executor.commands.get(1).contains(executor.containerName()), executor.commands.toString());
    }

    @Test
    @DisplayName("退出码 125 视为容器启动失败，137 提示资源限额")
    void mapsDockerExitCodes() throws IOException {
        FakeExecutor launchFailure = new FakeExecutor(List.of(outcome(125, "", "image not found")));
        SandboxResult error = new DockerSandboxRunner(settings(), launchFailure).run(request(), "sha");
        assertEquals(SandboxResult.ERROR, error.status());
        assertTrue(error.message().contains("image not found"), error.message());

        FakeExecutor killed = new FakeExecutor(List.of(outcome(137, "", "")));
        SandboxResult killedResult = new DockerSandboxRunner(settings(), killed).run(request(), "sha");
        assertEquals(SandboxResult.OK, killedResult.status());
        assertEquals(137, killedResult.exitCode());
        assertTrue(killedResult.message().contains("资源限额"), killedResult.message());
    }

    @Test
    @DisplayName("非 scripts 路径在写入前被拒绝，且不调用 CLI")
    void rejectsInvalidScriptPath() throws IOException {
        FakeExecutor executor = new FakeExecutor(List.of(outcome(0, "", "")));
        DockerSandboxRunner runner = new DockerSandboxRunner(settings(), executor);
        SandboxRequest invalid =
                new SandboxRequest(
                        7L,
                        "payroll-auditor",
                        "references/a.md",
                        List.of(),
                        1000,
                        List.of(new SandboxRequest.ScriptFile("references/a.md", "x")));

        SandboxResult result = runner.run(invalid, "sha");

        assertEquals(SandboxResult.INVALID, result.status());
        assertTrue(executor.commands.isEmpty(), "校验失败不应启动容器");
    }

    private SandboxSettings settings() {
        return new SandboxSettings("token", "docker", "payroll-sandbox-runtime:local", 4, workDir.toString());
    }

    private static SandboxRequest request() {
        return new SandboxRequest(
                7L,
                "payroll-auditor",
                "scripts/check.py",
                List.of("--month", "2026-11"),
                1000,
                List.of(
                        new SandboxRequest.ScriptFile("scripts/check.py", "print('ok')"),
                        new SandboxRequest.ScriptFile("scripts/helper.py", "HELPER = 1")));
    }

    private static CommandExecutor.Outcome outcome(int exitCode, String stdout, String stderr) {
        return new CommandExecutor.Outcome(exitCode, stdout, stderr, false, false, false);
    }

    private static CommandExecutor.Outcome outcome(
            int exitCode, String stdout, String stderr, boolean timedOut) {
        return new CommandExecutor.Outcome(exitCode, stdout, stderr, timedOut, false, false);
    }

    private static List<Path> directories(Path root) throws IOException {
        try (Stream<Path> children = Files.list(root)) {
            return children.toList();
        }
    }

    private static final class FakeExecutor implements CommandExecutor {

        private final List<CommandExecutor.Outcome> outcomes;
        private final List<List<String>> commands = new ArrayList<>();
        private int index;

        private FakeExecutor(List<CommandExecutor.Outcome> outcomes) {
            this.outcomes = outcomes;
        }

        @Override
        public CommandExecutor.Outcome exec(List<String> command, Duration timeout) {
            commands.add(List.copyOf(command));
            CommandExecutor.Outcome outcome =
                    outcomes.get(Math.min(index, outcomes.size() - 1));
            index++;
            return outcome;
        }

        private String containerName() {
            return commands.get(0).get(commands.get(0).indexOf("--name") + 1);
        }
    }
}
