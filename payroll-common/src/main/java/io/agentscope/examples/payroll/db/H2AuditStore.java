package io.agentscope.examples.payroll.db;

import io.agentscope.examples.payroll.db.mapper.AuditInsert;
import io.agentscope.examples.payroll.db.mapper.AuditMapper;
import io.agentscope.examples.payroll.domain.AuditEntry;
import io.agentscope.examples.payroll.domain.AuditQuery;
import java.time.LocalDateTime;
import java.util.List;

/** 审计日志读写：按时间倒序分页，支持动作/资源/结果/关键字组合过滤；过滤条件由 Mapper XML 拼接。 */
public final class H2AuditStore {

    private final AuditMapper mapper;

    public H2AuditStore(AuditMapper mapper) {
        this.mapper = mapper;
    }

    public long insert(AuditEntry entry) {
        AuditInsert row = new AuditInsert(entry);
        mapper.insert(row);
        return row.getId();
    }

    public List<AuditEntry> find(AuditQuery query) {
        return mapper.find(
                query.from(),
                query.to(),
                like(query.actor()),
                like(query.action()),
                like(query.resourceType()),
                like(query.resourceId()),
                result(query.result()),
                like(query.keyword()),
                query.size(),
                query.offset());
    }

    public long count(AuditQuery query) {
        return mapper.count(
                query.from(),
                query.to(),
                like(query.actor()),
                like(query.action()),
                like(query.resourceType()),
                like(query.resourceId()),
                result(query.result()),
                like(query.keyword()));
    }

    public int purgeBefore(LocalDateTime cutoff) {
        return mapper.purgeBefore(cutoff);
    }

    private static String like(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return "%" + escapeLike(value.trim()) + "%";
    }

    private static String result(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase();
    }

    /** 反斜杠转义写法在 MySQL 中是非法字符串字面量，改用两个方言都接受的 '!'。 */
    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}