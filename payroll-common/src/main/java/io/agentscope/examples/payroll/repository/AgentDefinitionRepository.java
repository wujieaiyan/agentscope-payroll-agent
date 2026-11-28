package io.agentscope.examples.payroll.repository;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 智能体定义持久化端口（M5.1）：数据库为权威，Nacos 只保留为变更通知通道。
 *
 * <p>revision 沿用现有内容指纹语义：写前重读比对，不一致抛 409；
 * 行级 revision 在每次更新时递增，整份目录指纹由全部行重新计算。
 */
public interface AgentDefinitionRepository {

    /** 全量快照：条目按 id 排序，revision 覆盖整份目录。 */
    record Snapshot(List<AgentDefinition> items, String revision) {}

    /** 版本时间线一行；{@code current} 标记当前生效版本（追加式历史里即最大 version_no）。 */
    record Version(
            int versionNo,
            String changeNote,
            String createdBy,
            LocalDateTime createdAt,
            boolean current) {}

    Snapshot load();

    Optional<AgentDefinition> find(String id);

    /** 事务内写前重读并比对 expectedRevision；同一 id 已存在时行级 revision 递增。 */
    AgentDefinition save(AgentDefinition definition, String operator, String expectedRevision);

    /**
     * 同 {@link #save(AgentDefinition, String, String)}，并把变更说明写进追加的版本行。
     * 端口给默认实现，让没有历史能力的存储源不必实现空重载。
     */
    default AgentDefinition save(
            AgentDefinition definition,
            String operator,
            String expectedRevision,
            String changeNote) {
        return save(definition, operator, expectedRevision);
    }

    /**
     * 版本时间线，按 version_no 倒序；没有历史（如导入前的旧数据）返回空列表。
     */
    List<Version> versions(String id);

    /**
     * 回滚：追加一条 payload 复用目标版本的新版本行，再把定义行切过去，历史行不改。
     * 目标版本不存在抛 {@code PayrollException}，revision 不一致抛 409。
     */
    AgentDefinition rollback(
            String id, int versionNo, String expectedRevision, String operator);

    /** 删除成功返回 true；条目不存在返回 false；revision 不一致抛 409。 */
    boolean delete(String id, String expectedRevision);

    /** 表为空时一次性导入并返回条数；已有数据返回 0（幂等）。 */
    int importIfEmpty(List<AgentDefinition> definitions, String operator);
}
