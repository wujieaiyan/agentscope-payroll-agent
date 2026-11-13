package io.agentscope.examples.payroll.memory;

import java.util.List;

/** 文本嵌入客户端；失败时抛 RuntimeException，由调用方降级。 */
public interface EmbeddingClient {

    float[] embed(String text);

    List<float[]> embedAll(List<String> texts);

    /** 启动探测：模型已拉取且服务可用。 */
    boolean available();
}
