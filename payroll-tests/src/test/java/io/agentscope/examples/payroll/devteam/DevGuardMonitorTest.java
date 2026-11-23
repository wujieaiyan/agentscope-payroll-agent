package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 任务级护栏记录（P0-6）：有界事件、单行摘要与空白折叠。 */
class DevGuardMonitorTest {

    @Test
    @DisplayName("未命中时为空，命中后记录动作/路径/原因")
    void recordsEvents() {
        DevGuardMonitor monitor = new DevGuardMonitor();
        assertFalse(monitor.breached());
        assertEquals("", monitor.summary());

        monitor.breach("read", "data/payroll.mv.db", "命中路径黑名单");

        assertTrue(monitor.breached());
        assertEquals(1, monitor.count());
        assertEquals("read:data/payroll.mv.db（命中路径黑名单）", monitor.events().get(0));
        assertEquals("read:data/payroll.mv.db（命中路径黑名单）", monitor.summary());
    }

    @Test
    @DisplayName("事件数量封顶 20 条，但不影响已命中状态")
    void capsEvents() {
        DevGuardMonitor monitor = new DevGuardMonitor();
        for (int i = 0; i < DevGuardMonitor.MAX_EVENTS + 5; i++) {
            monitor.breach("read", "docs/f" + i + ".md", "越权");
        }
        assertEquals(DevGuardMonitor.MAX_EVENTS, monitor.count());
        assertTrue(monitor.breached());
    }

    @Test
    @DisplayName("摘要只保留前三条，折叠空白并提示剩余条数")
    void summaryTrimsAndCountsRest() {
        DevGuardMonitor monitor = new DevGuardMonitor();
        monitor.breach("write", " docs/a.md ", "越权  写入\na");
        monitor.breach("edit", "docs/b.md", "越权");
        monitor.breach("delete", "docs/c.md", "越权");
        monitor.breach("move", "docs/d.md", "越权");

        String summary = monitor.summary();
        assertTrue(summary.startsWith("write:docs/a.md（越权 写入 a）"), summary);
        assertTrue(summary.contains("；另有 1 次"), summary);
        assertFalse(summary.contains("docs/d.md"), summary);
    }
}
