package io.agentscope.examples.payroll.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2AgentTeamStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.AgentTeamRun;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TeamAdminServiceTest {

    private H2AgentTeamStore teamStore;
    private TeamAdminService adminService;

    @BeforeEach
    void setUp() {
        PayrollDatabase database = PayrollDatabase.inMemory("team-admin-" + System.nanoTime());
        teamStore = new H2AgentTeamStore(database);
        adminService =
                new TeamAdminService(
                        new TeamSettings(TeamMode.ACTIVE, 90, 14, 0.5D, Set.of(7L), Set.of()),
                        teamStore);
    }

    @Test
    @DisplayName("配置视图暴露模式、白名单与窗口参数")
    void configExposesWhitelists() {
        TeamAdminService.ConfigView config = adminService.config();

        assertEquals("ACTIVE", config.mode());
        assertTrue(config.activeEnabled());
        assertFalse(config.activeAllUsers());
        assertEquals(1, config.activeUserIds().size());
        assertTrue(config.activeAllSessions());
        assertEquals(90, config.retentionDays());
        assertEquals(14, config.statsWindowDays());
        assertEquals(0.5D, config.shadowSampleRate());
    }

    @Test
    @DisplayName("近期运行按模式过滤并统计模式/状态分布")
    void runsFilterAndCount() {
        createRun(AgentTeamRun.MODE_ACTIVE, AgentTeamRun.STATUS_SUCCESS, "{}");
        createRun(AgentTeamRun.MODE_ACTIVE, AgentTeamRun.STATUS_FALLBACK, "{}");
        createRun(AgentTeamRun.MODE_STATIC, AgentTeamRun.STATUS_SUCCESS, "{}");

        TeamAdminService.RunsView all = adminService.runs(7, "", "", 50);
        assertEquals(3, all.count());
        assertEquals(2L, all.countsByMode().get(AgentTeamRun.MODE_ACTIVE));
        assertEquals(1L, all.countsByMode().get(AgentTeamRun.MODE_STATIC));

        TeamAdminService.RunsView activeOnly = adminService.runs(7, "active", "", 50);
        assertEquals(2, activeOnly.count());
        assertEquals(1L, activeOnly.countsByStatus().get(AgentTeamRun.STATUS_FALLBACK));

        TeamAdminService.RunsView empty = adminService.runs(7, "", "CANCELLED", 50);
        assertEquals(0, empty.count());
    }

    @Test
    @DisplayName("详情返回运行与节点记录，缺失运行报 400 级别错误")
    void detailAndMissingRun() {
        long runId = createRun(AgentTeamRun.MODE_ACTIVE, AgentTeamRun.STATUS_SUCCESS, "{}");

        TeamAdminService.DetailView detail = adminService.detail(runId);
        assertEquals(runId, detail.summary().runId());
        assertTrue(detail.records().isEmpty());

        assertThrows(IllegalArgumentException.class, () -> adminService.detail(9999L));
    }

    private long createRun(String mode, String status, String planJson) {
        return teamStore.createRun(
                new AgentTeamRun(
                        0L,
                        "plan-" + System.nanoTime(),
                        5L,
                        0L,
                        7L,
                        mode,
                        TeamIntent.TASK_CALCULATE,
                        TeamTemplate.CALC_VALIDATE.id(),
                        planJson,
                        null,
                        null,
                        status,
                        null,
                        null,
                        null,
                        null,
                        false,
                        0L,
                        LocalDateTime.now(),
                        null,
                        null,
                        null));
    }
}
