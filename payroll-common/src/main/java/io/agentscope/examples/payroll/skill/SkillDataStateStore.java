package io.agentscope.examples.payroll.skill;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 数据工作区状态的读模型存储；写入只发生在观测到沙箱状态之后。 */
public interface SkillDataStateStore {

    Optional<SkillDataState> find(String skillId);

    Map<String, SkillDataState> findAll();

    void upsert(SkillDataState state);

    void delete(String skillId);

    /** 需要后台刷新的一批快照：只取非终态且观测时间早于截止时间，条数由实现限流。 */
    List<SkillDataState> pendingBefore(long cutoffEpochMillis, int limit);

    /** 测试与只读场景的兜底实现：不持久化，永远观测不到旧状态。 */
    static SkillDataStateStore none() {
        return new SkillDataStateStore() {
            @Override
            public Optional<SkillDataState> find(String skillId) {
                return Optional.empty();
            }

            @Override
            public Map<String, SkillDataState> findAll() {
                return Map.of();
            }

            @Override
            public void upsert(SkillDataState state) {}

            @Override
            public void delete(String skillId) {}

            @Override
            public List<SkillDataState> pendingBefore(long cutoffEpochMillis, int limit) {
                return List.of();
            }
        };
    }
}
