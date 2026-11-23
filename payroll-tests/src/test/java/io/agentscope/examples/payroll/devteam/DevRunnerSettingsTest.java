package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.PayrollException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DevRunnerSettingsTest {

    @Test
    @DisplayName("缺省值：docker 可执行、2048MB、2 CPU、512 pids、8000 字符输出")
    void appliesDefaults() {
        DevRunnerSettings settings = new DevRunnerSettings(true, " ", "", 0, 0, 0, "", "", 0);

        assertEquals("docker", settings.dockerBinary());
        assertEquals("", settings.defaultImage());
        assertEquals(DevRunnerSettings.DEFAULT_MEMORY_MB, settings.memoryMb());
        assertEquals(DevRunnerSettings.DEFAULT_CPUS, settings.cpus());
        assertEquals(DevRunnerSettings.DEFAULT_PIDS_LIMIT, settings.pidsLimit());
        assertEquals(DevRunnerSettings.DEFAULT_MAX_OUTPUT_CHARS, settings.maxOutputChars());
        assertFalse(settings.hasMavenCache());
        assertFalse(settings.hasNpmCache());
    }

    @Test
    @DisplayName("输出上限收敛到 1000–100000，缓存目录去空白")
    void clampsOutputLimit() {
        DevRunnerSettings small =
                new DevRunnerSettings(true, "docker", "img", 1024, 1, 64, " m2 ", " npm ", 10);
        assertEquals(DevRunnerSettings.MIN_MAX_OUTPUT_CHARS, small.maxOutputChars());
        assertTrue(small.hasMavenCache());
        assertEquals("m2", small.mavenCacheDir());

        DevRunnerSettings large =
                new DevRunnerSettings(true, "docker", "img", 1024, 1, 64, "", "", 1_000_000);
        assertEquals(DevRunnerSettings.MAX_MAX_OUTPUT_CHARS, large.maxOutputChars());
    }

    @Test
    @DisplayName("内存下限 256MB，低于下限直接拒绝")
    void rejectsTooLittleMemory() {
        assertThrows(
                PayrollException.class,
                () -> new DevRunnerSettings(true, "docker", "img", 128, 1, 64, "", "", 8000));
    }

    @Test
    @DisplayName("disabled() 关闭 runner 且使用默认参数")
    void disabledFactory() {
        DevRunnerSettings settings = DevRunnerSettings.disabled();
        assertFalse(settings.enabled());
        assertEquals(DevRunnerSettings.DEFAULT_MEMORY_MB, settings.memoryMb());
        assertEquals(DevRunnerSettings.DEFAULT_MAX_OUTPUT_CHARS, settings.maxOutputChars());
    }
}
