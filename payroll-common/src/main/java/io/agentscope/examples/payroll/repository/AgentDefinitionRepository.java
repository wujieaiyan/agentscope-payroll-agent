package io.agentscope.examples.payroll.repository;

import io.agentscope.examples.payroll.domain.AgentDefinition;
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

    Snapshot load();

    Optional<AgentDefinition> find(String id);

    /** 事务内写前重读并比对 expectedRevision；同一 id 已存在时行级 revision 递增。 */
    AgentDefinition save(AgentDefinition definition, String operator, String expectedRevision);

    /** 删除成功返回 true；条目不存在返回 false；revision 不一致抛 409。 */
    boolean delete(String id, String expectedRevision);

    /** 表为空时一次性导入并返回条数；已有数据返回 0（幂等）。 */
    int importIfEmpty(List<AgentDefinition> definitions, String operator);
}
