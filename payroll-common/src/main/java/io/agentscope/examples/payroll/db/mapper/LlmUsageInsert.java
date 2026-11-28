package io.agentscope.examples.payroll.db.mapper;

import io.agentscope.examples.payroll.usage.LlmUsageRecord;

/** 写入参数包装（规格 §5.0.4 D6）：record 不可变，自增主键回填到 {@code id}。 */
public final class LlmUsageInsert {

    private final LlmUsageRecord record;
    private long id;

    public LlmUsageInsert(LlmUsageRecord record) {
        this.record = record;
    }

    public LlmUsageRecord getRecord() {
        return record;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }
}