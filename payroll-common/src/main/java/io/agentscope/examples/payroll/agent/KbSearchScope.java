package io.agentscope.examples.payroll.agent;

import java.util.List;

/** 本轮会话绑定的知识库快照；运行时按轮次写入 RuntimeContext，工具只读不写。 */
public record KbSearchScope(List<Long> seriesIds) {

    public KbSearchScope {
        seriesIds =
                seriesIds == null
                        ? List.of()
                        : List.copyOf(seriesIds.stream().filter(id -> id != null).toList());
    }

    public static KbSearchScope unbound() {
        return new KbSearchScope(List.of());
    }

    public boolean bound() {
        return !seriesIds.isEmpty();
    }

    public boolean contains(Long seriesId) {
        return seriesId != null && seriesIds.contains(seriesId);
    }
}
