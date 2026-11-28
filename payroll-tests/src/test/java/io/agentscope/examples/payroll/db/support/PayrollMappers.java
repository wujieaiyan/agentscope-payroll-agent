package io.agentscope.examples.payroll.db.support;

import io.agentscope.examples.payroll.db.H2AuditStore;
import io.agentscope.examples.payroll.db.H2LlmUsageStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.db.PayrollDatabaseDataSource;
import io.agentscope.examples.payroll.db.mapper.AuditMapper;
import io.agentscope.examples.payroll.db.mapper.LlmUsageMapper;
import java.io.IOException;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.JdbcType;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 测试侧唯一的 MyBatis 装配入口（规格 §5.0.5）：一个测试里不再各自写 {@code SqlSessionFactory}，
 * 生产装配在 {@code PayrollCoreConfiguration}，这里只复用同一份 XML 与同一组配置项。
 *
 * <p>配置项刻意与 {@code application-common.yml} 的 {@code mybatis.configuration} 逐项对齐：
 * 测试若跑在一份不同的会话缓存/超时设置下，会掩盖生产侧才出现的行为差。
 */
public final class PayrollMappers {

    private static final String MAPPER_LOCATIONS = "classpath*:mapper/**/*.xml";

    private final SqlSessionFactory sqlSessionFactory;
    private final SqlSessionTemplate template;

    private PayrollMappers(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.template = new SqlSessionTemplate(sqlSessionFactory);
    }

    /** 为一份测试库建装配；库的生命周期由调用方持有，这里不关闭它。 */
    public static PayrollMappers forDatabase(PayrollDatabase database) {
        return build(database, true, MAPPER_LOCATIONS);
    }

    /**
     * 生产 Mapper 全集 + 指定探针 XML：MySQL 闸门要同时用正式仓储与约束/upsert 夹具。
     * 不参与启动语句数断言——探针语句不属于生产集合。
     */
    public static PayrollMappers withProbes(PayrollDatabase database, String... probeLocations) {
        String[] locations = new String[probeLocations.length + 1];
        locations[0] = MAPPER_LOCATIONS;
        System.arraycopy(probeLocations, 0, locations, 1, probeLocations.length);
        return build(database, false, locations);
    }

    /**
     * 只给异常映射探针用：加载指定的测试夹具 XML，且不参与启动语句数断言
     * （断言口径是生产 Mapper 集合，夹具不属于它）。
     */
    public static PayrollMappers forProbe(PayrollDatabase database, String... mapperLocations) {
        return build(database, false, mapperLocations);
    }

    /** 取 Mapper：接口未在 XML namespace 里登记时补登记，避免测试为同一接口重复注册。 */
    public <T> T mapper(Class<T> type) {
        Configuration configuration = sqlSessionFactory.getConfiguration();
        if (!configuration.hasMapper(type)) {
            configuration.addMapper(type);
        }
        return template.getMapper(type);
    }

    public H2AuditStore auditStore() {
        return new H2AuditStore(mapper(AuditMapper.class));
    }

    public H2LlmUsageStore llmUsageStore() {
        return new H2LlmUsageStore(mapper(LlmUsageMapper.class));
    }

    public SqlSessionFactory sqlSessionFactory() {
        return sqlSessionFactory;
    }

    public SqlSessionTemplate template() {
        return template;
    }

    private static PayrollMappers build(
            PayrollDatabase database, boolean verifyStatements, String... mapperLocations) {
        Objects.requireNonNull(database, "database");
        DataSource dataSource = new PayrollDatabaseDataSource(database);
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(false);
        configuration.setJdbcTypeForNull(JdbcType.NULL);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setDefaultStatementTimeout(30);

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        // 与 Boot 侧一致：容器里的 Interceptor 会注册进 SqlSessionFactory，测试同样挂上以便量基线。
        factoryBean.setPlugins(new MybatisMetricsInterceptor());
        try {
            factoryBean.setMapperLocations(load(mapperLocations));
            SqlSessionFactory factory = factoryBean.getObject();
            if (verifyStatements) {
                // 与生产启动同一道断言：XML 漏加载在测试里也要当场炸，而不是等某个用例写出空表。
                new MappedStatementVerifier(factory);
            }
            return new PayrollMappers(factory);
        } catch (IOException e) {
            throw new IllegalStateException("读取 Mapper XML 失败：" + String.join(",", mapperLocations), e);
        } catch (Exception e) {
            throw new IllegalStateException("构建测试 SqlSessionFactory 失败", e);
        }
    }

    private static Resource[] load(String... mapperLocations) throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = new Resource[0];
        for (String location : mapperLocations) {
            Resource[] found = resolver.getResources(location);
            Resource[] merged = new Resource[resources.length + found.length];
            System.arraycopy(resources, 0, merged, 0, resources.length);
            System.arraycopy(found, 0, merged, resources.length, found.length);
            resources = merged;
        }
        return resources;
    }
}