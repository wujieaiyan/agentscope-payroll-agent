package io.agentscope.examples.payroll.db.support;

import java.util.Set;
import java.util.TreeSet;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 启动断言：Mapper XML 漏加载是静默的，语句要到第一次调用才炸，所以启动阶段就把数量钉死。
 *
 * <p>只统计本项目命名空间下的语句：{@link org.apache.ibatis.session.Configuration#getMappedStatements()}
 * 为每个语句同时登记全名与短名，按前缀过滤后每语句只剩一条，数量才可解释。
 *
 * <p>放在 {@code db.support} 而不是在 {@code config} 里写 {@code @Bean}：装配类一旦出现
 * {@code SqlSessionFactory} 参数就违反「MyBatis 只能在 db.mapper / db.support 内使用」这条硬边界。
 */
@Component
public final class MappedStatementVerifier {

    /** P0-8 试点：AuditMapper 4 条 + LlmUsageMapper 2 条。每接入一个仓储批次，这里同步加数。 */
    public static final int EXPECTED_STATEMENTS = 6;

    private static final String PROJECT_NAMESPACE = "io.agentscope.examples.payroll.";
    private static final Logger log = LoggerFactory.getLogger(MappedStatementVerifier.class);

    public MappedStatementVerifier(SqlSessionFactory sqlSessionFactory) {
        verify(sqlSessionFactory);
    }

    private static void verify(SqlSessionFactory sqlSessionFactory) {
        Set<String> ids = new TreeSet<>();
        for (Object candidate : sqlSessionFactory.getConfiguration().getMappedStatements()) {
            if (candidate instanceof MappedStatement statement
                    && statement.getId().startsWith(PROJECT_NAMESPACE)) {
                ids.add(statement.getId());
            }
        }
        if (ids.size() != EXPECTED_STATEMENTS) {
            throw new IllegalStateException(
                    "MyBatis 语句数与预期不符：预期 "
                            + EXPECTED_STATEMENTS
                            + "，实际 "
                            + ids.size()
                            + "，已加载 "
                            + ids
                            + "；检查 mapper-locations 与 XML 的 namespace");
        }
        log.info("MyBatis 启动断言通过：{} 条语句 {}", ids.size(), ids);
    }
}