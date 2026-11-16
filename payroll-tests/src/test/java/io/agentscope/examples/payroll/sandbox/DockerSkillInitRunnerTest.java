package io.agentscope.examples.payroll.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import io.agentscope.payroll.sandbox.CommandExecutor;
import io.agentscope.payroll.sandbox.DockerSkillInitRunner;
import io.agentscope.payroll.sandbox.SandboxSettings;
import io.agentscope.payroll.sandbox.SkillInitRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerSkillInitRunnerTest {

    @TempDir
    Path temp;

    @Test
    @DisplayName("初始化容器允许联网写工作区，其余隔离参数与执行容器一致")
    void buildsInitCommand() throws IOException {
        Path pkg = Files.createDirectories(temp.resolve("pkg"));
        Path data = Files.createDirectories(temp.resolve("data"));
        Files.writeString(data.resolve("poems.txt"), "静夜思");
        FakeExecutor executor = new FakeExecutor(List.of(outcome(0, "cloned")));
        DockerSkillInitRunner runner = new DockerSkillInitRunner(settings(), executor);

        SkillInitRunner.Outcome result = runner.run("setup.sh", pkg, data, 600_000, 1024);

        assertEquals(0, result.exitCode());
        assertEquals("cloned", result.logTail());
        assertEquals(9, result.sizeBytes());
        List<String> command = executor.commands.get(0);
        assertTrue(
                command.containsAll(
                        List.of(
                                "run",
                                "--rm",
                                "--network",
                                "bridge",
                                "--read-only",
                                "--cap-drop",
                                "ALL",
                                "1g",
                                "128")),
                command.toString());
        assertTrue(command.contains("no-new-privileges"), command.toString());
        assertTrue(command.contains("65534:65534"), command.toString());
        assertTrue(command.contains("HOME=/work"), command.toString());
        assertTrue(command.contains("GIT_TERMINAL_PROMPT=0"), command.toString());
        List<String> mounts =
                command.stream().filter(value -> value.startsWith("type=bind,")).toList();
        assertTrue(
                mounts.stream().anyMatch(mount -> mount.endsWith("target=/skill")), mounts.toString());
        assertTrue(
                mounts.stream().anyMatch(mount -> mount.contains("target=/skill/data")),
                mounts.toString());
        assertTrue(command.contains("payroll-sandbox-init:local"), command.toString());
        assertTrue(command.contains("/bin/sh"), command.toString());
        assertTrue(command.contains("/skill/setup.sh"), command.toString());
    }

    @Test
    @DisplayName("setup.sh 声明 bash 时按 bash 启动容器")
    void usesBashForBashShebang() throws IOException {
        Path pkg = Files.createDirectories(temp.resolve("pkg-bash"));
        Path data = Files.createDirectories(temp.resolve("data-bash"));
        Files.writeString(
                pkg.resolve("setup.sh"),
                "#!/bin/bash\necho ${BASH_SOURCE[0]}\n");
        FakeExecutor executor = new FakeExecutor(List.of(outcome(0, "ok")));
        DockerSkillInitRunner runner = new DockerSkillInitRunner(settings(), executor);

        runner.run("setup.sh", pkg, data, 600_000, 1024);

        List<String> command = executor.commands.get(0);
        assertTrue(command.contains("/bin/bash"), command.toString());
        assertFalse(command.contains("/bin/sh"), command.toString());
        assertTrue(command.indexOf("/bin/bash") < command.indexOf("/skill/setup.sh"));
    }

    @Test
    @DisplayName("超时后按容器名强制 kill")
    void killsOnTimeout() {
        FakeExecutor executor =
                new FakeExecutor(List.of(timedOut(), outcome(0, "")));
        DockerSkillInitRunner runner = new DockerSkillInitRunner(settings(), executor);

        SkillInitRunner.Outcome result =
                runner.run("scripts/init.py", temp, temp, 1000, 1024);

        assertTrue(result.timedOut());
        assertEquals(2, executor.commands.size());
        assertTrue(executor.commands.get(1).contains("kill"));
        assertTrue(executor.commands.get(1).contains(executor.containerName()));
    }

    @Test
    @DisplayName("退出码 125 与 137 给出明确原因")
    void mapsDockerExitCodes() {
        FakeExecutor launchFailure = new FakeExecutor(List.of(outcome(125, "image not found")));
        SkillInitRunner.Outcome error =
                new DockerSkillInitRunner(settings(), launchFailure)
                        .run("setup.sh", temp, temp, 1000, 1024);

        assertTrue(error.message().contains("image not found"), error.message());
        assertFalse(error.sizeExceeded());

        FakeExecutor killed = new FakeExecutor(List.of(outcome(137, "")));
        SkillInitRunner.Outcome killedResult =
                new DockerSkillInitRunner(settings(), killed)
                        .run("setup.sh", temp, temp, 1000, 1024);

        assertEquals(137, killedResult.exitCode());
        assertTrue(killedResult.message().contains("资源限额"), killedResult.message());
    }

    private SandboxSettings settings() {
        return new SandboxSettings(
                "token",
                "docker",
                "payroll-sandbox-runtime:local",
                4,
                temp.toString(),
                temp.resolve("skills").toString(),
                "payroll-sandbox-init:local",
                600_000,
                1024L * 1024 * 1024,
                2);
    }

    private static CommandExecutor.Outcome outcome(int exitCode, String stdout) {
        return new CommandExecutor.Outcome(exitCode, stdout, "", false, false, false);
    }

    private static CommandExecutor.Outcome timedOut() {
        return new CommandExecutor.Outcome(-1, "", "", true, false, false);
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
            return exec(command, timeout, null);
        }

        @Override
        public CommandExecutor.Outcome exec(
                List<String> command, Duration timeout, Path logFile) {
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
