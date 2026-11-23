package io.agentscope.examples.payroll.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 智能体级模型覆盖，只影响控制面。未设置的字段表示继承连接或使用面默认值。
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record AgentModelSettings(
        String connectionId,
        String model,
        Integer numCtx,
        Integer numPredict,
        Double temperature,
        Integer timeoutSeconds) {

    public AgentModelSettings {
        connectionId = connectionId == null ? "" : connectionId.trim();
        model = model == null ? "" : model.trim();
    }

    public boolean isEmpty() {
        return connectionId.isBlank()
                && model.isBlank()
                && numCtx == null
                && numPredict == null
                && temperature == null
                && timeoutSeconds == null;
    }

    public static AgentModelSettings none() {
        return new AgentModelSettings("", "", null, null, null, null);
    }
}
