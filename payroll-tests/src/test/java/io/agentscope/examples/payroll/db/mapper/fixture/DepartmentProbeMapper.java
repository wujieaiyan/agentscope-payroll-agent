package io.agentscope.examples.payroll.db.mapper.fixture;

import org.apache.ibatis.annotations.Param;

/** D6 upsert 探针：只在 MySQL 闸门里加载，SQL 使用 MySQL 专有的 ON DUPLICATE KEY UPDATE。 */
public interface DepartmentProbeMapper {

    int upsertDepartment(DepartmentProbeInsert row);

    int deleteDepartment(@Param("name") String name);
}