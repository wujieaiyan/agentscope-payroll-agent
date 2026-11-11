package io.agentscope.examples.payroll.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
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

    @Test
    @DisplayName("ACTIVE 白名单按用户与会话双重灰度，留空表示不限")
    void gatesActiveByWhitelist() {
        TeamSettings open =
                new TeamSettings(TeamMode.ACTIVE, 180, 30, 0D, Set.of(), Set.of());
        assertTrue(open.activeAllowed(1L, 10L));

        TeamSettings usersOnly =
                new TeamSettings(TeamMode.ACTIVE, 180, 30, 0D, Set.of(7L), Set.of());
        assertTrue(usersOnly.activeAllowed(7L, 10L));
        assertFalse(usersOnly.activeAllowed(8L, 10L));

        TeamSettings sessionsOnly =
                new TeamSettings(TeamMode.SHADOW, 180, 30, 1D, Set.of(), Set.of(10L));
        assertFalse(sessionsOnly.activeAllowed(7L, 10L), "非 ACTIVE 模式不允许动态接管");

        TeamSettings both =
                new TeamSettings(TeamMode.ACTIVE, 180, 30, 0D, Set.of(7L), Set.of(10L));
        assertTrue(both.activeAllowed(7L, 10L));
        assertFalse(both.activeAllowed(7L, 11L));
    }
}
