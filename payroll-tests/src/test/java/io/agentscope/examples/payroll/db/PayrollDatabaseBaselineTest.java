package io.agentscope.examples.payroll.db;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 历史 H2 库的补建边界：只重放不高于基线的版本。
 *
 * <p>越界重放会把新库「剧透」成对象已存在但没有迁移记录，Flyway 真正执行同一版本时撞
 * {@code CREATE TABLE} 直接启动失败。
 */
class PayrollDatabaseBaselineTest {

    @Test
    @DisplayName("基线判定放行不高于基线的版本，拦住后续版本")
    void replayStopsAtBaselineVersion() {
        assertTrue(PayrollDatabase.appliedBaseline("1", "21"));
        assertTrue(PayrollDatabase.appliedBaseline("21", "21"));
        assertTrue(PayrollDatabase.appliedBaseline("21.0", "21"));
        assertFalse(PayrollDatabase.appliedBaseline("21.1", "21"));
        assertFalse(PayrollDatabase.appliedBaseline("22", "21"));
        assertFalse(PayrollDatabase.appliedBaseline("100", "21"));
    }
}
