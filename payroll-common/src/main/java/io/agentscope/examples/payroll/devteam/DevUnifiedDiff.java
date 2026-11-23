package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeSet;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

/**
 * 结构化编辑对应的 unified diff（P1-1）：按路径排序，逐文件给出 git 风格头与 3 行上下文。
 *
 * <p>入参是「路径 → 文本」快照，{@code null} 表示文件不存在。新建文件使用
 * {@code /dev/null} 旧侧，删除文件使用 {@code /dev/null} 新侧；算法固定 Myers，
 * 与仓库落地后的 {@code git diff} 保持一致的可读格式。
 */
public final class DevUnifiedDiff {

    public static final int DEFAULT_CONTEXT = 3;

    private DevUnifiedDiff() {}

    public static String between(Map<String, String> before, Map<String, String> after) {
        return between(before, after, DEFAULT_CONTEXT);
    }

    public static String between(
            Map<String, String> before, Map<String, String> after, int context) {
        if (before == null) {
            before = Map.of();
        }
        if (after == null) {
            after = Map.of();
        }
        TreeSet<String> paths = new TreeSet<>(before.keySet());
        paths.addAll(after.keySet());
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DiffFormatter formatter = new DiffFormatter(buffer)) {
            formatter.setContext(Math.max(0, context));
            formatter.setDiffAlgorithm(
                    DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.MYERS));
            for (String path : paths) {
                String oldText = before.get(path);
                String newText = after.get(path);
                if (java.util.Objects.equals(oldText, newText)) {
                    continue;
                }
                writeFile(buffer, formatter, path, oldText, newText);
            }
            formatter.flush();
        } catch (IOException e) {
            throw new PayrollException("生成 unified diff 失败：" + e.getMessage(), e);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /** 单文件 diff；两侧都为 {@code null} 或缺省时返回空串。 */
    public static String forFile(String path, String before, String after) {
        java.util.Map<String, String> left = new java.util.HashMap<>();
        java.util.Map<String, String> right = new java.util.HashMap<>();
        if (before != null) {
            left.put(path, before);
        }
        if (after != null) {
            right.put(path, after);
        }
        return between(left, right);
    }

    private static void writeFile(
            ByteArrayOutputStream buffer,
            DiffFormatter formatter,
            String path,
            String oldText,
            String newText)
            throws IOException {
        String headerPath = path.replace('\\', '/');
        buffer.write(("diff --git a/" + headerPath + " b/" + headerPath + "\n")
                .getBytes(StandardCharsets.UTF_8));
        if (oldText == null) {
            buffer.write("new file mode 100644\n".getBytes(StandardCharsets.UTF_8));
            buffer.write("--- /dev/null\n".getBytes(StandardCharsets.UTF_8));
            buffer.write(("+++ b/" + headerPath + "\n").getBytes(StandardCharsets.UTF_8));
        } else if (newText == null) {
            buffer.write("deleted file mode 100644\n".getBytes(StandardCharsets.UTF_8));
            buffer.write(("--- a/" + headerPath + "\n").getBytes(StandardCharsets.UTF_8));
            buffer.write("+++ /dev/null\n".getBytes(StandardCharsets.UTF_8));
        } else {
            buffer.write(("--- a/" + headerPath + "\n").getBytes(StandardCharsets.UTF_8));
            buffer.write(("+++ b/" + headerPath + "\n").getBytes(StandardCharsets.UTF_8));
        }
        RawText oldRaw = new RawText(oldText == null ? new byte[0] : utf8(oldText));
        RawText newRaw = new RawText(newText == null ? new byte[0] : utf8(newText));
        EditList edits =
                DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.MYERS)
                        .diff(RawTextComparator.DEFAULT, oldRaw, newRaw);
        formatter.format(edits, oldRaw, newRaw);
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
