package io.agentscope.examples.payroll.memory;

import java.util.Optional;

/**
 * 回答缓存的最小接口：默认关闭，测试可注入固定实现。
 *
 * <p>缓存只承载稳定只读问答，命中仍由调用方完成权限与会话归属校验。
 */
public interface AnswerCache extends AutoCloseable {

    record Hit(String answer, String type, double score) {
    }

    boolean enabled();

    /** scope 由调用方按「角色 + 模型 + 权限指纹」拼接；未命中返回空。 */
    Optional<Hit> lookup(String scope, String question);

    void store(String scope, String question, String answer);

    /** 负反馈等场景按问题删除，失败不影响主链路。 */
    default void invalidate(String scope, String question) {
    }

    @Override
    default void close() {
    }

    static AnswerCache disabled() {
        return new AnswerCache() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public Optional<Hit> lookup(String scope, String question) {
                return Optional.empty();
            }

            @Override
            public void store(String scope, String question, String answer) {
            }
        };
    }
}
