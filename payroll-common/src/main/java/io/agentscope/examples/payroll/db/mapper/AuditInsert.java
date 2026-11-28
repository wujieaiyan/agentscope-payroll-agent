package io.agentscope.examples.payroll.db.mapper;

import io.agentscope.examples.payroll.domain.AuditEntry;

/**
 * 写入参数包装（规格 §5.0.4 D6）：{@link AuditEntry} 是 record，不可变，主键回填必须写到可变属性上。
 * 不用 {@code <selectKey>} 兜底，也就避免「单独查 LAST_INSERT_ID()」在无事务时取到别的会话。
 */
public final class AuditInsert {

    private final AuditEntry entry;
    private long id;

    public AuditInsert(AuditEntry entry) {
        this.entry = entry;
    }

    public AuditEntry getEntry() {
        return entry;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }
}