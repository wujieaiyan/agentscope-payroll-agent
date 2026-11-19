package io.agentscope.examples.payroll.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModelOutboundPolicyTest {

    @Test
    @DisplayName("本机 Ollama 端口默认放行，其余回环与私网被拒绝")
    void privateAndLoopbackDeniedByDefault() {
        ModelOutboundPolicy policy = new ModelOutboundPolicy(List.of());

        assertNull(policy.violation(URI.create("http://127.0.0.1:11434")));
        assertNull(policy.violation(URI.create("http://localhost:11434/api")));

        assertNotNull(policy.violation(URI.create("http://127.0.0.1:8080")));
        assertNotNull(policy.violation(URI.create("http://192.168.1.10:8000")));
        assertNotNull(policy.violation(URI.create("http://10.0.0.9")));
    }

    @Test
    @DisplayName("白名单支持 host 与 host:port 两种粒度")
    void allowListMatchesHostOrPort() {
        ModelOutboundPolicy hostOnly =
                new ModelOutboundPolicy(List.of("127.0.0.1"));
        assertNull(hostOnly.violation(URI.create("http://127.0.0.1:8080")));

        ModelOutboundPolicy portScoped =
                new ModelOutboundPolicy(List.of("127.0.0.1:8080"));
        assertNull(portScoped.violation(URI.create("http://127.0.0.1:8080")));
        assertNotNull(portScoped.violation(URI.create("http://127.0.0.1:8081")));

        ModelOutboundPolicy named = new ModelOutboundPolicy(List.of("example.com"));
        assertNull(named.violation(URI.create("https://example.com")));
    }

    @Test
    @DisplayName("非 http(s) 协议与缺失主机被拒绝")
    void rejectsBadSchemesAndHosts() {
        ModelOutboundPolicy policy = new ModelOutboundPolicy(List.of("example.com"));

        assertTrue(policy.violation(URI.create("ftp://example.com")).contains("协议"));
        assertTrue(policy.violation(URI.create("file:///etc/passwd")).contains("协议"));
        assertNotNull(policy.violation(URI.create("https:///path")));
        assertNotNull(policy.violation(null));
    }

    @Test
    @DisplayName("check 抛出统一错误码并触发审计回调")
    void checkThrowsAndReportsDenial() {
        AtomicReference<String> reason = new AtomicReference<>();
        ModelOutboundPolicy policy =
                new ModelOutboundPolicy(List.of(), (uri, why) -> reason.set(why));

        ModelConfigException error =
                assertThrows(
                        ModelConfigException.class,
                        () -> policy.check("http://127.0.0.1:8080/v1"));

        assertEquals(ModelConfigException.OUTBOUND_BLOCKED, error.code());
        assertNotNull(reason.get());
        assertTrue(reason.get().contains("环") || reason.get().contains("私网"), reason.get());
    }

    @Test
    @DisplayName("审计回调自身失败不影响拒绝")
    void failingDenialSinkStillBlocks() {
        ModelOutboundPolicy policy =
                new ModelOutboundPolicy(
                        List.of(),
                        (uri, why) -> {
                            throw new IllegalStateException("audit down");
                        });

        ModelConfigException error =
                assertThrows(
                        ModelConfigException.class,
                        () -> policy.check("http://10.0.0.9/v1"));
        assertEquals(ModelConfigException.OUTBOUND_BLOCKED, error.code());
    }

    @Test
    @DisplayName("非法 URL 同样按出网拒绝处理")
    void malformedUrlBlocked() {
        ModelOutboundPolicy policy = new ModelOutboundPolicy(List.of());

        ModelConfigException error =
                assertThrows(ModelConfigException.class, () -> policy.check("::not-a-url"));
        assertEquals(ModelConfigException.OUTBOUND_BLOCKED, error.code());
    }
}
