package io.agentscope.examples.payroll.db.mapper.fixture;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;

/**
 * 异常映射探针：只用于验证约束冲突在 MyBatis-Spring 下抛出的异常类型。
 *
 * <p>选 {@code job_lease}（PK {@code job_name}、无外键）是因为它的唯一索引语义最单一，
 * 不会把外键或乐观锁的错误混进来。
 */
public interface LeaseProbeMapper {

    int insert(
            @Param("jobName") String jobName,
            @Param("owner") String owner,
            @Param("acquiredAt") LocalDateTime acquiredAt,
            @Param("expiresAt") LocalDateTime expiresAt);

    /** MySQL 闸门用的清理路径：探针行不能留在部门库里。 */
    int delete(@Param("jobName") String jobName);
}