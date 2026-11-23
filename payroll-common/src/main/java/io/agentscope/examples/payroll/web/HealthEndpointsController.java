package io.agentscope.examples.payroll.web;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 编排探针的薄别名：内部映射 Actuator 的 liveness/readiness 分组，只回状态码与 status。
 *
 * <p>{@code /livez} 只看进程存活；{@code /readyz} 由 readiness 分组决定（DB + Redis），
 * 不带详情，避免把依赖地址暴露给未鉴权调用方。
 */
@RestController
public class HealthEndpointsController {

    private static final Logger log = LoggerFactory.getLogger(HealthEndpointsController.class);

    private final HealthEndpoint healthEndpoint;

    public HealthEndpointsController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/livez")
    public ResponseEntity<Map<String, Object>> livez() {
        return probe("liveness");
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, Object>> readyz() {
        return probe("readiness");
    }

    private ResponseEntity<Map<String, Object>> probe(String group) {
        HealthComponent component;
        try {
            component = healthEndpoint.healthForPath(group);
        } catch (RuntimeException e) {
            log.warn("[健康] 无法读取 {} 分组：{}", group, e.getMessage());
            component = null;
        }
        if (component == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "UNKNOWN", "group", group));
        }
        Status status = component.getStatus();
        String code = status == null ? "UNKNOWN" : status.getCode();
        ResponseEntity.BodyBuilder builder =
                Status.UP.equals(status)
                        ? ResponseEntity.ok()
                        : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE);
        return builder.body(Map.of("status", code, "group", group));
    }
}
