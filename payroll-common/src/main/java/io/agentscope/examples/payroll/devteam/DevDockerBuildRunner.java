package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 容器内构建校验（P1-3）：按路径 profile 声明的命令逐条执行 {@code docker run}，
 * 网络固定 {@code none}，限制内存/CPU/进程数，工作区只挂载到 {@code /work}。
 *
 * <p>命令以 argv 传入容器，不经过 shell；任一条失败或超时立即抛出，后续命令不再执行，
 * 任务由执行器置 FAILED。缓存目录仅在配置后挂载，用于跨任务复用 .m2/.npm。
 */
public final class DevDockerBuildRunner implements DevBuildRunner {

    private final DevRunnerSettings settings;
    private final DevCommandLauncher launcher;

    public DevDockerBuildRunner(DevRunnerSettings settings, DevCommandLauncher launcher) {
        if (settings == null) {
            throw new IllegalArgumentException("DevRunnerSettings 不能为空");
        }
        if (launcher == null) {
            throw new IllegalArgumentException("DevCommandLauncher 不能为空");
        }
        this.settings = settings;
        this.launcher = launcher;
    }

    @Override
    public List<DevBuildResult> run(String role, Path workspaceRoot) {
        List<DevVerifyCommand> commands = DevPathPolicy.verifyCommands(role);
        if (commands.isEmpty()) {
            return List.of();
        }
        if (!settings.enabled()) {
            throw new PayrollException(
                    "角色 " + role + " 声明了构建校验命令，但受限 runner 未启用；"
                            + "请设置 payroll.dev-team.runner.enabled=true 或移除 verify 配置");
        }
        if (workspaceRoot == null) {
            throw new PayrollException("构建校验工作区不能为空");
        }
        Path workspace = workspaceRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(workspace)) {
            throw new PayrollException("构建校验工作区不存在：" + workspace);
        }
        List<String> cacheMounts = cacheMounts();
        List<DevBuildResult> results = new ArrayList<>(commands.size());
        for (DevVerifyCommand command : commands) {
            String image = command.image().isBlank() ? settings.defaultImage() : command.image();
            if (image.isBlank()) {
                throw new PayrollException("校验命令未配置镜像：" + command.name());
            }
            List<String> argv = dockerArgv(image, workspace, cacheMounts, command.command());
            long started = System.nanoTime();
            DevCommandResult result;
            try {
                result =
                        launcher.execute(
                                argv, Duration.ofSeconds(command.timeoutSeconds()));
            } catch (IOException e) {
                throw new PayrollException(
                        "启动校验命令失败：" + command.name() + "（请确认 docker 可用）：" + e.getMessage(),
                        e);
            }
            long millis = (System.nanoTime() - started) / 1_000_000L;
            DevBuildResult build =
                    new DevBuildResult(
                            command.name(),
                            image,
                            result.exitCode(),
                            result.timedOut(),
                            millis,
                            result.output());
            results.add(build);
            if (!build.success()) {
                throw new PayrollException(
                        "构建校验失败："
                                + build.describe()
                                + (build.output().isBlank() ? "" : "\n" + build.output()));
            }
        }
        return List.copyOf(results);
    }

    private List<String> cacheMounts() {
        List<String> mounts = new ArrayList<>(2);
        if (settings.hasMavenCache()) {
            mounts.add(prepareCache(settings.mavenCacheDir()) + ":/root/.m2");
        }
        if (settings.hasNpmCache()) {
            mounts.add(prepareCache(settings.npmCacheDir()) + ":/root/.npm");
        }
        return mounts;
    }

    private static String prepareCache(String dir) {
        Path path = Path.of(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new PayrollException("创建构建缓存目录失败：" + path + "：" + e.getMessage(), e);
        }
        return path.toString();
    }

    /** docker run 参数：固定 network=none，限制资源，工作区只读挂载之外不暴露其他宿主路径。 */
    List<String> dockerArgv(
            String image, Path workspace, List<String> cacheMounts, List<String> command) {
        List<String> argv = new ArrayList<>();
        argv.add(settings.dockerBinary());
        argv.add("run");
        argv.add("--rm");
        argv.add("--network");
        argv.add("none");
        argv.add("--memory");
        argv.add(settings.memoryMb() + "m");
        argv.add("--cpus");
        argv.add(cpus());
        argv.add("--pids-limit");
        argv.add(Long.toString(settings.pidsLimit()));
        argv.add("-v");
        argv.add(workspace + ":/work");
        for (String mount : cacheMounts) {
            argv.add("-v");
            argv.add(mount);
        }
        argv.add("-w");
        argv.add("/work");
        argv.add(image);
        argv.addAll(command);
        return argv;
    }

    private String cpus() {
        double cpus = settings.cpus();
        return cpus == Math.rint(cpus) ? Long.toString((long) cpus) : Double.toString(cpus);
    }
}
