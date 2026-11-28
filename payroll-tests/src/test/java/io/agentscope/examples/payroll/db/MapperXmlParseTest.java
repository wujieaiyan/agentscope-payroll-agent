package io.agentscope.examples.payroll.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.examples.payroll.db.support.MappedStatementVerifier;
import io.agentscope.examples.payroll.db.support.PayrollMappers;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 解析全部 Mapper XML：语法或 namespace 错误必须在构建期暴露，不能等到第一次调用。
 *
 * <p>装配走 {@link PayrollMappers}，与其它测试共用同一份 XML 与配置（规格 §5.0.5）。
 */
class MapperXmlParseTest {

    @Test
    @DisplayName("全部 XML 解析通过，语句数等于启动断言预期值")
    void parsesAllMapperXml() {
        PayrollDatabase database = PayrollDatabase.inMemory("mapper-xml-" + UUID.randomUUID());
        try {
            PayrollMappers mappers = PayrollMappers.forDatabase(database);
            SqlSessionFactory factory = mappers.sqlSessionFactory();
            assertNotNull(factory);

            Set<String> ids = new TreeSet<>();
            for (Object candidate : factory.getConfiguration().getMappedStatements()) {
                if (candidate instanceof MappedStatement statement
                        && statement.getId().startsWith("io.agentscope.examples.payroll.")) {
                    ids.add(statement.getId());
                }
            }
            assertEquals(
                    MappedStatementVerifier.EXPECTED_STATEMENTS,
                    ids.size(),
                    "XML 数量与预期不一致：" + ids);
        } finally {
            database.close();
        }
    }
}