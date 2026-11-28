package io.agentscope.examples.payroll.db.support;

import io.agentscope.examples.payroll.db.DbMetrics;
import java.util.Properties;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.stereotype.Component;

/**
 * P0-9 指标入口：每条 Mapper 方法调用 +1，并记录一次耗时样本。
 *
 * <p>挂在 {@link Executor} 上而不是 {@code StatementHandler} 上：指标口径是「Mapper 调用」，
 * 含结果映射；同一进程内 4 参 query 与 6 参 query 的嵌套调用只走一次代理，不会重复计数。
 * Boot 会把容器里的 {@link Interceptor} 自动注册进 {@code SqlSessionFactory}。
 */
@Component
@Intercepts({
    @Signature(
            type = Executor.class,
            method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
    @Signature(
            type = Executor.class,
            method = "query",
            args = {
                MappedStatement.class,
                Object.class,
                RowBounds.class,
                ResultHandler.class,
                CacheKey.class,
                BoundSql.class
            }),
    @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})
})
public final class MybatisMetricsInterceptor implements Interceptor {

    private static final String MAPPER_PACKAGE = "io.agentscope.examples.payroll.db.mapper.";

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        String statementId = shortId(((MappedStatement) invocation.getArgs()[0]).getId());
        DbMetrics.mapperCalled(statementId);
        long start = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            DbMetrics.statementExecuted(statementId, System.nanoTime() - start);
        }
    }

    @Override
    public void setProperties(Properties properties) {
        // 无参数；实现该方法是 Interceptor 接口要求。
    }

    /** {@code io.agentscope.examples.payroll.db.mapper.AuditMapper.find} → {@code AuditMapper.find}。 */
    static String shortId(String mappedStatementId) {
        if (mappedStatementId.startsWith(MAPPER_PACKAGE)) {
            return mappedStatementId.substring(MAPPER_PACKAGE.length());
        }
        return mappedStatementId;
    }
}