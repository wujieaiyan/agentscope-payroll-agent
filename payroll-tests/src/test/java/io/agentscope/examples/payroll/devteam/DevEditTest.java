package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.PayrollException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DevEditTest {

    @Test
    @DisplayName("整文件写入：空内容归一为空串，路径去空白")
    void writeNormalizes() {
        DevEdit edit = DevEdit.write(" payroll-common/src/main/java/A.java ", null);
        assertEquals(DevEdit.Kind.WRITE, edit.kind());
        assertEquals("payroll-common/src/main/java/A.java", edit.path());
        assertEquals("", edit.content());
        assertEquals("write payroll-common/src/main/java/A.java", edit.describe());
    }

    @Test
    @DisplayName("写入不能携带 oldText/newText")
    void writeRejectsReplacementFields() {
        assertThrows(PayrollException.class, () -> new DevEdit(DevEdit.Kind.WRITE, "a", "c", "x", null));
        assertThrows(PayrollException.class, () -> new DevEdit(DevEdit.Kind.WRITE, "a", "c", null, "y"));
    }

    @Test
    @DisplayName("替换要求非空 oldText 且新旧内容不同")
    void replaceValidates() {
        assertThrows(PayrollException.class, () -> DevEdit.replace("a.java", "", "b"));
        assertThrows(PayrollException.class, () -> DevEdit.replace("a.java", null, "b"));
        assertThrows(PayrollException.class, () -> DevEdit.replace("a.java", "same", "same"));
    }

    @Test
    @DisplayName("替换不能携带整文件内容，路径不能为空")
    void replaceRejectsContentAndBlankPath() {
        assertThrows(
                PayrollException.class,
                () -> new DevEdit(DevEdit.Kind.REPLACE, "a.java", "whole", "old", "new"));
        assertThrows(PayrollException.class, () -> DevEdit.write("  ", "x"));
        assertThrows(PayrollException.class, () -> DevEdit.replace(null, "old", "new"));
    }

    @Test
    @DisplayName("替换的新内容允许为空串，表示删除片段")
    void replaceAllowsEmptyNewText() {
        DevEdit edit = DevEdit.replace("a.java", "old", null);
        assertEquals("", edit.newText());
        assertTrue(edit.describe().startsWith("replace "));
    }
}
