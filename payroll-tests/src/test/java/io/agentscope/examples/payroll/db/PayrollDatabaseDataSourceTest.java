package io.agentscope.examples.payroll.db;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayrollDatabaseDataSourceTest {

    private PayrollDatabase database;
    private PayrollDatabaseDataSource dataSource;

    @BeforeEach
    void setUp() {
        database = PayrollDatabase.inMemory("ds-" + System.nanoTime());
        dataSource = new PayrollDatabaseDataSource(database);
    }

    @Test
    @DisplayName("连接委托给 PayrollDatabase，非事务路径不改 autoCommit")
    void delegatesConnectionsUntouched() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertFalse(connection.isClosed());
            assertTrue(connection.getAutoCommit(), "适配器不得介入 autoCommit");
        }
        try (Connection connection = dataSource.getConnection("ignored", "ignored")) {
            assertFalse(connection.isClosed());
        }
    }

    @Test
    @DisplayName("每次 getConnection 计入 payroll.db.connection.acquired")
    void countsAcquisitions() throws SQLException {
        long before = DbMetrics.connectionAcquiredCount();
        try (Connection ignored = dataSource.getConnection();
                Connection ignored2 = dataSource.getConnection("u", "p")) {
            assertEquals(before + 2, DbMetrics.connectionAcquiredCount());
        }
    }

    @Test
    @DisplayName("unwrap 只支持自身，元数据方法返回无害默认值")
    void unwrapOnlySelf() throws SQLException {
        assertSame(
                dataSource,
                assertDoesNotThrow(() -> dataSource.unwrap(PayrollDatabaseDataSource.class)));
        assertTrue(dataSource.isWrapperFor(PayrollDatabaseDataSource.class));
        assertFalse(dataSource.isWrapperFor(Connection.class));
        assertThrows(SQLException.class, () -> dataSource.unwrap(Connection.class));
        assertNull(dataSource.getLogWriter());
        assertEquals(0, dataSource.getLoginTimeout());
    }

    @Test
    @DisplayName("缺少 PayrollDatabase 直接拒绝构造")
    void rejectsNullDatabase() {
        assertThrows(IllegalArgumentException.class, () -> new PayrollDatabaseDataSource(null));
    }
}