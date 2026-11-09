package io.agentscope.examples.payroll.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TeamSettingsTest {

    @Test
    @DisplayName("模式解析大小写不敏感，未知值回退 OFF")
    void parsesMode() {
        assertEquals(TeamMode.STATIC, TeamMode.from("static"));
        assertEquals(TeamMode.SHADOW, TeamMode.from(" SHADOW "));
        assertEquals(TeamMode.ACTIVE, TeamMode.from("active"));
        assertEquals(TeamMode.OFF, TeamMode.from("unknown"));
        assertEquals(TeamMode.OFF, TeamMode.from(null));
    }

    @Test
    @DisplayName("配置值越界时收敛到安全范围")
    void clampsSettings() {
        TeamSettings settings = new TeamSettings(TeamMode.STATIC, 0, -1, 2.0);
        assertEquals(180, settings.retentionDays());
        assertEquals(30, settings.statsWindowDays());
        assertEquals(1D, settings.shadowSampleRate());
        assertTrue(settings.enabled());

        TeamSettings off = new TeamSettings(TeamMode.OFF, 30, 7, -0.5);
        assertFalse(off.enabled());
        assertEquals(0D, off.shadowSampleRate());
    }
}
