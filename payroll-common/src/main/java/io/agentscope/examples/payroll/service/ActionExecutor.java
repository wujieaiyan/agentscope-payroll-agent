package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.domain.ActionProposal;

/**
 * 提案执行器：确认后按工具名派发到既有领域服务或已配置的 MCP 服务。
 *
 * <p>实现不得提供可拼接 SQL、任意反射调用等通用写入口；每个可执行工具都必须是显式映射。
 */
public interface ActionExecutor {

    /** 执行该提案所需的权限码；调用方在确认前校验，权限不足时提案保持 PENDING。 */
    String requiredPermission(String toolName);

    /** 执行提案并返回结果对象（由调用方序列化落库）；失败时抛出异常。 */
    Object execute(ActionProposal proposal, long userId);
}
