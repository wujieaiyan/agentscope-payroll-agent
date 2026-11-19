package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 受限 runner（P1-3）：只执行 profile 声明的命令，容器参数固定并逐条失败即停。 */
class DevDockerBuildRunnerTest {

    @TempDir Path temp;

    private Path workspace;

    @BeforeEach
    void setUp() throws IOException {
        workspace = Files.createDirectories(temp.resolve("ws"));
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
    }

    @AfterEach
    void reset() {
        DevPathPolicy.resetProfile();
    }

    @Test
    @DisplayName("未声明校验命令时返回空列表且不调用 docker")
    void noCommandsRunsNothing() {
        RecordingLauncher launcher = new RecordingLauncher();
        DevDockerBuildRunner runner =
                new DevDockerBuildRunner(enabledSettings("", "", ""), launcher);

        assertTrue(runner.run(DevPathPolicy.ROLE_IMPLEMENTER, workspace).isEmpty());
        assertTrue(launcher.calls.isEmpty());
    }

    @Test
    @DisplayName("声明命令但 runner 关闭时失败，不把未校验当成通过")
    void disabledRunnerFailsWhenCommandsDeclared() throws IOException {
        loadVerify(
                """
                [{"name": "backend-build", "command": ["mvn", "-o", "-q", "compile"],
                  "image": "maven:3.9"}]
                """);
        RecordingLauncher launcher = new RecordingLauncher();
        DevDockerBuildRunner runner =
                new DevDockerBuildRunner(
                        new DevRunnerSettings(false, "docker", "", 0, 0, 0, "", "", 0), launcher);

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> runner.run(DevPathPolicy.ROLE_IMPLEMENTER, workspace));
        assertTrue(error.getMessage().contains("未启用"));
        assertTrue(launcher.calls.isEmpty());
    }

    @Test
    @DisplayName("docker run 固定 network=none 与资源上限，并按配置挂载缓存")
    void buildsIsolatedDockerArgv() throws IOException {
        loadVerify(
                """
                [{"name": "backend-build", "command": ["mvn", "-o", "-q", "compile"],
                  "timeoutSeconds": 60}]
                """);
        RecordingLauncher launcher = new RecordingLauncher();
        Path m2 = temp.resolve("cache/m2");
        Path npm = temp.resolve("cache/npm");
        DevRunnerSettings settings =
                new DevRunnerSettings(
                        true, "docker", "maven:3.9-eclipse-temurin-17", 1024, 1.5, 128,
                        m2.toString(), npm.toString(), 4000);
        DevDockerBuildRunner runner = new DevDockerBuildRunner(settings, launcher);

        List<DevBuildResult> results =
                runner.run(DevPathPolicy.ROLE_IMPLEMENTER, workspace);

        assertEquals(1, results.size());
        assertTrue(results.get(0).success());
        assertEquals("backend-build", results.get(0).name());
        assertEquals(Duration.ofSeconds(60), launcher.timeouts.get(0));
        assertEquals(
                List.of(
                        "docker", "run", "--rm", "--network", "none",
                        "--memory", "1024m", "--cpus", "1.5", "--pids-limit", "128",
                        "-v", workspace.toAbsolutePath().normalize() + ":/work",
                        "-v", m2.toAbsolutePath().normalize() + ":/root/.m2",
                        "-v", npm.toAbsolutePath().normalize() + ":/root/.npm",
                        "-w", "/work",
                        "maven:3.9-eclipse-temurin-17",
                        "mvn", "-o", "-q", "compile"),
                launcher.calls.get(0));
        assertTrue(Files.isDirectory(m2));
        assertTrue(Files.isDirectory(npm));
    }

    @Test
    @DisplayName("命令自带镜像优先于默认镜像，整数 CPU 不带小数点")
    void commandImageOverridesDefault() throws IOException {
        loadVerify(
                """
                [{"name": "frontend-build", "command": ["npm", "run", "build"],
                  "image": "node:20-alpine"}]
                """);
        RecordingLauncher launcher = new RecordingLauncher();
        DevDockerBuildRunner runner =
                new DevDockerBuildRunner(
                        new DevRunnerSettings(
                                true, "docker", "maven:3.9", 2048, 2, 512, "", "", 8000),
                        launcher);

        runner.run(DevPathPolicy.ROLE_IMPLEMENTER, workspace);

        List<String> argv = launcher.calls.get(0);
        assertTrue(argv.containsAll(List.of("--cpus", "2")));
        int imageIndex = argv.indexOf("node:20-alpine");
        assertTrue(imageIndex > 0);
        assertEquals(
                List.of("npm", "run", "build"),
                argv.subList(imageIndex + 1, argv.size()));
    }

    @Test
    @DisplayName("第一条命令失败即停止，后续命令不再执行")
    void stopsOnFirstFailure() throws IOException {
        loadVerify(
                """
                [{"name": "compile", "command": ["mvn", "compile"], "image": "maven:3.9"},
                 {"name": "test", "command": ["mvn", "test"], "image": "maven:3.9"}]
                """);
        RecordingLauncher launcher = new RecordingLauncher();
        launcher.results.add(new DevCommandResult(1, false, "compile error"));
        DevDockerBuildRunner runner =
                new DevDockerBuildRunner(enabledSettings("", "", ""), launcher);

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> runner.run(DevPathPolicy.ROLE_IMPLEMENTER, workspace));
        assertTrue(error.getMessage().contains("构建校验失败"));
        assertTrue(error.getMessage().contains("compile error"));
        assertEquals(1, launcher.calls.size());
    }

    @Test
    @DisplayName("超时命令按失败处理，错误信息标注超时")
    void timeoutFailsTask() throws IOException {
        loadVerify(
                """
                [{"name": "test", "command": ["mvn", "test"], "image": "maven:3.9"}]
                """);
        RecordingLauncher launcher = new RecordingLauncher();
        launcher.results.add(new DevCommandResult(-1, true, "partial"));
        DevDockerBuildRunner runner =
                new DevDockerBuildRunner(enabledSettings("", "", ""), launcher);

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> runner.run(DevPathPolicy.ROLE_IMPLEMENTER, workspace));
        assertTrue(error.getMessage().contains("超时"));
    }

    @Test
    @DisplayName("缺少镜像或工作区不存在时直接失败")
    void rejectsMissingImageOrWorkspace() throws IOException {
        loadVerify(
                """
                [{"command": ["mvn", "-v"]}]
                """);
        RecordingLauncher launcher = new RecordingLauncher();
        DevDockerBuildRunner noImage =
                new DevDockerBuildRunner(enabledSettings("", "", ""), launcher);
        PayrollException imageError =
                assertThrows(
                        PayrollException.class,
                        () -> noImage.run(DevPathPolicy.ROLE_IMPLEMENTER, workspace));
        assertTrue(imageError.getMessage().contains("镜像"));

        DevDockerBuildRunner runner =
                new DevDockerBuildRunner(enabledSettings("maven:3.9", "", ""), launcher);
        PayrollException workspaceError =
                assertThrows(
                        PayrollException.class,
                        () ->
                                runner.run(
                                        DevPathPolicy.ROLE_IMPLEMENTER,
                                        temp.resolve("missing-ws")));
        assertTrue(workspaceError.getMessage().contains("工作区不存在"));
        assertTrue(launcher.calls.isEmpty());
    }

    private DevRunnerSettings enabledSettings(String image, String m2, String npm) {
        return new DevRunnerSettings(true, "docker", image, 2048, 2, 512, m2, npm, 8000);
    }

    private void loadVerify(String commandsJson) throws IOException {
        Path file = temp.resolve("runner-profile.json");
        Files.writeString(
                file,
                "{\"roles\": {\"project-implementer\": {\"read\": [\"**\"], \"write\": [\"**\"]}},"
                        + " \"verify\": {\"project-implementer\": "
                        + commandsJson
                        + "}}",
                StandardCharsets.UTF_8);
        DevPathPolicy.loadProfile(file);
    }

    private static final class RecordingLauncher implements DevCommandLauncher {

        private final List<List<String>> calls = new ArrayList<>();
        private final List<Duration> timeouts = new ArrayList<>();
        private final Deque<DevCommandResult> results = new ArrayDeque<>();

        @Override
        public DevCommandResult execute(List<String> argv, Duration timeout) {
            calls.add(List.copyOf(argv));
            timeouts.add(timeout);
            return results.isEmpty() ? new DevCommandResult(0, false, "ok") : results.removeFirst();
        }
    }
}
