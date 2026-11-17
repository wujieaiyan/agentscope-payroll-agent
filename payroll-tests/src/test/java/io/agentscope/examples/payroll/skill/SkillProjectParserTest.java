package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillProjectParserTest {

    private static final Path POETRY =
            Path.of("src", "test", "resources", "skill-projects", "poetry");

    @Test
    @DisplayName("解析目录式技能项目：ID、元信息、setup、白名单文件与告警")
    void parsesPoetryProject() {
        SkillProjectParser.ParsedProject project =
                SkillProjectParser.parse(POETRY, SkillFileSettings.defaults());

        assertEquals("poetry", project.id());
        assertEquals("1.0.0", project.metadata().get("version"));
        assertEquals("MIT", project.metadata().get("license"));
        assertEquals("prompt", project.metadata().get("type"));
        assertTrue(project.description().startsWith("提供诗词生成与查询能力"), project.description());
        assertTrue(project.body().contains("## 角色"));
        assertTrue(project.body().length() >= 200, String.valueOf(project.body().length()));
        assertEquals("sh setup.sh", project.setupCommand());
        assertEquals(536_870_912L, project.dataMaxBytes());

        List<String> paths =
                project.files().stream().map(SkillFilePackage.SkillFile::path).toList();
        assertTrue(
                paths.containsAll(
                        List.of(
                                "SKILL.md",
                                "PROMPT.md",
                                "README.md",
                                "LICENSE",
                                "setup.sh",
                                "references/quickstart.md",
                                "scripts/query.py",
                                "assets/sample.json")),
                paths.toString());
        assertFalse(paths.stream().anyMatch(path -> path.startsWith("data/")), paths.toString());
        assertFalse(paths.stream().anyMatch(path -> path.startsWith(".git/")), paths.toString());
        assertFalse(paths.contains("notes.md"));
        assertFalse(paths.contains("scripts/ignore.txt"));
        assertFalse(paths.contains("references/logo.bin"));

        assertTrue(project.warnings().stream().anyMatch(w -> w.contains("notes.md")));
        assertTrue(project.warnings().stream().anyMatch(w -> w.contains("scripts/ignore.txt")));
        assertTrue(project.warnings().stream().anyMatch(w -> w.contains("references/logo.bin")));
        assertTrue(project.warnings().stream().anyMatch(w -> w.contains("activation")));
    }

    @Test
    @DisplayName("缺省 setup 时存在 setup.sh 即执行，含 scripts/ 下的 setup.sh")
    void defaultsSetupWhenScriptExists(@TempDir Path temp) throws IOException {
        Path dir = Files.createDirectories(temp.resolve("demo"));
        writeSkillMd(
                dir,
                "name: demo\nversion: 1.0.0\n",
                body());
        Files.writeString(dir.resolve("setup.sh"), "#!/bin/sh\n", StandardCharsets.UTF_8);

        SkillProjectParser.ParsedProject project =
                SkillProjectParser.parse(dir, SkillFileSettings.defaults());

        assertEquals("sh setup.sh", project.setupCommand());

        Path nested = Files.createDirectories(temp.resolve("nested"));
        writeSkillMd(nested, "name: nested\n", body());
        Files.createDirectories(nested.resolve("scripts"));
        Files.writeString(
                nested.resolve("scripts/setup.sh"), "#!/bin/sh\n", StandardCharsets.UTF_8);
        assertEquals(
                "sh scripts/setup.sh",
                SkillProjectParser.parse(nested, SkillFileSettings.defaults()).setupCommand());

        Path plain = Files.createDirectories(temp.resolve("plain"));
        writeSkillMd(plain, "name: plain\n", body());
        assertNull(
                SkillProjectParser.parse(plain, SkillFileSettings.defaults()).setupCommand(),
                "没有 setup 声明也没有 setup.sh 时不应初始化");
    }

    @Test
    @DisplayName("setup 不能指向包外或不存在的脚本")
    void rejectsSetupOutsidePackage(@TempDir Path temp) throws IOException {
        Path escaped = Files.createDirectories(temp.resolve("escape"));
        writeSkillMd(escaped, "name: escape\nsetup: \"sh ../evil.sh\"\n", body());
        assertThrows(
                PayrollException.class,
                () -> SkillProjectParser.parse(escaped, SkillFileSettings.defaults()));

        Path missing = Files.createDirectories(temp.resolve("missing"));
        writeSkillMd(missing, "name: missing\nsetup: \"python3 scripts/init.py\"\n", body());
        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> SkillProjectParser.parse(missing, SkillFileSettings.defaults()));
        assertTrue(error.getMessage().contains("不存在"), error.getMessage());

        Path pipe = Files.createDirectories(temp.resolve("pipe"));
        writeSkillMd(pipe, "name: pipe\nsetup: \"sh setup.sh && rm -rf /\"\n", body());
        Files.writeString(pipe.resolve("setup.sh"), "#!/bin/sh\n", StandardCharsets.UTF_8);
        assertThrows(
                PayrollException.class,
                () -> SkillProjectParser.parse(pipe, SkillFileSettings.defaults()));
    }

    @Test
    @DisplayName("缺少 SKILL.md 或正文不达标时拒绝导入")
    void validatesSkillMd(@TempDir Path temp) throws IOException {
        Path empty = Files.createDirectories(temp.resolve("empty"));
        assertThrows(
                PayrollException.class,
                () -> SkillProjectParser.parse(empty, SkillFileSettings.defaults()));

        Path shortBody = Files.createDirectories(temp.resolve("short"));
        writeSkillMd(shortBody, "name: short\n", "## 角色\n太短了。");
        assertThrows(
                PayrollException.class,
                () -> SkillProjectParser.parse(shortBody, SkillFileSettings.defaults()));
    }

    @Test
    @DisplayName("ID 回退目录名，data.max-bytes 超硬上限按 5GB 处理")
    void fallsBackToDirectoryIdAndClampsData(@TempDir Path temp) throws IOException {
        Path dir = Files.createDirectories(temp.resolve("My Skill!"));
        writeSkillMd(dir, "version: 1.0.0\ndata:\n  max-bytes: 999999999999\n", body());

        SkillProjectParser.ParsedProject project =
                SkillProjectParser.parse(dir, SkillFileSettings.defaults());

        assertEquals("my-skill", project.id());
        assertEquals(SkillProjectParser.HARD_DATA_MAX_BYTES, project.dataMaxBytes());
        assertTrue(project.warnings().stream().anyMatch(w -> w.contains("5GB")));
    }

    @Test
    @DisplayName("导入时可以显式指定技能 ID")
    void honoursOverrideId(@TempDir Path temp) throws IOException {
        Path dir = Files.createDirectories(temp.resolve("poetry-skills"));
        writeSkillMd(dir, "name: poetry\n", body());

        SkillProjectParser.ParsedProject project =
                SkillProjectParser.parse(dir, SkillFileSettings.defaults(), "poetry-cn");

        assertEquals("poetry-cn", project.id());
    }

    @Test
    @DisplayName("技能名归一化规则")
    void normalizesIds() {
        assertEquals("poetry-skills", SkillProjectParser.normalizeId("Poetry Skills"));
        assertEquals("my-skill", SkillProjectParser.normalizeId("--My__Skill!!"));
        assertNull(SkillProjectParser.normalizeId("诗词"));
        assertNull(SkillProjectParser.normalizeId("a"));
        assertEquals(64, SkillProjectParser.normalizeId("x".repeat(80)).length());
    }

    @Test
    @DisplayName("CRLF 文本按 LF 入库，避免 Linux 沙箱执行 setup 失败")
    void normalizesCrlfToLf(@TempDir Path temp) throws IOException {
        Path dir = Files.createDirectories(temp.resolve("crlf"));
        writeSkillMd(dir, "name: crlf\n", body());
        Files.writeString(
                dir.resolve("setup.sh"),
                "#!/bin/sh\r\necho ok\r\n",
                StandardCharsets.UTF_8);

        SkillProjectParser.ParsedProject project =
                SkillProjectParser.parse(dir, SkillFileSettings.defaults());

        String script =
                project.files().stream()
                        .filter(file -> file.path().equals("setup.sh"))
                        .findFirst()
                        .orElseThrow()
                        .content();
        assertEquals("#!/bin/sh\necho ok\n", script);
        assertFalse(project.body().contains("\r\n"));
    }

    private static void writeSkillMd(Path dir, String frontmatter, String body) throws IOException {
        Files.writeString(
                dir.resolve("SKILL.md"),
                "---\n" + frontmatter + "---\n" + body,
                StandardCharsets.UTF_8);
    }

    private static String body() {
        return "## 角色\n\n"
                + "测试技能角色说明，用于校验导入与运行链路。\n\n"
                + "## 规则\n\n"
                + "1. 只基于检索结果作答，不编造数据。\n"
                + "2. 缺失字段时说明缺少哪些字段，并请用户补充。\n"
                + "3. 输出先结论后依据，最后列出风险与待确认项。\n\n"
                + "## 输出要求\n\n"
                + "使用简洁中文分点作答，金额保留两位小数；涉及政策口径时注明来源。\n"
                + "信息缺失时明确说明，并给出可执行的下一步建议，避免让用户重复提供相同信息。\n"
                + "每次回答都要复核数据口径，避免跨城市或跨批次混用规则。\n";
    }
}
