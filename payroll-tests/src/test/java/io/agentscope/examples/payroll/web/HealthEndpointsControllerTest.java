package io.agentscope.examples.payroll.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class HealthEndpointsControllerTest {

    private final HealthEndpoint endpoint = mock(HealthEndpoint.class);
    private final HealthEndpointsController controller = new HealthEndpointsController(endpoint);

    @Test
    @DisplayName("liveness 为 UP 时 /livez 返回 200 且只带 status")
    void livezUp() {
        when(endpoint.healthForPath("liveness")).thenReturn(Health.up().build());

        ResponseEntity<Map<String, Object>> response = controller.livez();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("UP", response.getBody().get("status"));
        assertEquals("liveness", response.getBody().get("group"));
        assertEquals(2, response.getBody().size());
    }

    @Test
    @DisplayName("readiness 为 DOWN 时 /readyz 返回 503")
    void readyzDown() {
        when(endpoint.healthForPath("readiness")).thenReturn(Health.down().build());

        ResponseEntity<Map<String, Object>> response = controller.readyz();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("DOWN", response.getBody().get("status"));
    }

    @Test
    @DisplayName("分组缺失或不可读时返回 503 UNKNOWN，避免误判为就绪")
    void unknownGroup() {
        when(endpoint.healthForPath("readiness"))
                .thenThrow(new IllegalStateException("group not configured"));

        ResponseEntity<Map<String, Object>> response = controller.readyz();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("UNKNOWN", response.getBody().get("status"));
    }
}
