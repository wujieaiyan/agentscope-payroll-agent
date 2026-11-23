package io.agentscope.examples.payroll.devteam;

/**
 * 代码符号（P2-2）：tree-sitter 解析出的类、方法、函数等定位信息。
 *
 * <p>行号从 1 开始，{@code parent} 是最近的包含类型名（顶层符号为空串）；
 * {@code signature} 是声明首行的截断文本，用于让模型在未读文件时也能判断用途。
 */
public record DevCodeSymbol(
        String path,
        String language,
        String kind,
        String name,
        String parent,
        String signature,
        int startLine,
        int endLine) {

    public DevCodeSymbol {
        path = path == null ? "" : path.trim();
        language = language == null ? "" : language.trim();
        kind = kind == null ? "" : kind.trim();
        name = name == null ? "" : name.trim();
        parent = parent == null ? "" : parent.trim();
        signature = signature == null ? "" : signature.trim();
    }
}
