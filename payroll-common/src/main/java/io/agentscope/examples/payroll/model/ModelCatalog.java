package io.agentscope.examples.payroll.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.Optional;

/** {@code payroll-models.json} 的完整内容：默认连接 + 连接列表 + revision。 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record ModelCatalog(
        int version, long revision, ModelDefaults defaults, List<ModelConnection> connections) {

    public static final int CURRENT_VERSION = 1;

    public ModelCatalog {
        defaults = defaults == null ? ModelDefaults.EMPTY : defaults;
        connections = connections == null ? List.of() : List.copyOf(connections);
    }

    public static ModelCatalog empty() {
        return new ModelCatalog(CURRENT_VERSION, 0, ModelDefaults.EMPTY, List.of());
    }

    public Optional<ModelConnection> connection(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return connections.stream().filter(item -> item.id().equals(id)).findFirst();
    }
}
