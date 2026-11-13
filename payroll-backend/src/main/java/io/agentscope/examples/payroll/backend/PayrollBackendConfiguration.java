package io.agentscope.examples.payroll.backend;

import io.agentscope.examples.payroll.db.H2PayrollRepository;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.AgentRuntimeClient;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.service.ChatStreamListener;
import io.agentscope.examples.payroll.service.ExcelImportService;
import io.agentscope.examples.payroll.service.PayrollChat;
import io.agentscope.examples.payroll.service.PayrollEngine;
import io.agentscope.examples.payroll.service.PayrollRunService;
import io.agentscope.examples.payroll.service.PayrollServices;
import io.agentscope.examples.payroll.support.ModelEnv;
import io.agentscope.examples.payroll.web.AuditFilter;
import io.agentscope.examples.payroll.web.AuthInterceptor;
import io.agentscope.examples.payroll.web.NodeRoleFilter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * Web 后端装配：本节点只负责鉴权、会话与管理台接口，模型执行全部交给 agent 运行期。
 *
 * <p>没有配置 {@code payroll.runtime.urls} 时不提供本机执行兜底，接口会返回明确错误。
 */
@Configuration(proxyBeanMethods = false)
public class PayrollBackendConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PayrollBackendConfiguration.class);

    /** Web 角色的 runtime 客户端；未配置地址或令牌时视为不可用。 */
    @Bean
    public AgentRuntimeClient agentRuntimeClient(
            @Value("${payroll.runtime.urls:}") String urls,
            @Value("${payroll.internal.token:}") String token) {
        List<String> parsed =
                urls == null || urls.isBlank() ? List.of() : List.of(urls.split("\\s*,\\s*"));
        AgentRuntimeClient client = new AgentRuntimeClient(parsed, token);
        if (client.enabled()) {
            log.info("[运行期] 远程执行已启用：{}", parsed);
        } else {
            log.warn("[运行期] 未配置远程执行地址或内部令牌：会话接口将只落轮次，模型调用返回明确错误");
        }
        return client;
    }

    /** 生产环境没有本机模型；测试可注入 @Primary 替身覆盖。 */
    @Bean
    public PayrollChat backendChatUnavailable() {
        return new BackendChatUnavailable();
    }

    @Bean
    public PayrollServices payrollServices(
            PayrollDatabase database,
            H2PayrollRepository repository,
            PayrollEngine engine,
            PayrollRunService runService,
            ObjectProvider<PayrollChat> chat,
            ExcelImportService excelImportService) {
        return PayrollServices.of(
                database,
                repository,
                engine,
                runService,
                chat.getIfAvailable(),
                excelImportService);
    }

    @Bean
    public AuthInterceptor authInterceptor(AuthService authService) {
        return new AuthInterceptor(authService);
    }

    @Bean
    public FilterRegistrationBean<AuditFilter> auditFilter(
            AuditService auditService, AuthService authService) {
        FilterRegistrationBean<AuditFilter> registration =
                new FilterRegistrationBean<>(new AuditFilter(auditService, authService));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /** web 节点不暴露 /internal，只提供对外 API。 */
    @Bean
    public FilterRegistrationBean<NodeRoleFilter> nodeRoleFilter(
            @Value("${payroll.node-role:web}") String role) {
        FilterRegistrationBean<NodeRoleFilter> registration =
                new FilterRegistrationBean<>(new NodeRoleFilter(role));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        return registration;
    }

    /** 启动完成后打印一次关键运行参数，方便本机排查端口与数据落盘位置。 */
    @Bean
    public ApplicationRunner payrollStartupReporter(
            PayrollServices services,
            AgentRuntimeClient runtimeClient,
            Environment environment) {
        return args -> {
            String port = environment.getProperty("local.server.port", "0");
            String address = environment.getProperty("server.address", "127.0.0.1");
            log.info("=".repeat(72));
            log.info("薪酬管理台后端  http://{}:{}", address, port);
            log.info("数据库          {}", services.database().url());
            log.info("智能体运行期    {}", runtimeClient.enabled() ? "远程" : "未配置（不执行模型）");
            log.info("输出目录        {}", services.runService().outputDir().toAbsolutePath());
            log.info("按 Ctrl+C 停止");
            log.info("=".repeat(72));
        };
    }

    /** 后端不执行模型：保留接口兼容性，但直接给出可操作的错误。 */
    private static final class BackendChatUnavailable implements PayrollChat {

        private static final String MESSAGE =
                "当前后端节点不执行模型，请通过 /api/chat/sessions 使用智能体运行期";

        @Override
        public Reply chat(String message) {
            throw new PayrollException(MESSAGE);
        }

        @Override
        public void stream(String message, ChatStreamListener listener) {
            throw new PayrollException(MESSAGE);
        }

        @Override
        public String modelId() {
            return ModelEnv.modelId();
        }
    }
}
