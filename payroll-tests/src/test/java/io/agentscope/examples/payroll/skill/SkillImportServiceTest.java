package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SkillImportServiceTest {

    private static final Path POETRY =
            Path.of("src", "test", "resources", "skill-projects", "poetry");

    @TempDir
    Path temp;

    private FakeNacosServer nacos;

    @AfterEach
    void stopServer() {
        if (nacos != null) {
            nacos.close();
        }
    }

    @Test
    @DisplayName("ZIP 导入带一层目录的技能项目并发布到 Nacos")
    void importsZipWithTopDirectory() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry();
        SkillImportService service = service(registry);
        Path zip = temp.resolve("poetry-skills.zip");
        zipFixture(zip, "poetry-skills/");

        SkillImportService.ImportResult result = service.importZip(zip, null, false, false);

        assertEquals("poetry", result.id());
        assertNull(result.commitSha());
        assertEquals("sh setup.sh", result.setupCommand());
        assertEquals(536_870_912L, result.dataMaxBytes());
        assertFalse(result.dryRun());
        assertFalse(result.existing());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("references/logo.bin")));

        String markdown = nacos.configs.get("poetry.md");
        assertTrue(markdown.contains("version: 1.0.0"), markdown);
        assertTrue(markdown.contains("license: MIT"), markdown);
        assertTrue(markdown.contains("setup: sh setup.sh"), markdown);
        String files = nacos.configs.get("poetry.files.json");
        assertTrue(files.contains("\"version\" : 2"), files);

        NacosSkillRegistry reloaded = registry();
        assertEquals(8, reloaded.detail("poetry").files().size());
        assertTrue(reloaded.detail("poetry").metadata().containsKey("activation"));
    }

    @Test
    @DisplayName("ZIP 根目录直接放 SKILL.md 也能导入，dryRun 不写 Nacos")
    void importsZipAtRootAndPreviews() throws IOException {
        nacos = new FakeNacosServer();
        SkillImportService service = service(registry());
        Path zip = temp.resolve("poetry.zip");
        zipFixture(zip, "");

        SkillImportService.ImportResult result = service.importZip(zip, null, false, true);

        assertEquals("poetry", result.id());
        assertTrue(result.dryRun());
        assertTrue(nacos.configs.isEmpty(), "dryRun 不应写 Nacos");
    }

    @Test
    @DisplayName("同名技能需显式覆盖")
    void requiresOverwriteForExisting() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry();
        SkillImportService service = service(registry);
        Path zip = temp.resolve("poetry.zip");
        zipFixture(zip, "poetry/");
        service.importZip(zip, null, false, false);

        assertThrows(
                PayrollConflictException.class,
                () -> service.importZip(zip, null, false, false));

        SkillImportService.ImportResult overwritten =
                service.importZip(zip, null, true, false);
        assertEquals("poetry", overwritten.id());
        assertTrue(overwritten.existing());
    }

    @Test
    @DisplayName("显式 ID 生效，且覆盖内置技能需要确认")
    void importsWithExplicitId() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry();
        SkillImportService service = service(registry);
        Path zip = temp.resolve("auditor.zip");
        zipFixture(zip, "");

        SkillImportService.ImportResult custom =
                service.importZip(zip, "poetry-cn", false, false);
        assertEquals("poetry-cn", custom.id());
        assertTrue(
                nacos.configs.get("poetry-cn.md").contains("name: poetry-cn"),
                nacos.configs.get("poetry-cn.md"));

        assertThrows(
                PayrollConflictException.class,
                () -> service.importZip(zip, RoleSkills.CALCULATOR, false, false));

        service.importZip(zip, RoleSkills.CALCULATOR, true, false);
        NacosSkillRegistry reloaded = registry();
        assertTrue(reloaded.detail(RoleSkills.CALCULATOR).overridden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../evil.md", "/etc/passwd", "poetry/../evil.md", "..\\evil.md"})
    @DisplayName("ZIP 拒绝越界、绝对路径与反斜杠条目")
    void rejectsUnsafeZipEntries(String name) throws IOException {
        nacos = new FakeNacosServer();
        SkillImportService service = service(registry());
        Path zip = temp.resolve("bad.zip");
        writeZip(zip, List.of(new Entry(name, "x"), new Entry("SKILL.md", skillMd())));

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> service.importZip(zip, null, false, false));

        assertTrue(error.getMessage().contains("ZIP"), error.getMessage());
        assertTrue(nacos.configs.isEmpty());
    }

    @Test
    @DisplayName("ZIP 拒绝符号链接条目")
    void rejectsSymlinkEntry() throws IOException {
        nacos = new FakeNacosServer();
        SkillImportService service = service(registry());
        Path zip = temp.resolve("link.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(zip.toFile())) {
            ZipArchiveEntry entry = new ZipArchiveEntry("poetry/scripts/query.py");
            entry.setUnixMode(0120777);
            out.putArchiveEntry(entry);
            out.write("scripts/query.py".getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
        }

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> service.importZip(zip, null, false, false));
        assertTrue(error.getMessage().contains("符号链接"), error.getMessage());
    }

    @Test
    @DisplayName("ZIP 超过大小上限直接拒绝")
    void rejectsOversizedZip() throws IOException {
        nacos = new FakeNacosServer();
        SkillImportService service =
                new SkillImportService(
                        registry(),
                        SkillFileSettings.defaults(),
                        new SkillImportSettings(Set.of("github.com"), 16));
        Path zip = temp.resolve("big.zip");
        zipFixture(zip, "");

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> service.importZip(zip, null, false, false));
        assertTrue(error.getMessage().contains("超过上限"), error.getMessage());
    }

    @Test
    @DisplayName("ZIP 缺少 SKILL.md 时拒绝导入")
    void rejectsZipWithoutSkillMd() throws IOException {
        nacos = new FakeNacosServer();
        SkillImportService service = service(registry());
        Path zip = temp.resolve("empty.zip");
        writeZip(zip, List.of(new Entry("scripts/run.py", "print('x')")));

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> service.importZip(zip, null, false, false));
        assertTrue(error.getMessage().contains("SKILL.md"), error.getMessage());
    }

    @Test
    @DisplayName("Git 导入只接受白名单主机的 https 地址，且不携带账号")
    void validatesGitUrl() throws IOException {
        nacos = new FakeNacosServer();
        SkillImportService service = service(registry());

        PayrollException ssh =
                assertThrows(
                        PayrollException.class,
                        () ->
                                service.importGit(
                                        "git@github.com:wujieaiyan/poetry-skills.git",
                                        null,
                                        null,
                                        false,
                                        true));
        assertTrue(ssh.getMessage().contains("https"), ssh.getMessage());

        PayrollException host =
                assertThrows(
                        PayrollException.class,
                        () ->
                                service.importGit(
                                        "https://evil.example.com/poetry-skills.git",
                                        null,
                                        null,
                                        false,
                                        true));
        assertTrue(host.getMessage().contains("白名单"), host.getMessage());

        PayrollException credentials =
                assertThrows(
                        PayrollException.class,
                        () ->
                                service.importGit(
                                        "https://user:secret@github.com/wujieaiyan/poetry-skills.git",
                                        null,
                                        null,
                                        false,
                                        true));
        assertTrue(credentials.getMessage().contains("口令"), credentials.getMessage());
    }

    private SkillImportService service(NacosSkillRegistry registry) {
        return new SkillImportService(
                registry,
                SkillFileSettings.defaults(),
                new SkillImportSettings(
                        Set.of("github.com"), SkillImportSettings.DEFAULT_MAX_ZIP_BYTES));
    }

    private NacosSkillRegistry registry() {
        return new NacosSkillRegistry(
                new NacosSkillSettings(
                        true,
                        nacos.url(),
                        "payroll-agent",
                        "",
                        "",
                        "",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(60)));
    }

    private record Entry(String name, String content) {
    }

    private static void writeZip(Path zip, List<Entry> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(zip);
                ZipOutputStream stream = new ZipOutputStream(out)) {
            for (Entry entry : entries) {
                stream.putNextEntry(new ZipEntry(entry.name()));
                stream.write(entry.content().getBytes(StandardCharsets.UTF_8));
                stream.closeEntry();
            }
        }
    }

    /** 把 poetry fixture 打成 ZIP；prefix 非空时所有条目放到同一顶层目录下。 */
    private static void zipFixture(Path zip, String prefix) throws IOException {
        try (OutputStream out = Files.newOutputStream(zip);
                ZipOutputStream stream = new ZipOutputStream(out);
                var paths = Files.walk(POETRY)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                String rel = POETRY.relativize(file).toString().replace('\\', '/');
                stream.putNextEntry(new ZipEntry(prefix + rel));
                stream.write(Files.readAllBytes(file));
                stream.closeEntry();
            }
        }
    }

    private static String skillMd() throws IOException {
        return Files.readString(POETRY.resolve("SKILL.md"), StandardCharsets.UTF_8);
    }
}
