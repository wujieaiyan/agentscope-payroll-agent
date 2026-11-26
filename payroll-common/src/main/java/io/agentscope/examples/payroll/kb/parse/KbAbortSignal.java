package io.agentscope.examples.payroll.kb.parse;

/**
 * 入库取消信号：不是解析失败，跨模块按控制流原样向上传递。
 *
 * <p>解析实现抛出的异常若实现本接口，调用方不得包装成「解析文档失败」。
 */
public interface KbAbortSignal {
}
