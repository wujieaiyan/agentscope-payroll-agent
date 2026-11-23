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
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * prod 启动期拓扑校验（M3 / D3）：只允许「MySQL + Redis + Nacos + 外部检索嵌入 + 凭证密钥」
 * 一种生产组合。任一项缺失即聚合报错并终止启动，不做进程内降级；
 * 允许的档位与对应环境变量见 docs/deployment-matrix.md。
 *
 * <p>校验失败只输出一条带编号的中文错误，避免堆栈刷屏。
 */
@Component
@Profile("prod")
public class TopologyGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TopologyGuard.class);
    private static final String DOC = "docs/deployment-matrix.md";

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
        List<String> errors = new ArrayList<>();
        checkDeploymentMode(errors);
        checkDatabase(errors);
        checkRedis(errors);
        checkNacos(errors);
        checkExternalRetrieval(errors);
        checkSecretKey(errors);
        if (!errors.isEmpty()) {
            StringBuilder message = new StringBuilder("prod 拓扑校验失败（")
                    .append(errors.size())
                    .append(" 项），已停止启动：");
            for (int i = 0; i < errors.size(); i++) {
                message.append(System.lineSeparator())
                        .append("  ")
                        .append(i + 1)
                        .append(") ")
                        .append(errors.get(i));
            }
            message.append(System.lineSeparator()).append("参考 ").append(DOC);
            log.error(message.toString());
            throw new IllegalStateException(message.toString());
        }
        log.info(
                "[Topology] prod 拓扑校验通过：cluster + 非 H2 数据库 + Redis + Nacos + 外部检索嵌入 + 凭证密钥");
    }

    private void checkDeploymentMode(List<String> errors) {
        String mode = environment.getProperty("payroll.deployment.mode", "single").trim();
        if (!"cluster".equalsIgnoreCase(mode)) {
            errors.add("部署模式必须为 cluster（payroll.deployment.mode / PAYROLL_DEPLOYMENT_MODE），当前："
                    + (mode.isEmpty() ? "<空>" : mode));
        }
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
