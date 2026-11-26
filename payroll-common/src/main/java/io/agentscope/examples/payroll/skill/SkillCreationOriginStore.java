package io.agentscope.examples.payroll.skill;

import java.util.Map;

/** 技能由哪个模板创建；用于「最近使用的模板」和模板血缘追溯。 */
public interface SkillCreationOriginStore {

    record Origin(String templateId, String operator, long createdAt) {}

    void record(String skillId, String templateId, String operator);

    Map<String, Origin> origins();

    void delete(String skillId);

    static SkillCreationOriginStore none() {
        return new SkillCreationOriginStore() {
            @Override
            public void record(String skillId, String templateId, String operator) {}

            @Override
            public Map<String, Origin> origins() {
                return Map.of();
            }

            @Override
            public void delete(String skillId) {}
        };
    }
}
