package io.agentscope.examples.payroll.db.mapper;

import io.agentscope.examples.payroll.domain.AuditEntry;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 审计日志的单语句读写（规格 §5.1 硬边界 2）：过滤条件在 XML 里用 {@code <if>} 拼接，
 * 不用 {@code ${}}；LIKE 的转义与 {@code %} 包裹由调用方（{@code H2AuditStore}）算好再传。
 */
public interface AuditMapper {

    int insert(AuditInsert row);

    List<AuditEntry> find(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("actor") String actorPattern,
            @Param("action") String actionPattern,
            @Param("resourceType") String resourceTypePattern,
            @Param("resourceId") String resourceIdPattern,
            @Param("result") String result,
            @Param("keyword") String keywordPattern,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long count(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("actor") String actorPattern,
            @Param("action") String actionPattern,
            @Param("resourceType") String resourceTypePattern,
            @Param("resourceId") String resourceIdPattern,
            @Param("result") String result,
            @Param("keyword") String keywordPattern);

    int purgeBefore(@Param("cutoff") LocalDateTime cutoff);
}