package io.agentscope.examples.payroll.db;

import io.agentscope.examples.payroll.db.mapper.LlmUsageInsert;
import io.agentscope.examples.payroll.db.mapper.LlmUsageMapper;
import io.agentscope.examples.payroll.usage.LlmUsageQuery;
import io.agentscope.examples.payroll.usage.LlmUsageRecord;
import io.agentscope.examples.payroll.usage.LlmUsageStore;
import java.util.List;

/** 模型用量事实的 H2/MySQL 读写：一次模型调用一行，按时间倒序查询。 */
public final class H2LlmUsageStore implements LlmUsageStore {

    private final LlmUsageMapper mapper;

    public H2LlmUsageStore(LlmUsageMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long insert(LlmUsageRecord record) {
        LlmUsageInsert row = new LlmUsageInsert(record);
        mapper.insert(row);
        return row.getId();
    }

    @Override
    public List<LlmUsageRecord> find(LlmUsageQuery query, int limit) {
        return mapper.find(
                query.from(),
                query.to(),
                query.model(),
                query.plane(),
                query.agent(),
                query.userId(),
                Math.max(1, limit));
    }
}