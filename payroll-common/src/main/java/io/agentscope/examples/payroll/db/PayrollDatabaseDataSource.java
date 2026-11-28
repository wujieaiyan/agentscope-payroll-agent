package io.agentscope.examples.payroll.db;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * 把自研引导层包成 Spring {@link DataSource}：连接获取直接委托
 * {@link PayrollDatabase#connection()}，适配器自身不改 autoCommit。
 *
 * <p>事务路径由 {@code DataSourceTransactionManager} 在 doBegin 时置
 * {@code autoCommit=false} 并在提交/回滚后还原；非事务路径保持现状（MySQL 归还池，
 * H2 由调用方关闭）。
 */
public final class PayrollDatabaseDataSource extends AbstractDataSource {

    private final PayrollDatabase database;

    public PayrollDatabaseDataSource(PayrollDatabase database) {
        if (database == null) {
            throw new IllegalArgumentException("PayrollDatabase 不能为空");
        }
        this.database = database;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return database.connection();
    }

    /** 账号固定来自 PayrollDatabase 的配置，忽略这里的入参。 */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return database.connection();
    }

    public PayrollDatabase database() {
        return database;
    }

    /** Spring 的默认实现直接抛 UnsupportedOperationException，这里给出无害值。 */
    @Override
    public java.io.PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(java.io.PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(int seconds) {
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }
}