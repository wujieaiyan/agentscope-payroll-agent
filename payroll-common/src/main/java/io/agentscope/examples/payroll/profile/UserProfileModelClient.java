package io.agentscope.examples.payroll.profile;

/** 抽取的模型增强端口：实现方负责选择控制面轻量模型并隔离调用上下文。 */
public interface UserProfileModelClient {

    boolean enabled();

    /**
     * 执行一次无工具的单轮抽取。
     *
     * @return 模型返回的 JSON 文本（顶层数组或 {@code {"items":[...]}}）；不可用或失败返回 null
     */
    String extract(String instruction, String question, String sessionKey);

    static UserProfileModelClient disabled() {
        return new UserProfileModelClient() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public String extract(String instruction, String question, String sessionKey) {
                return null;
            }
        };
    }
}
