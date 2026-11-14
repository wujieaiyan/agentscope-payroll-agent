package io.agentscope.payroll.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 智能体运行期入口：执行模型对话与动态团队，只对内暴露 {@code /internal} 接口。
 *
 * <p>扫描基础包时同时覆盖公共基础设施与运行期配置，Web 后端不在本应用类路径内。
 */
@SpringBootApplication(scanBasePackages = {"io.agentscope.examples.payroll", "io.agentscope.payroll.runtime"})
public class AgentRuntimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentRuntimeApplication.class, args);
    }
}
