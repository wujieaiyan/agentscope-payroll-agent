package io.agentscope.examples.payroll.db.mapper.fixture;

/**
 * D6 upsert 探针载体：必须是可变对象，MyBatis 才能把自增主键写回 {@code id}。
 *
 * <p>选 {@code departments} 是因为它同时具备「自增主键 + 唯一业务键（name）」，
 * 能在 MySQL 上分别观察 INSERT 分支与 ON DUPLICATE KEY UPDATE 分支的 keyProperty 行为。
 */
public class DepartmentProbeInsert {

    private long id;
    private final String name;
    private final String description;

    public DepartmentProbeInsert(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }
}