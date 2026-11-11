package io.agentscope.examples.payroll.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具读写分类的本地覆盖，用于服务端 readOnlyHint 谎报或漏报时的兜底。
 *
 * <pre>{@code
 * payroll:
 *   tools:
 *     safety-overrides:
 *       delete_employee: MUTATING
 *       search_policy: READ_ONLY
 * }</pre>
 */
@ConfigurationProperties(prefix = "payroll.tools")
public record ToolSafetyProperties(Map<String, String> safetyOverrides) {

    public ToolSafetyProperties {
        safetyOverrides = safetyOverrides == null ? Map.of() : Map.copyOf(safetyOverrides);
    }
}
