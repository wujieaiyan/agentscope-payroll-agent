package io.agentscope.payroll.sandbox;

/** 执行器抽象，生产实现固定为一次性 Docker 容器；测试注入假实现。 */
public interface SandboxRunner {

    SandboxResult run(SandboxRequest request, String scriptSha256);
}
