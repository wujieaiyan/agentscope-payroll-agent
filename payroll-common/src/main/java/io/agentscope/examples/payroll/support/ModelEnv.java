package io.agentscope.examples.payroll.support;

/** 模型标识的读取入口：智能体运行期与对话抽象共用，避免 common 依赖智能体工厂。 */
public final class ModelEnv {

    public static final String DEFAULT_MODEL = "ollama:qwen2.5:3b-instruct";

    private ModelEnv() {}

    public static String modelId() {
        String configured = System.getenv("PAYROLL_MODEL");
        return configured == null || configured.isBlank() ? DEFAULT_MODEL : configured.trim();
    }
}
