package io.agentscope.examples.payroll.memory;

/** 记忆来源：只索引用户问题与压缩摘要，助手答复不进入向量库。 */
public enum MemorySource {
    QUESTION,
    SUMMARY
}
