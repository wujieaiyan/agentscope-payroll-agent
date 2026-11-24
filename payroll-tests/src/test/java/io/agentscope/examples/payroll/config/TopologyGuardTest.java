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
    void warnsButDoesNotBlockByDefault() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("payroll.guard.strict", "warn");
        assertThatCode(() -> guard(env, new HashMap<>()).run(null))
                .doesNotThrowAnyException();
    }

    @Test
    void failModeBlocksAndAggregatesAllViolations() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("payroll.guard.strict", "fail");
        env.setProperty("payroll.deployment.mode", "cluster");
        env.setProperty("payroll.db.url", "jdbc:h2:mem:test");
        Map<String, String> vars = new HashMap<>();
        vars.put("PAYROLL_NACOS_ENABLED", "false");
        assertThatThrownBy(() -> guard(env, vars).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payroll.guard.strict=fail")
                .hasMessageContaining("启动拓扑校验发现 10 项问题")
                .hasMessageContaining("禁止 H2")
                .hasMessageContaining("spring.data.redis.host")
                .hasMessageContaining("PAYROLL_NACOS")
                .hasMessageContaining("知识库 ES 地址")
                .hasMessageContaining("PAYROLL_SECRET_KEY")
                .hasMessageContaining("payroll.auth.admin-password")
                .hasMessageContaining("payroll.internal.token")
                .hasMessageContaining("payroll.node-role");
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
    void rejectsDefaultAdminPasswordWithoutEchoingIt() {
        MockEnvironment env = validEnv();
        env.setProperty("payroll.auth.admin-password", "admin123");
        assertThatThrownBy(() -> guard(env, validEnvVars()).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payroll.auth.admin-password")
                .hasMessageNotContaining("admin123");
    }

    @Test
    void rejectsMissingInternalTokenOnWebNode() {
        Map<String, String> vars = validEnvVars();
        vars.remove("PAYROLL_AGENT_RUNTIME_TOKEN");
        assertFailsWith(validEnv(), vars, "payroll.internal.token");
    }

    @Test
    void runtimeNodeDoesNotNeedInternalToken() {
        MockEnvironment env = validEnv();
        env.setProperty("payroll.node-role", "runtime");
        Map<String, String> vars = validEnvVars();
        vars.remove("PAYROLL_AGENT_RUNTIME_TOKEN");
        assertThatCode(() -> guard(env, vars).run(null)).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingParseTokenWhenRemoteParsing() {
        MockEnvironment env = validEnv();
        env.setProperty("payroll.kb.parse.mode", "remote");
        Map<String, String> vars = validEnvVars();
        vars.remove("PAYROLL_PARSE_TOKEN");
        assertFailsWith(env, vars, "payroll.kb.parse.token");
    }

    @Test
    void remoteParsingTokenNotRequiredWhenInline() {
        MockEnvironment env = validEnv();
        env.setProperty("payroll.kb.parse.mode", "inline");
        assertThatCode(() -> guard(env, validEnvVars()).run(null)).doesNotThrowAnyException();
    }

    @Test
    void clusterForbidsNodeRoleAll() {
        MockEnvironment env = validEnv();
        env.setProperty("payroll.node-role", "all");
        assertFailsWith(env, validEnvVars(), "集群部署禁止 node-role=all");
    }

    @Test
    void singleNodeRoleAllOnlyWarnsEvenInFailMode() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("payroll.guard.strict", "fail");
        env.setProperty("payroll.auth.admin-password", "Prod-Admin-Pass-1");
        env.setProperty("payroll.internal.token", "runtime-token");
        assertThatCode(() -> guard(env, new HashMap<>()).run(null))
                .doesNotThrowAnyException();
    }

    @Test
    void prodProfileRequiresCluster() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("payroll.auth.admin-password", "Prod-Admin-Pass-1");
        env.setProperty("payroll.internal.token", "runtime-token");
        assertFailsWith(env, new HashMap<>(), "payroll.deployment.mode");
    }

    private void assertFailsWith(MockEnvironment env, Map<String, String> vars, String expected) {
        assertThatThrownBy(() -> guard(env, vars).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expected);
    }

    private TopologyGuard guard(MockEnvironment env, Map<String, String> vars) {
        if (env.getProperty("payroll.guard.strict") == null) {
            env.setProperty("payroll.guard.strict", "fail");
        }
        return new TopologyGuard(env, vars::get);
    }

    private MockEnvironment validEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("payroll.deployment.mode", "cluster");
        env.setProperty("payroll.node-role", "web");
        env.setProperty("payroll.db.url", "jdbc:mysql://db:3306/payroll");
        env.setProperty("spring.data.redis.host", "redis");
        env.setProperty("payroll.kb.es-enabled", "true");
        env.setProperty("payroll.kb.es-url", "http://es:9200");
        env.setProperty("payroll.memory.es-url", "http://es:9200");
        env.setProperty("payroll.kb.embedding-base-url", "http://ollama:11434");
        env.setProperty("payroll.auth.admin-password", "Prod-Admin-Pass-1");
        return env;
    }

    private Map<String, String> validEnvVars() {
        Map<String, String> vars = new HashMap<>();
        vars.put("PAYROLL_NACOS_SERVER", "http://nacos:8848");
        vars.put("PAYROLL_NACOS_ENABLED", "true");
        vars.put("PAYROLL_SECRET_KEY", Base64.getEncoder().encodeToString(new byte[32]));
        vars.put("PAYROLL_AGENT_RUNTIME_TOKEN", "runtime-token");
        vars.put("PAYROLL_PARSE_TOKEN", "parse-token");
        return vars;
    }
}
