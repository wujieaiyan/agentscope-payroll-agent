package io.agentscope.examples.payroll.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SandboxProtocolTest {

    @Test
    @DisplayName("脚本路径只允许 scripts/ 下的三种后缀")
    void acceptsOnlyScriptPaths() {
        assertEquals("scripts/check.py", SandboxProtocol.requireScriptPath("scripts/check.py"));
        assertEquals("scripts/sub/a.sh", SandboxProtocol.requireScriptPath(" scripts/sub/a.sh "));
        assertEquals("scripts/a.js", SandboxProtocol.requireScriptPath("scripts/a.js"));

        assertThrows(
                IllegalArgumentException.class,
                () -> SandboxProtocol.requireScriptPath("references/a.md"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SandboxProtocol.requireScriptPath("scripts/a.txt"));
        assertThrows(
                IllegalArgumentException.class, () -> SandboxProtocol.requireScriptPath("/etc/passwd"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SandboxProtocol.requireScriptPath("scripts/../secret.py"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SandboxProtocol.requireScriptPath("scripts\\a.py"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SandboxProtocol.requireScriptPath("scripts/a b.py"));
        assertThrows(IllegalArgumentException.class, () -> SandboxProtocol.requireScriptPath(""));
    }

    @Test
    @DisplayName("参数逐个校验且返回不可变列表")
    void validatesArguments() {
        assertEquals(List.of("--month", "2026-11"), SandboxProtocol.requireArgs(List.of("--month", "2026-11")));
        assertTrue(SandboxProtocol.requireArgs(null).isEmpty());

        List<String> tooMany = new ArrayList<>();
        for (int index = 0; index <= SandboxProtocol.MAX_ARGS; index++) {
            tooMany.add("a" + index);
        }
        assertThrows(IllegalArgumentException.class, () -> SandboxProtocol.requireArgs(tooMany));
        assertThrows(IllegalArgumentException.class, () -> SandboxProtocol.requireArgs(List.of("a\nb")));
        assertThrows(IllegalArgumentException.class, () -> SandboxProtocol.requireArgs(List.of("a\0b")));
        assertThrows(
                IllegalArgumentException.class,
                () -> SandboxProtocol.requireArgs(List.of("x".repeat(201))));
    }

    @Test
    @DisplayName("超时被夹到上限，解释器按后缀固定")
    void normalizesTimeoutAndInterpreter() {
        assertEquals(SandboxProtocol.DEFAULT_TIMEOUT_MS, SandboxProtocol.requireTimeout(0));
        assertEquals(5000, SandboxProtocol.requireTimeout(5000));
        assertEquals(SandboxProtocol.MAX_TIMEOUT_MS, SandboxProtocol.requireTimeout(60_000));

        assertEquals(List.of("python3", "-B"), SandboxProtocol.interpreter("scripts/a.py"));
        assertEquals(List.of("/bin/sh"), SandboxProtocol.interpreter("scripts/a.sh"));
        assertEquals(List.of("node"), SandboxProtocol.interpreter("scripts/a.js"));
    }

    @Test
    @DisplayName("技能 ID 校验拒绝大写、短横线开头与非 ASCII")
    void validatesSkillId() {
        assertEquals("payroll-auditor", SandboxProtocol.requireSkillId("payroll-auditor"));
        assertThrows(IllegalArgumentException.class, () -> SandboxProtocol.requireSkillId("Payroll"));
        assertThrows(IllegalArgumentException.class, () -> SandboxProtocol.requireSkillId("-x"));
        assertThrows(IllegalArgumentException.class, () -> SandboxProtocol.requireSkillId("技能"));
    }

    @Test
    @DisplayName("脚本哈希稳定且对内容敏感")
    void hashesScripts() {
        String first = SandboxProtocol.scriptSha256("print('ok')");
        assertEquals(first, SandboxProtocol.scriptSha256("print('ok')"));
        assertFalse(first.equals(SandboxProtocol.scriptSha256("print('no')")));
        assertEquals(64, first.length());
    }
}
