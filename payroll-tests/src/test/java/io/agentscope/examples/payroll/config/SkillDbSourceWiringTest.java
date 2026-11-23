package io.agentscope.examples.payroll.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.examples.payroll.skill.CompositeSkillRepository;
import io.agentscope.examples.payroll.skill.DbSkillRepository;
import io.agentscope.examples.payroll.skill.SkillRepositories;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * 规格 §11.1 的容器装配契约：切到 db 源后，按 {@code AgentSkillRepository} 类型注入必须唯一解析。
 *
 * <p>唯一靠组合仓库上的 {@code @Primary}，不是靠少注册一个 bean——轮询器与导入器要按具体类型拿裸 db
 * 仓库，两个候选都得在。这条只能由真容器锁死：P0-1…P0-7 的用例全在手工 new 对象，db 源第一次被真的拉进
 * Spring 进程时容器起不来，{@code AgentAdminController} 的 {@code ObjectProvider.getIfAvailable} 当场歧义。
 */
class SkillDbSourceWiringTest {

    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void clearSharedRepository() {
        // skillRepository bean 会把组合仓库写进进程级共享槽位，先清掉上一个用例留下的实例。
        SkillRepositories.resetShared();
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
        SkillRepositories.resetShared();
    }

    @Test
    @DisplayName("source=db：按类型注入不歧义，命中带内置回退的组合仓库")
    void dbSourceResolvesSingleRepositoryBean() throws IOException {
        refresh("db");

        // db 源下 dbSkillRepository 与 skillRepository 同属 AgentSkillRepository，歧义靠 @Primary 消掉。
        // 去掉它下面两行直接抛 NoUniqueBeanDefinitionException——真机第一次拉起 db 源就撞在这。
        AgentSkillRepository repository = context.getBean(AgentSkillRepository.class);

        assertInstanceOf(
                CompositeSkillRepository.class,
                repository,
                "按类型拿到的是裸 db 仓库，内置技能回退会在切库当天消失");
        // 控制器用的就是这条调用：没有 @Primary 时它抛 NoUniqueBeanDefinitionException。
        assertSame(
                repository,
                context.getBeanProvider(AgentSkillRepository.class).getIfAvailable());
        assertSame(repository, SkillRepositories.shared(), "容器装好的仓库没进共享槽位，读路径会再开一个连接池");
    }

    @Test
    @DisplayName("source=db：db 仓库仍按具体类型可得，轮询器与导入器靠它刷新目录")
    void dbRepositoryStaysInjectableByConcreteType() throws IOException {
        refresh("db");

        assertInstanceOf(
                DbSkillRepository.class,
                context.getBean(DbSkillRepository.class),
                "SkillInvalidationPoller 按具体类型拿 db 仓库，这条不能被 @Primary 影响");
    }

    @Test
    @DisplayName("nacos 源不注册运行期仓库 bean，沿用懒加载共享槽位")
    void nacosSourceRegistersNoRepositoryBean() throws IOException {
        refresh("nacos");

        assertNull(
                context.getBeanProvider(AgentSkillRepository.class).getIfAvailable(),
                "nacos 源多出一个仓库 bean，等于给同一份技能建了两个权威");
        assertEquals(0, context.getBeanNamesForType(DbSkillRepository.class).length);
    }

    /**
     * 只注册被测配置类，并把整份 bean 定义改成懒加载后启动。
     *
     * <p>{@code PayrollCoreConfiguration} 有几十个 {@code @Bean} 方法，默认 refresh 会把它们全实例化，
     * 那需要真连 Nacos、ES、Redis 与模型；懒加载后只有断言这条链（skillRepository → jdbcSkillStore →
     * payrollDatabase/skillBlobStore）真的被创建。改标志位必须放在 bean 工厂后置处理器里：{@code @Bean}
     * 定义是 refresh 过程中才注册的，refresh 前遍历 {@code getBeanDefinitionNames()} 一个也看不到。
     * 库用 H2 内存实例，技能表由 Flyway 现建。
     */
    private void refresh(String skillsSource) throws IOException {
        Map<String, Object> properties = new HashMap<>();
        properties.put("payroll.skills.source", skillsSource);
        properties.put(
                "payroll.db.url", "jdbc:h2:mem:skill-wiring-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        properties.put("payroll.db.user", "sa");
        properties.put("payroll.db.password", "");
        properties.put(
                "payroll.skill.blob.root", Files.createTempDirectory("skill-wiring-blob").toString());
        properties.put("payroll.skill.invalidation.poll-ms", "0");
        AnnotationConfigApplicationContext created = new AnnotationConfigApplicationContext();
        created.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("skill-db-source-wiring", properties));
        created.register(PayrollCoreConfiguration.class);
        created.addBeanFactoryPostProcessor(
                factory -> {
                    for (String name : factory.getBeanDefinitionNames()) {
                        factory.getBeanDefinition(name).setLazyInit(true);
                    }
                });
        created.refresh();
        context = created;
    }
}
