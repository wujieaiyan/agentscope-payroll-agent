package io.agentscope.examples.payroll.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class TopologyGuardTest {

    @Test
    void acceptsCompleteProdTopology() {
        assertThatCode(() -> guard(validEnv(), validEnvVars()).run(null))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSingleDeploymentMode() {
        MockEnvironment env = validEnv();
        env.setProperty("payroll.deployment.mode", "single");
        assertFailsWith(env, validEnvVars(), "payroll.deployment.mode");
    }

    @Test
    void rejectsH2Database() {
        MockEnvironment env = validEnv();
        env.setProperty("payroll.db.url", "jdbc:h2:file:./data/payroll");
        assertFailsWith(env, validEnvVars(), "禁止 H2");
    }

    @Test
    void rejectsMissingRedis() {
        MockEnvironment env = validEnv();
        env.setProperty("spring.data.redis.host", "");
        assertFailsWith(env, validEnvVars(), "spring.data.redis.host");
    }

    @Test
    void rejectsDisabledNacos() {
        Map<String, String> vars = validEnvVars();
        vars.put("PAYROLL_NACOS_ENABLED", "false");
        assertFailsWith(validEnv(), vars, "PAYROLL_NACOS_ENABLED");
    }

    @Test
    void rejectsHashEmbeddingFallback() {
        MockEnvironment env = validEnv();
        env.setProperty("payroll.kb.embedding-base-url", "");
        assertFailsWith(env, validEnvVars(), "HASH 降级");
    }

    @Test
    void rejectsMissingSecretKey() {
        Map<String, String> vars = validEnvVars();
        vars.remove("PAYROLL_SECRET_KEY");
        assertFailsWith(validEnv(), vars, "PAYROLL_SECRET_KEY");
    }

    @Test
    void aggregatesAllViolations() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("payroll.deployment.mode", "single");
        env.setProperty("payroll.db.url", "jdbc:h2:mem:test");
        Map<String, String> vars = new HashMap<>();
        vars.put("PAYROLL_NACOS_ENABLED", "false");
        assertThatThrownBy(() -> guard(env, vars).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("（8 项）")
                .hasMessageContaining("payroll.deployment.mode")
                .hasMessageContaining("禁止 H2")
                .hasMessageContaining("spring.data.redis.host")
                .hasMessageContaining("PAYROLL_NACOS")
                .hasMessageContaining("知识库 ES 地址")
                .hasMessageContaining("PAYROLL_SECRET_KEY");
    }

    private void assertFailsWith(MockEnvironment env, Map<String, String> vars, String expected) {
        assertThatThrownBy(() -> guard(env, vars).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expected);
    }

    private TopologyGuard guard(MockEnvironment env, Map<String, String> vars) {
        return new TopologyGuard(env, vars::get);
    }

    private MockEnvironment validEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("payroll.deployment.mode", "cluster");
        env.setProperty("payroll.db.url", "jdbc:mysql://db:3306/payroll");
        env.setProperty("spring.data.redis.host", "redis");
        env.setProperty("payroll.kb.es-enabled", "true");
        env.setProperty("payroll.kb.es-url", "http://es:9200");
        env.setProperty("payroll.memory.es-url", "http://es:9200");
        env.setProperty("payroll.kb.embedding-base-url", "http://ollama:11434");
        return env;
    }

    private Map<String, String> validEnvVars() {
        Map<String, String> vars = new HashMap<>();
        vars.put("PAYROLL_NACOS_SERVER", "http://nacos:8848");
        vars.put("PAYROLL_NACOS_ENABLED", "true");
        vars.put("PAYROLL_SECRET_KEY", Base64.getEncoder().encodeToString(new byte[32]));
        return vars;
    }
}
