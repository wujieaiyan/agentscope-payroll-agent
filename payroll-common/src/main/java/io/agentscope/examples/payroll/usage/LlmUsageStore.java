package io.agentscope.examples.payroll.usage;

import java.util.List;

/** 用量事实存储；按时间倒序返回，limit 为单次查询上限。 */
public interface LlmUsageStore {

    long insert(LlmUsageRecord record);

    List<LlmUsageRecord> find(LlmUsageQuery query, int limit);
}
