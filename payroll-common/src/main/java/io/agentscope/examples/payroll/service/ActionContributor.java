package io.agentscope.examples.payroll.service;

import java.util.Map;

/**
 * 业务模块向写操作执行器登记本地动作与所需权限。
 *
 * <p>执行器定义在公共模块，知识库等服务只在 Web 模块可用，因此由 Web 侧实现本接口，
 * 启动时由 {@code PayrollCoreConfiguration} 合并；runtime 没有贡献者时保持原有 MCP 行为。
 */
public interface ActionContributor {

    /** 工具名 → 本地执行动作。 */
    Map<String, DefaultActionExecutor.LocalAction> localActions();

    /** 工具名 → 确认执行所需权限；未登记的沿用 chat:use。 */
    Map<String, String> toolPermissions();
}
