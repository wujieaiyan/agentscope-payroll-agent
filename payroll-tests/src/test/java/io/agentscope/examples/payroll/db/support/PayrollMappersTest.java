package io.agentscope.examples.payroll.db.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.db.PayrollDatabaseDataSource;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;

/**
 * 测试装配工厂本身的契约：配置必须逐项等于生产 {@code mybatis.configuration}，
 * 否则测试会在与生产不同的会话缓存/超时下跑，把差异藏起来。
 */
class PayrollMappersTest {

    @Test
    @DisplayName("装配结果与生产配置一致，连接来源仍是 PayrollDatabase")
    void buildsFactoryWithProductionConfiguration() {
        PayrollDatabase database = PayrollDatabase.inMemory("mappers-contract-" + UUID.randomUUID());
        try {
            PayrollMappers mappers = PayrollMappers.forDatabase(database);
            Configuration configuration = mappers.sqlSessionFactory().getConfiguration();

            assertFalse(configuration.isMapUnderscoreToCamelCase(), "禁止隐式下划线推断");
            assertEquals(LocalCacheScope.STATEMENT, configuration.getLocalCacheScope(), "不开一级会话缓存");
            assertEquals(30, configuration.getDefaultStatementTimeout(), "单语句超时与 yml 一致");
            assertInstanceOf(
                    SpringManagedTransactionFactory.class,
                    configuration.getEnvironment().getTransactionFactory(),
                    "事务必须能被 Spring 的 DataSourceTransactionManager 接管");

            PayrollDatabaseDataSource dataSource =
                    assertInstanceOf(PayrollDatabaseDataSource.class, configuration.getEnvironment().getDataSource());
            assertSame(database, dataSource.database(), "MyBatis 只消费 PayrollDatabase 给的连接");
        } finally {
            database.close();
        }
    }
}