package io.agentscope.examples.payroll.config;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 启动期拓扑守卫（M3 / D3、C4-D7）：把「生产组合是否成立」的全部问题一次收集完，
 * 再按 {@code payroll.guard.strict} 决定只告警还是一并抛出终止启动。
 *
 * <p>两阶段的原因：首版用 warn 让存量部署先把清单补完，下一版本切 fail 才阻断；
 * 装配不再限定 prod profile，否则非 prod 环境无法演练 fail。
 *
 * <p>错误信息只报「未配置/为默认值/不满足要求」，不回显任何取值，避免把密钥写进日志。
 * 生产组合与必填 env 见 docs/deployment-matrix.md、docs/capacity-resilience-remediation-spec.md §7.1。
 */
@Component
public class TopologyGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TopologyGuard.class);
    private static final String DOC = "docs/deployment-matrix.md";
    private static final String DEFAULT_ADMIN_PASSWORD = "admin123";

    private final Environment environment;
    private final Function<String, String> env;

    @Autowired
    public TopologyGuard(Environment environment) {
        this(environment, System::getenv);
    }

    /** 供单测注入环境变量快照，不读取真实进程环境。 */
    public TopologyGuard(Environment environment, Function<String, String> envLookup) {
        this.environment = environment;
        this.env = envLookup;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean strict = "fail".equalsIgnoreCase(
                environment.getProperty("payroll.guard.strict", "warn").trim());
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String role = role();
        boolean cluster = "cluster".equalsIgnoreCase(
                environment.getProperty("payroll.deployment.mode", "single").trim());
        boolean production = cluster || environment.matchesProfiles("prod");

        if (environment.matchesProfiles("prod") && !cluster) {
            errors.add("prod 环境部署模式必须为 cluster（payroll.deployment.mode / PAYROLL_DEPLOYMENT_MODE）");
        }
        if (production) {
            checkDatabase(errors);
            checkRedis(errors);
            checkNacos(errors);
            checkExternalRetrieval(errors);
            checkSecretKey(errors);
        }
        checkAdminPassword(errors);
        checkNodeRole(errors, warnings, role, cluster);
        if (isWeb(role)) {
            checkInternalToken(errors);
            if ("remote".equalsIgnoreCase(
                    environment.getProperty("payroll.kb.parse.mode", "inline").trim())) {
                checkParseToken(errors);
            }
        }

        warnings.forEach(warning -> log.warn("[Topology] 启动守卫提示：{}", warning));
        if (errors.isEmpty()) {
            log.info("[Topology] 启动守卫通过：strict={}, node-role={}, deployment={}",
                    strict ? "fail" : "warn", role, cluster ? "cluster" : "single");
            return;
        }

        StringBuilder message = new StringBuilder()
                .append("启动拓扑校验发现 ")
                .append(errors.size())
                .append(" 项问题");
        if (strict) {
            message.append("（payroll.guard.strict=fail），已停止启动：");
        } else {
            message.append("（payroll.guard.strict=warn，仅告警，下一版本将改为 fail）：");
        }
        for (int i = 0; i < errors.size(); i++) {
            message.append(System.lineSeparator())
                    .append("  ")
                    .append(i + 1)
                    .append(") ")
                    .append(errors.get(i));
        }
        message.append(System.lineSeparator()).append("参考 ").append(DOC);
        if (strict) {
            log.error(message.toString());
            throw new IllegalStateException(message.toString());
        }
        log.warn(message.toString());
    }

    private void checkDatabase(List<String> errors) {
        String url = environment.getProperty("payroll.db.url", "").trim();
        if (url.isEmpty()) {
            errors.add("数据源未配置（payroll.db.url / PAYROLL_DB_URL）");
        } else if (url.toLowerCase(Locale.ROOT).startsWith("jdbc:h2:")) {
            errors.add("生产环境禁止 H2 文件库（payroll.db.url / PAYROLL_DB_URL），请使用 MySQL");
        }
    }

    private void checkRedis(List<String> errors) {
        String host = environment.getProperty("spring.data.redis.host", "").trim();
        if (host.isEmpty()) {
            errors.add("未启用 Redis（spring.data.redis.host 为空），请激活 redis profile / 设置 PAYROLL_REDIS_HOST");
        }
    }

    private void checkNacos(List<String> errors) {
        String server = value("PAYROLL_NACOS_SERVER", "http://127.0.0.1:8848");
        boolean enabled = Boolean.parseBoolean(
                value("PAYROLL_NACOS_ENABLED", server.isBlank() ? "false" : "true"));
        if (!enabled || server.isBlank()) {
            errors.add("Nacos 未启用或地址为空（PAYROLL_NACOS_ENABLED=true 且 PAYROLL_NACOS_SERVER 非空）");
        }
    }

    private void checkExternalRetrieval(List<String> errors) {
        if (!Boolean.parseBoolean(
                environment.getProperty("payroll.kb.es-enabled", "true").trim())) {
            errors.add("知识库检索必须启用外部 ES（payroll.kb.es-enabled / PAYROLL_KB_ES_ENABLED=true）");
        }
        checkHttpUrl(errors, "知识库 ES 地址", environment.getProperty("payroll.kb.es-url", ""));
        checkHttpUrl(errors, "记忆 ES 地址", environment.getProperty("payroll.memory.es-url", ""));
        checkHttpUrl(
                errors,
                "知识库嵌入服务地址",
                environment.getProperty("payroll.kb.embedding-base-url", ""));
    }

    private void checkSecretKey(List<String> errors) {
        String value = value("PAYROLL_SECRET_KEY", "");
        if (value.isBlank()) {
            errors.add("未配置 PAYROLL_SECRET_KEY（页面录入密钥的 AES-256-GCM 主密钥）");
            return;
        }
        try {
            if (Base64.getDecoder().decode(value.trim()).length != 32) {
                errors.add("PAYROLL_SECRET_KEY 必须是 Base64 编码的 32 字节密钥");
            }
        } catch (IllegalArgumentException e) {
            errors.add("PAYROLL_SECRET_KEY 必须是 Base64 编码的 32 字节密钥");
        }
    }

    private void checkAdminPassword(List<String> errors) {
        String password = propertyOrEnv("payroll.auth.admin-password", "PAYROLL_ADMIN_PASSWORD");
        if (password.isBlank() || DEFAULT_ADMIN_PASSWORD.equals(password)) {
            errors.add("管理员初始口令未修改（payroll.auth.admin-password / PAYROLL_ADMIN_PASSWORD 为默认值或为空）");
        }
    }

    private void checkNodeRole(List<String> errors, List<String> warnings, String role, boolean cluster) {
        if (!"all".equals(role)) {
            return;
        }
        if (cluster) {
            errors.add("集群部署禁止 node-role=all（payroll.node-role / PAYROLL_NODE_ROLE），请拆成 web 与 runtime");
        } else {
            warnings.add("单机部署使用 node-role=all（payroll.node-role / PAYROLL_NODE_ROLE），仅允许本地/单机");
        }
    }

    private void checkInternalToken(List<String> errors) {
        if (propertyOrEnv("payroll.internal.token", "PAYROLL_AGENT_RUNTIME_TOKEN").isBlank()) {
            errors.add("Web 节点未配置内部调用密钥（payroll.internal.token / PAYROLL_AGENT_RUNTIME_TOKEN）");
        }
    }

    private void checkParseToken(List<String> errors) {
        if (propertyOrEnv("payroll.kb.parse.token", "PAYROLL_PARSE_TOKEN").isBlank()) {
            errors.add("解析模式为 remote 但未配置解析密钥（payroll.kb.parse.token / PAYROLL_PARSE_TOKEN）");
        }
    }

    private boolean isWeb(String role) {
        return "web".equals(role) || "all".equals(role);
    }

    private String role() {
        String role = environment.getProperty("payroll.node-role", "all").trim().toLowerCase(Locale.ROOT);
        return role.isEmpty() ? "all" : role;
    }

    private String propertyOrEnv(String key, String envKey) {
        String property = environment.getProperty(key);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        return value(envKey, "");
    }

    private void checkHttpUrl(List<String> errors, String label, String raw) {
        String url = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            errors.add(label + "必须是 http(s) 服务地址，禁止使用内置 HASH 降级路径");
        }
    }

    private String value(String key, String defaultValue) {
        String raw = env.apply(key);
        return raw == null || raw.isBlank() ? defaultValue : raw.trim();
    }
}
