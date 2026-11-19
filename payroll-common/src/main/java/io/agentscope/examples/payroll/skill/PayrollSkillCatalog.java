package io.agentscope.examples.payroll.skill;

import io.agentscope.core.skill.AgentSkill;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 本仓库自有的技能目录扩展接口（规格 4.2）。
 *
 * <p>外部 {@code AgentSkillRepository} 来自 harness，不能给它加方法；本地实现类同时实现
 * 本接口，调用侧用 {@code repository instanceof PayrollSkillCatalog} 能力探测，
 * 取不到时回退逐条读取。目录只含元数据，正文由 {@link #getSkills(Collection)} 批量按需取。
 */
public interface PayrollSkillCatalog {

    /** 只读元数据列表；列表阶段不逐条拉正文。 */
    List<SkillManifest> listManifests();

    /** 批量取正文，返回顺序与入参一致，缺失的 id 被跳过。 */
    List<AgentSkill> getSkills(Collection<String> ids);

    /** 单条元数据，供管理台与缓存指纹使用。 */
    Optional<SkillManifest> manifest(String id);

    /** id → 裸名称，供工具组激活中间件 O(1) 解析。 */
    Map<String, String> nameById();
}
