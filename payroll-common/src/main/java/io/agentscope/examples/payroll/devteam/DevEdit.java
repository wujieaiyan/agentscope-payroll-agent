package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.domain.PayrollException;

/**
 * 研发域结构化编辑（P1-1/A3）：整文件写入或唯一子串替换，模型不再直接产出 unified diff。
 *
 * <p>替换要求 {@code oldText} 在文件中恰好出现一次，既避免多匹配写到错误位置，
 * 也避免模型拿旧上下文做模糊替换；空 {@code oldText} 与「新旧内容相同」直接拒绝。
 */
public record DevEdit(Kind kind, String path, String content, String oldText, String newText) {

    public enum Kind {
        WRITE,
        REPLACE
    }

    public DevEdit {
        if (kind == null) {
            throw new PayrollException("编辑类型不能为空");
        }
        if (path == null || path.isBlank()) {
            throw new PayrollException("编辑路径不能为空");
        }
        path = path.trim();
        if (kind == Kind.WRITE) {
            content = content == null ? "" : content;
            if (oldText != null || newText != null) {
                throw new PayrollException("整文件写入不允许携带 oldText/newText");
            }
        } else {
            if (oldText == null || oldText.isEmpty()) {
                throw new PayrollException("替换编辑的 oldText 不能为空");
            }
            newText = newText == null ? "" : newText;
            if (oldText.equals(newText)) {
                throw new PayrollException("替换编辑的新旧内容相同：" + path);
            }
            if (content != null) {
                throw new PayrollException("替换编辑不允许携带整文件内容");
            }
        }
    }

    public static DevEdit write(String path, String content) {
        return new DevEdit(Kind.WRITE, path, content, null, null);
    }

    public static DevEdit replace(String path, String oldText, String newText) {
        return new DevEdit(Kind.REPLACE, path, null, oldText, newText);
    }

    public String describe() {
        return kind == Kind.WRITE ? "write " + path : "replace " + path;
    }
}
