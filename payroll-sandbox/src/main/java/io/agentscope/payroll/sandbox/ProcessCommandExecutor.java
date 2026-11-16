package io.agentscope.payroll.sandbox;

import io.agentscope.examples.payroll.sandbox.SandboxProtocol;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** 本地进程执行：只用于调用 docker CLI；输出各保留 8KB，其余读空避免管道阻塞。 */
@Component
public class ProcessCommandExecutor implements CommandExecutor {

    private static final int CAPTURE_LIMIT = SandboxProtocol.MAX_OUTPUT_BYTES;
    private static final int DRAIN_BUFFER = 4 * 1024;

    @Override
    public Outcome exec(List<String> command, Duration timeout)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).start();
        process.getOutputStream().close();
        Capture stdout = Capture.start(process.getInputStream());
        Capture stderr = Capture.start(process.getErrorStream());
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            stdout.await();
            stderr.await();
            return new Outcome(
                    -1,
                    stdout.text(),
                    stderr.text(),
                    true,
                    stdout.truncated(),
                    stderr.truncated());
        }
        stdout.await();
        stderr.await();
        return new Outcome(
                process.exitValue(),
                stdout.text(),
                stderr.text(),
                false,
                stdout.truncated(),
                stderr.truncated());
    }

    @Override
    public Outcome exec(List<String> command, Duration timeout, Path logFile)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile.toFile());
        Process process = builder.start();
        process.getOutputStream().close();
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        String tail = tail(logFile, CAPTURE_LIMIT);
        return new Outcome(
                finished ? process.exitValue() : -1,
                tail,
                "",
                !finished,
                tail.getBytes(StandardCharsets.UTF_8).length >= CAPTURE_LIMIT,
                false);
    }

    /** 只读文件末尾，避免把整段初始化输出读进内存。 */
    private static String tail(Path file, int limit) throws IOException {
        try (RandomAccessFile reader = new RandomAccessFile(file.toFile(), "r")) {
            long length = reader.length();
            int size = (int) Math.min(length, limit);
            byte[] bytes = new byte[size];
            reader.seek(length - size);
            reader.readFully(bytes);
            int offset = 0;
            while (offset < size && (bytes[offset] & 0xC0) == 0x80) {
                offset++;
            }
            return new String(bytes, offset, size - offset, StandardCharsets.UTF_8);
        }
    }

    private static final class Capture implements Runnable {

        private final InputStream in;
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private final Thread thread;
        private volatile boolean truncated;

        private Capture(InputStream in) {
            this.in = in;
            this.thread = new Thread(this, "sandbox-cli-capture");
            this.thread.setDaemon(true);
        }

        static Capture start(InputStream in) {
            Capture capture = new Capture(in);
            capture.thread.start();
            return capture;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[DRAIN_BUFFER];
            try (in) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    int room = CAPTURE_LIMIT - kept.size();
                    if (room <= 0) {
                        truncated = true;
                        continue;
                    }
                    kept.write(buffer, 0, Math.min(room, read));
                    if (read > room) {
                        truncated = true;
                    }
                }
            } catch (IOException e) {
                truncated = true;
            }
        }

        void await() throws InterruptedException {
            thread.join(2_000);
        }

        String text() {
            return kept.toString(StandardCharsets.UTF_8);
        }

        boolean truncated() {
            return truncated;
        }
    }
}
