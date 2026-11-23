package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于 {@link ProcessBuilder} 的 argv 执行器：合并 stderr、超时强制终止、输出按字符上限保留。
 *
 * <p>输出由独立线程持续读取，避免管道写满阻塞子进程；超时后 destroyForcibly 并等待退出。
 */
public final class ProcessCommandLauncher implements DevCommandLauncher {

    private static final long DESTROY_WAIT_SECONDS = 5;

    private final int maxOutputChars;

    public ProcessCommandLauncher(int maxOutputChars) {
        this.maxOutputChars = Math.max(1, maxOutputChars);
    }

    @Override
    public DevCommandResult execute(List<String> argv, Duration timeout) throws IOException {
        if (argv == null || argv.isEmpty()) {
            throw new IllegalArgumentException("命令不能为空");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("命令超时时间必须为正数");
        }
        Process process =
                new ProcessBuilder(argv).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder(Math.min(maxOutputChars, 4096));
        Thread reader =
                new Thread(
                        () -> drain(process.getInputStream(), output),
                        "dev-runner-output");
        reader.setDaemon(true);
        reader.start();
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new PayrollException("等待命令退出被中断：" + argv.get(0), e);
        }
        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(DESTROY_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            join(reader);
            return new DevCommandResult(-1, true, render(output));
        }
        join(reader);
        return new DevCommandResult(process.exitValue(), false, render(output));
    }

    private void drain(InputStream stream, StringBuilder target) {
        char[] buffer = new char[4096];
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                synchronized (target) {
                    int room = maxOutputChars - target.length();
                    if (room > 0) {
                        target.append(buffer, 0, Math.min(room, read));
                    }
                }
            }
        } catch (IOException e) {
            // 子进程被强制终止时读端会关闭，属于预期路径；输出保留已读取部分。
        }
    }

    private static void join(Thread reader) {
        try {
            reader.join(DESTROY_WAIT_SECONDS * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String render(StringBuilder output) {
        synchronized (output) {
            return output.toString().strip();
        }
    }
}
