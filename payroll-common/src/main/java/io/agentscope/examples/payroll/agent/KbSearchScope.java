package io.agentscope.examples.payroll.agent;

import java.util.List;

/**
 * 本轮会话显式绑定的知识库快照；运行时按轮次写入 RuntimeContext，工具只读不写。
 *
 * <p>空集合表示未收窄：检索范围由服务端解析为当前账号可读的全部已启用库。
 */
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
