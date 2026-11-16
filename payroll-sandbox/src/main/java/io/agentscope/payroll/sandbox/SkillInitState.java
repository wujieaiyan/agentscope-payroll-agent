package io.agentscope.payroll.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工作区状态文件 {@code <skill-dir>/.init.json} 的读写；写使用临时文件加原子替换。
 *
 * <p>读写共用进程内锁：Windows 上目标文件被并发读取时替换会报共享冲突，
 * 替换失败再短重试几次，覆盖外部进程短暂持有句柄的情况。
 */
public final class SkillInitState {

    public static final String STATE_FILE = ".init.json";

    private static final Logger log = LoggerFactory.getLogger(SkillInitState.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final Object LOCK = new Object();
    private static final int REPLACE_ATTEMPTS = 5;
    private static final long REPLACE_RETRY_MS = 20L;

    private SkillInitState() {
    }

    public static SkillInitStatus read(Path skillDir) {
        if (skillDir == null) {
            return null;
        }
        Path file = skillDir.resolve(STATE_FILE);
        synchronized (LOCK) {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            try {
                return MAPPER.readValue(file.toFile(), SkillInitStatus.class);
            } catch (IOException e) {
                log.warn("[技能初始化] 状态文件无法解析：{}", file);
                return null;
            }
        }
    }

    public static void write(Path skillDir, SkillInitStatus status) throws IOException {
        Path file = skillDir.resolve(STATE_FILE);
        Path temp = skillDir.resolve(STATE_FILE + ".tmp");
        synchronized (LOCK) {
            Files.createDirectories(skillDir);
            MAPPER.writeValue(temp.toFile(), status);
            IOException failure = null;
            for (int attempt = 0; attempt < REPLACE_ATTEMPTS; attempt++) {
                if (attempt > 0) {
                    sleepQuietly();
                }
                try {
                    replace(temp, file);
                    return;
                } catch (IOException e) {
                    failure = e;
                }
            }
            throw failure;
        }
    }

    public static SkillInitStatus readReady(Path dataDir) {
        if (dataDir == null) {
            return null;
        }
        SkillInitStatus status = read(dataDir.getParent());
        return status != null && status.ready() ? status : null;
    }

    private static void replace(Path temp, Path file) throws IOException {
        try {
            Files.move(
                    temp,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            // Windows 上覆盖已存在的目标有时不支持原子替换，退回普通替换。
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(REPLACE_RETRY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
