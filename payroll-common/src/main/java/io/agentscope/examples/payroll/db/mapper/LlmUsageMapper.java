package io.agentscope.examples.payroll.db.mapper;

import io.agentscope.examples.payroll.usage.LlmUsageRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 模型用量事实的单语句读写；时间窗左闭右开，其余条件为空表示不过滤。 */
public interface LlmUsageMapper {

    int insert(LlmUsageInsert row);

    List<LlmUsageRecord> find(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("model") String model,
            @Param("plane") String plane,
            @Param("agent") String agent,
            @Param("userId") String userId,
            @Param("limit") int limit);
}