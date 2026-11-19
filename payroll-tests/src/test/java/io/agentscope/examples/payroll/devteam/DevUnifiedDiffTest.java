package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DevUnifiedDiffTest {

    @Test
    @DisplayName("修改文件：输出 git 头与 hunk，并保留上下文")
    void rendersModification() {
        String diff =
                DevUnifiedDiff.between(
                        Map.of("payroll-common/src/main/java/A.java", "a\nb\nc\n"),
                        Map.of("payroll-common/src/main/java/A.java", "a\nB\nc\n"));

        assertTrue(diff.startsWith("diff --git a/payroll-common/src/main/java/A.java b/"));
        assertTrue(diff.contains("--- a/payroll-common/src/main/java/A.java"));
        assertTrue(diff.contains("+++ b/payroll-common/src/main/java/A.java"));
        assertTrue(diff.contains("@@"));
        assertTrue(diff.contains("-b"));
        assertTrue(diff.contains("+B"));
    }

    @Test
    @DisplayName("新建文件用 /dev/null 旧侧，删除文件用 /dev/null 新侧")
    void rendersAddAndDelete() {
        String added =
                DevUnifiedDiff.between(
                        Map.of(), Map.of("frontend/src/new.js", "export const a = 1;\n"));
        assertTrue(added.contains("new file mode 100644"));
        assertTrue(added.contains("--- /dev/null"));
        assertTrue(added.contains("+++ b/frontend/src/new.js"));
        assertTrue(added.contains("+export const a = 1;"));

        String deleted =
                DevUnifiedDiff.between(Map.of("scripts/drop.sh", "rm -rf /\n"), Map.of());
        assertTrue(deleted.contains("deleted file mode 100644"));
        assertTrue(deleted.contains("--- a/scripts/drop.sh"));
        assertTrue(deleted.contains("+++ /dev/null"));
        assertTrue(deleted.contains("-rm -rf /"));
    }

    @Test
    @DisplayName("内容未变化时返回空串")
    void emptyWhenUnchanged() {
        Map<String, String> state = Map.of("a.txt", "same\n");
        assertEquals("", DevUnifiedDiff.between(state, new LinkedHashMap<>(state)));
        assertEquals("", DevUnifiedDiff.between(null, null));
    }

    @Test
    @DisplayName("多文件按路径字典序输出，单文件接口等价")
    void sortsPaths() {
        Map<String, String> before = new LinkedHashMap<>();
        before.put("z.txt", "z\n");
        before.put("a.txt", "a\n");
        Map<String, String> after = new LinkedHashMap<>();
        after.put("z.txt", "z2\n");
        after.put("a.txt", "a2\n");

        String diff = DevUnifiedDiff.between(before, after);
        assertTrue(diff.indexOf("diff --git a/a.txt") < diff.indexOf("diff --git a/z.txt"));
        assertFalse(diff.contains("m.txt"));

        String single = DevUnifiedDiff.forFile("a.txt", "a\n", "a2\n");
        assertTrue(single.startsWith("diff --git a/a.txt b/a.txt"));
    }
}
