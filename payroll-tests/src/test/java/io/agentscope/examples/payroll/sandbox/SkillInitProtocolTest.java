package io.agentscope.examples.payroll.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.sandbox.SkillInitProtocol.PathAndBytes;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkillInitProtocolTest {

    @Test
    @DisplayName("技能 ID 与路径沿用执行契约的严格格式")
    void validatesIdsAndPaths() {
        assertEquals("poetry-cn", SkillInitProtocol.requireSkillId(" poetry-cn "));
        assertThrows(
                IllegalArgumentException.class, () -> SkillInitProtocol.requireSkillId("Poetry"));
        assertThrows(
                IllegalArgumentException.class, () -> SkillInitProtocol.requireSkillId("../etc"));

        assertEquals("setup.sh", SkillInitProtocol.requireSetupPath("setup.sh"));
        assertEquals("scripts/init.py", SkillInitProtocol.requireSetupPath("scripts/init.py"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillInitProtocol.requireSetupPath("scripts/init.txt"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillInitProtocol.requireSetupPath("../setup.sh"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillInitProtocol.requireSetupPath("references/setup.sh"));
    }

    @Test
    @DisplayName("包内文件只允许根固定文件与 scripts 脚本")
    void validatesPackageFiles() {
        assertEquals("SKILL.md", SkillInitProtocol.requireFilePath("SKILL.md"));
        assertEquals("scripts/a.js", SkillInitProtocol.requireFilePath("scripts/a.js"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillInitProtocol.requireFilePath("assets/logo.png"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillInitProtocol.requireFilePath("scripts/a.json"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillInitProtocol.requireFilePath("data/poems.txt"));

        assertTrue(SkillInitProtocol.isInitPath("setup.sh"));
        assertTrue(SkillInitProtocol.isInitPath("scripts/query.py"));
        assertFalse(SkillInitProtocol.isInitPath("assets/sample.json"));
        assertFalse(SkillInitProtocol.isInitPath("references/quickstart.md"));
        assertFalse(SkillInitProtocol.isInitPath("notes.md"));
        assertFalse(SkillInitProtocol.isInitPath("../setup.sh"));
    }

    @Test
    @DisplayName("超时与数据上限有默认值与硬顶")
    void clampsLimits() {
        assertEquals(600_000, SkillInitProtocol.requireTimeout(null, 0));
        assertEquals(900_000, SkillInitProtocol.requireTimeout(10_000_000, 600_000));
        assertEquals(120_000, SkillInitProtocol.requireTimeout(120_000, 600_000));
        assertEquals(2L * 1024 * 1024 * 1024, SkillInitProtocol.requireMaxBytes(null, 0));
        assertEquals(5L * 1024 * 1024 * 1024, SkillInitProtocol.requireMaxBytes(9_000_000_000L, 0));
    }

    @Test
    @DisplayName("重复路径与总量超限直接拒绝")
    void validatesFileSizes() {
        List<PathAndBytes> ok =
                List.of(new PathAndBytes("SKILL.md", 100), new PathAndBytes("scripts/a.py", 200));
        assertEquals(List.of(100, 200), SkillInitProtocol.validateFiles(ok));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SkillInitProtocol.validateFiles(
                                List.of(
                                        new PathAndBytes("SKILL.md", 10),
                                        new PathAndBytes("SKILL.md", 10))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SkillInitProtocol.validateFiles(
                                List.of(new PathAndBytes("SKILL.md", SkillInitProtocol.MAX_FILE_BYTES + 1))));
    }

    @Test
    @DisplayName("状态路径与解释器按后缀固定")
    void buildsPathsAndInterpreters() {
        assertEquals("/internal/skills/poetry", SkillInitProtocol.statusPath("poetry"));
        assertTrue(SkillInitProtocol.interpreters("setup.sh").contains("/bin/sh"));
        assertTrue(SkillInitProtocol.interpreters("scripts/a.py").contains("python3"));
        assertTrue(SkillInitProtocol.interpreters("scripts/a.js").contains("node"));
    }

    @Test
    @DisplayName("声明 bash 的 setup 用 bash 执行，其余 sh 脚本仍用 sh")
    void honoursBashShebang() {
        assertTrue(
                SkillInitProtocol.interpreters("setup.sh", "#!/bin/bash\necho hi\n")
                        .contains("/bin/bash"));
        assertTrue(
                SkillInitProtocol.interpreters("setup.sh", "#!/usr/bin/env bash\necho hi\n")
                        .contains("/bin/bash"));
        assertTrue(
                SkillInitProtocol.interpreters("setup.sh", "#!/usr/bin/env -S bash -e\necho hi\n")
                        .contains("/bin/bash"));
        assertTrue(
                SkillInitProtocol.interpreters("setup.sh", "#!/bin/sh\necho hi\n")
                        .contains("/bin/sh"));
        assertTrue(SkillInitProtocol.interpreters("setup.sh", null).contains("/bin/sh"));
        assertTrue(SkillInitProtocol.interpreters("setup.sh", "echo hi\n").contains("/bin/sh"));
    }
}
