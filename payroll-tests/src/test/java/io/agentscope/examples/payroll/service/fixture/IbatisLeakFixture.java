package io.agentscope.examples.payroll.service.fixture;

import org.apache.ibatis.session.SqlSessionFactory;

/** 负例：服务层绕过 store 直接用 MyBatis（违反 IBATIS_ONLY_IN_MAPPER_OR_SUPPORT）。 */
public final class IbatisLeakFixture {

    private final SqlSessionFactory sqlSessionFactory;

    public IbatisLeakFixture(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    public boolean ready() {
        return sqlSessionFactory != null;
    }
}