package io.agentscope.examples.payroll;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot 管理台入口，使用内嵌 Tomcat 提供 REST API 与静态页面。 */
@SpringBootApplication
public class PayrollWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayrollWebApplication.class, args);
    }
}
