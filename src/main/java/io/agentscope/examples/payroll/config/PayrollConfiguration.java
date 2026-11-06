package io.agentscope.examples.payroll.config;

import io.agentscope.examples.payroll.db.H2AdminRepository;
import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.db.H2PayrollRepository;
import io.agentscope.examples.payroll.db.H2PayrollRunStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.PayrollPolicy;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.service.ChatSessionManager;
import io.agentscope.examples.payroll.service.DryRunEmailGateway;
import io.agentscope.examples.payroll.service.ExcelExporter;
import io.agentscope.examples.payroll.service.ExcelImportService;
import io.agentscope.examples.payroll.service.PayrollChat;
import io.agentscope.examples.payroll.service.PayrollChatFactory;
import io.agentscope.examples.payroll.service.PayrollChatService;
import io.agentscope.examples.payroll.service.PayrollEngine;
import io.agentscope.examples.payroll.service.PayrollRunService;
import io.agentscope.examples.payroll.service.PayrollServices;
import io.agentscope.examples.payroll.web.AuthInterceptor;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Spring 容器中的薪酬组件装配，与 CLI 使用同一套领域实现。 */
@Configuration(proxyBeanMethods = false)
public class PayrollConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PayrollConfiguration.class);

    @Bean
    public PayrollDatabase payrollDatabase(
            @Value("${payroll.db.url}") String url,
            @Value("${payroll.db.user}") String user,
            @Value("${payroll.db.password}") String password) {
        return PayrollDatabase.open(url, user, password);
    }

    @Bean
    public H2PayrollRepository payrollRepository(PayrollDatabase database) {
        return new H2PayrollRepository(database);
    }

    @Bean
    public PayrollEngine payrollEngine(H2PayrollRepository repository) {
        PayrollPolicy defaultPolicy = repository.policyFor("默认");
        if (defaultPolicy == null) {
            throw new IllegalStateException("默认城市政策缺失，无法启动");
        }
        return new PayrollEngine(repository, repository, defaultPolicy);
    }

    @Bean
    public PayrollRunService payrollRunService(
            PayrollEngine engine,
            H2PayrollRepository repository,
            PayrollDatabase database,
            @Value("${payroll.output-dir}") String outputDir) {
        return new PayrollRunService(
                engine,
                repository,
                new H2PayrollRunStore(database),
                new ExcelExporter(),
                new DryRunEmailGateway(),
                Path.of(outputDir));
    }

    @Bean
    public PayrollChat payrollChat(PayrollEngine engine) {
        return new PayrollChatService(engine);
    }

    @Bean
    public PayrollChatFactory payrollChatFactory(PayrollEngine engine) {
        return () -> new PayrollChatService(engine);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public H2AdminRepository adminRepository(
            PayrollDatabase database,
            PasswordEncoder passwordEncoder,
            @Value("${payroll.auth.admin-password:admin123}") String adminPassword) {
        H2AdminRepository repository = new H2AdminRepository(database);
        if (repository.ensureAdminUser(passwordEncoder.encode(adminPassword))) {
            if ("admin123".equals(adminPassword)) {
                log.warn("已创建默认管理员 admin / admin123，请登录后立即修改密码");
            } else {
                log.info("已创建管理员 admin，密码取自 PAYROLL_ADMIN_PASSWORD");
            }
        }
        return repository;
    }

    @Bean
    public H2ChatStore chatStore(PayrollDatabase database) {
        return new H2ChatStore(database);
    }

    @Bean(destroyMethod = "close")
    public ChatSessionManager chatSessionManager(
            H2ChatStore chatStore, PayrollChatFactory chatFactory) {
        return new ChatSessionManager(chatStore, chatFactory);
    }

    @Bean
    public AuthService authService(
            H2AdminRepository adminRepository,
            PasswordEncoder passwordEncoder,
            @Value("${payroll.auth.enabled:true}") boolean authEnabled) {
        return new AuthService(adminRepository, passwordEncoder, authEnabled);
    }

    @Bean
    public AuthInterceptor authInterceptor(AuthService authService) {
        return new AuthInterceptor(authService);
    }

    @Bean
    public ExcelImportService excelImportService(H2PayrollRepository repository) {
        return new ExcelImportService(repository);
    }

    /** 流式对话线程池：SseEmitter 在独立线程中消费模型事件。 */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService chatStreamExecutor() {
        return Executors.newFixedThreadPool(
                4,
                runnable -> {
                    Thread thread = new Thread(runnable, "payroll-chat-stream");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Bean(destroyMethod = "close")
    public PayrollServices payrollServices(
            PayrollDatabase database,
            H2PayrollRepository repository,
            PayrollEngine engine,
            PayrollRunService runService,
            PayrollChat chat,
            ExcelImportService excelImportService) {
        return PayrollServices.of(
                database, repository, engine, runService, chat, excelImportService);
    }

    /** 启动完成后打印一次关键运行参数，方便本机排查端口与数据落盘位置。 */
    @Bean
    public ApplicationRunner payrollStartupReporter(
            PayrollServices services, Environment environment) {
        return args -> {
            String port =
                    environment.getProperty("local.server.port", "0");
            String address = environment.getProperty("server.address", "127.0.0.1");
            log.info("=".repeat(72));
            log.info("薪酬管理台  http://{}:{}", address, port);
            log.info("数据库      {}", services.database().url());
            log.info("邮件模式    dry-run（只校验并记录，不会真实投递）");
            log.info("输出目录    {}", services.runService().outputDir().toAbsolutePath());
            log.info("按 Ctrl+C 停止");
            log.info("=".repeat(72));
        };
    }
}
