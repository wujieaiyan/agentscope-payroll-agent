package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry.SkillDetail;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry.SkillSummary;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NacosSkillRegistryTest {

    private static final String AUDITOR = "payroll-auditor";

    private FakeNacosServer nacos;

    @AfterEach
    void stopServer() {
        if (nacos != null) {
            nacos.close();
        }
    }

    @Test
    @DisplayName("Nacos 为空时列出三个内置技能，可写且显示存储位置")
    void listsBuiltinsWhenNacosEmpty() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true);

        List<SkillSummary> skills = registry.list();

        assertEquals(RoleSkills.ids(), skills.stream().map(SkillSummary::id).toList());
        assertTrue(skills.stream().allMatch(SkillSummary::builtin));
        assertTrue(skills.stream().allMatch(skill -> "builtin".equals(skill.source())));
        assertTrue(registry.writable());
        assertTrue(registry.location().contains("payroll-agent"), registry.location());
    }

    @Test
    @DisplayName("新增技能写入 Nacos Markdown，列表与详情可重新读取")
    void createsCustomSkill() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true);

        SkillSummary saved =
                registry.create(
                        "payroll-auditor", "薪酬审计角色", roleContent("独立审计工资数据。"));

        assertEquals("payroll-auditor", saved.id());
        assertEquals("nacos", saved.source());
        assertFalse(saved.builtin());
        String raw = nacos.configs.get("payroll-auditor.md");
        assertTrue(raw.startsWith("---"), raw);
        assertTrue(raw.contains("name: payroll-auditor"), raw);
        assertTrue(raw.contains("description: \"薪酬审计角色\""), raw);

        List<SkillSummary> skills = registry.list();
        assertEquals(RoleSkills.ids().size() + 1, skills.size());
        SkillSummary custom =
                skills.stream()
                        .filter(skill -> skill.id().equals("payroll-auditor"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("薪酬审计角色", custom.description());
        assertFalse(custom.builtin());

        SkillDetail detail = registry.detail("payroll-auditor");
        assertEquals("薪酬审计角色", detail.description());
        assertTrue(detail.content().contains("独立审计工资数据"), detail.content());
        assertFalse(detail.content().contains("description:"), detail.content());
        assertEquals("nacos", detail.source());
    }

    @Test
    @DisplayName("执行开关写入 frontmatter，重启后可读并可关闭")
    void storesExecutionFlag() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true);
        registry.create(AUDITOR, "薪酬审计角色", roleContent("独立审计工资数据。"), true);

        String raw = nacos.configs.get(AUDITOR + ".md");
        assertTrue(raw.contains("execution:\n  enabled: true"), raw);
        assertTrue(registry(true).detail(AUDITOR).executionEnabled());
        assertFalse(registry.detail(RoleSkills.CALCULATOR).executionEnabled(), "内置技能默认关闭");

        registry.update(AUDITOR, "薪酬审计角色", roleContent("独立审计工资数据。"), false);

        assertFalse(nacos.configs.get(AUDITOR + ".md").contains("execution:"), "关闭后应移除标记");
        assertFalse(registry(true).detail(AUDITOR).executionEnabled());
    }

    @Test
    @DisplayName("编辑器查详情时返回可再次保存的正文")
    void detailForBuiltinOmitsFrontmatter() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true);

        SkillDetail detail = registry.detail(RoleSkills.CALCULATOR);

        assertEquals(RoleSkills.CALCULATOR, detail.id());
        assertEquals("builtin", detail.source());
        assertTrue(detail.builtin());
        assertEquals(RoleSkills.skill(RoleSkills.CALCULATOR).getSkillContent(), detail.content());
    }

    @Test
    @DisplayName("编辑内置技能生成 Nacos 覆盖，删除后回退内置提示词")
    void overridesAndFallsBackToBuiltin() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true);

        registry.update(RoleSkills.CALCULATOR, "覆盖版计算角色", roleContent("来自 Nacos 的覆盖版。"), false);
        SkillSummary overridden =
                registry.list().stream()
                        .filter(skill -> skill.id().equals(RoleSkills.CALCULATOR))
                        .findFirst()
                        .orElseThrow();
        assertEquals("nacos", overridden.source());
        assertTrue(overridden.builtin());
        assertTrue(overridden.overridden());

        assertTrue(registry.delete(RoleSkills.CALCULATOR));
        SkillDetail fallback = registry.detail(RoleSkills.CALCULATOR);
        assertEquals("builtin", fallback.source());
        assertTrue(fallback.content().contains("薪酬计算智能体"), fallback.content());

        PayrollException error =
                assertThrows(
                        PayrollException.class, () -> registry.delete(RoleSkills.CALCULATOR));
        assertTrue(error.getMessage().contains("内置技能"), error.getMessage());
    }

    @Test
    @DisplayName("校验技能 ID、重复创建、缺失更新与正文长度")
    void validatesInput() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true);

        PayrollException badId =
                assertThrows(
                        PayrollException.class,
                        () -> registry.create("Bad ID", "", "正文"));
        assertTrue(badId.getMessage().contains("技能 ID"), badId.getMessage());

        PayrollException duplicate =
                assertThrows(
                        PayrollException.class,
                        () -> registry.create(RoleSkills.CALCULATOR, "", "正文"));
        assertTrue(duplicate.getMessage().contains("已存在"), duplicate.getMessage());

        PayrollException missing =
                assertThrows(
                        PayrollException.class,
                        () -> registry.update("no-such-skill", "", "正文", false));
        assertTrue(missing.getMessage().contains("不存在"), missing.getMessage());

        PayrollException blank =
                assertThrows(
                        PayrollException.class,
                        () -> registry.create("payroll-auditor", "", "   "));
        assertTrue(blank.getMessage().contains("正文"), blank.getMessage());

        PayrollException longDescription =
                assertThrows(
                        PayrollException.class,
                        () ->
                                registry.create(
                                        "payroll-auditor", "x".repeat(201), "正文"));
        assertTrue(longDescription.getMessage().contains("描述"), longDescription.getMessage());

        PayrollException longContent =
                assertThrows(
                        PayrollException.class,
                        () ->
                                registry.create(
                                        "payroll-auditor", "", "x".repeat(200_001)));
        assertTrue(longContent.getMessage().contains("200000"), longContent.getMessage());

        assertTrue(nacos.configs.isEmpty());
    }

    @Test
    @DisplayName("TTL 内复用技能列表，不重复查询 Nacos")
    void cachesListWithinTtl() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true);

        registry.list();
        registry.list();

        // Nacos 为空时只需要列出正文；第二次 list() 命中 TTL 缓存。
        assertEquals(1, nacos.reads.get());
    }

    @Test
    @DisplayName("列表失败时沿用上次成功快照，文件缓存不清空")
    void keepsSnapshotAndFileCacheWhenListFails() throws Exception {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true, Duration.ofMillis(1));
        registry.create(AUDITOR, "薪酬审计角色", roleContent("独立审计工资数据。"));
        registry.replaceFiles(AUDITOR, List.of(file("references/faq.md", "# FAQ")));
        assertEquals("# FAQ", registry.fileContent(AUDITOR, "references/faq.md"));
        assertEquals(Set.of(AUDITOR), fileCacheKeys(registry));

        nacos.failNextList.set(1);
        Thread.sleep(5);
        List<SkillSummary> skills = registry.list();

        assertTrue(
                skills.stream().anyMatch(skill -> skill.id().equals(AUDITOR)),
                "列表失败时应沿用上次快照，自定义技能不应消失");
        assertEquals(Set.of(AUDITOR), fileCacheKeys(registry), "文件缓存不应被整份清空");
    }

    @Test
    @DisplayName("成功刷新后裁剪已删除技能的文件缓存")
    void trimsFileCacheOnSuccessfulReload() throws Exception {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true, Duration.ofMillis(1));
        registry.create(AUDITOR, "薪酬审计角色", roleContent("独立审计工资数据。"));
        registry.replaceFiles(AUDITOR, List.of(file("references/faq.md", "# FAQ")));
        registry.fileContent(AUDITOR, "references/faq.md");
        assertEquals(Set.of(AUDITOR), fileCacheKeys(registry));

        assertTrue(registry.delete(AUDITOR));
        Thread.sleep(5);
        registry.list();

        assertTrue(fileCacheKeys(registry).isEmpty(), "删除技能后文件缓存应被裁掉");
    }

    @Test
    @DisplayName("文件包写入 Nacos，重启后仍可读取正文与文件")
    void storesFilePackage() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true);
        registry.create(AUDITOR, "薪酬审计角色", roleContent("独立审计工资数据。"));

        List<SkillFilePackage.Summary> saved =
                registry.replaceFiles(
                        AUDITOR,
                        List.of(
                                file("references/faq.md", "# FAQ\n口径说明"),
                                file("scripts/check.py", "print('ok')")));

        assertEquals(
                List.of("references/faq.md", "scripts/check.py"),
                saved.stream().map(SkillFilePackage.Summary::path).toList());
        assertEquals(SkillFilePackage.KIND_REFERENCE, saved.get(0).kind());
        assertEquals(SkillFilePackage.KIND_SCRIPT, saved.get(1).kind());
        String raw = nacos.configs.get(AUDITOR + ".files.json");
        assertTrue(raw.contains("\"mdHash\""), raw);

        NacosSkillRegistry restarted = registry(true);
        assertEquals(2, restarted.detail(AUDITOR).files().size());
        assertEquals("# FAQ\n口径说明", restarted.fileContent(AUDITOR, "references/faq.md"));
    }

    @Test
    @DisplayName("改正文后自动重绑文件包哈希，避免版本错配")
    void rebindsHashOnUpdate() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true);
        registry.create(AUDITOR, "薪酬审计角色", roleContent("独立审计工资数据。"));
        registry.replaceFiles(AUDITOR, List.of(file("references/faq.md", "# FAQ")));

        registry.update(AUDITOR, "更新描述", roleContent("更新后的审计规则。"), false);

        SkillDetail detail = registry(true).detail(AUDITOR);
        assertTrue(detail.content().contains("更新后的审计规则"), detail.content());
        assertEquals(1, detail.files().size(), "保存正文应重绑文件包，读取时不应丢弃");
        assertTrue(
                nacos.configs.get(AUDITOR + ".files.json")
                        .contains(SkillFilePackage.mdHash(detail.content())),
                "文件包哈希应跟随最新正文");
    }

    @Test
    @DisplayName("哈希不一致的文件包被忽略，正文照常可读")
    void mismatchedPackageIgnored() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true);
        registry.create(AUDITOR, "薪酬审计角色", roleContent("独立审计工资数据。"));
        nacos.configs.put(
                AUDITOR + ".files.json",
                SkillFilePackage.compose(
                        SkillFilePackage.mdHash("旧正文"), List.of(file("references/faq.md", "# FAQ"))));

        SkillDetail detail = registry(true).detail(AUDITOR);

        assertTrue(detail.files().isEmpty(), "哈希不一致时不应展示旧文件");
        assertTrue(detail.content().contains("独立审计工资数据"), detail.content());
    }

    @Test
    @DisplayName("校验文件路径、类型、数量与大小")
    void validatesFilePackage() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true);
        registry.create(AUDITOR, "薪酬审计角色", roleContent("独立审计工资数据。"));

        assertThrows(
                PayrollException.class,
                () -> registry.replaceFiles(AUDITOR, List.of(file("../secret.md", "x"))));
        assertThrows(
                PayrollException.class,
                () -> registry.replaceFiles(AUDITOR, List.of(file("/etc/passwd", "x"))));
        assertThrows(
                PayrollException.class,
                () -> registry.replaceFiles(AUDITOR, List.of(file("references/../../x.md", "x"))));
        assertThrows(
                PayrollException.class,
                () -> registry.replaceFiles(AUDITOR, List.of(file("docs/a.md", "x"))));
        assertThrows(
                PayrollException.class,
                () -> registry.replaceFiles(AUDITOR, List.of(file("scripts/run.txt", "x"))));
        assertThrows(
                PayrollException.class,
                () ->
                        registry.replaceFiles(
                                AUDITOR,
                                List.of(
                                        file("references/a.md", "x"),
                                        file("references/a.md", "y"))));
        assertThrows(
                PayrollException.class,
                () ->
                        registry.replaceFiles(
                                AUDITOR,
                                List.of(file("references/big.md", "x".repeat(512 * 1024 + 1)))));
        List<SkillFilePackage.SkillFile> many =
                IntStream.rangeClosed(1, 201)
                        .mapToObj(index -> file("references/f" + index + ".md", "x"))
                        .toList();
        assertThrows(PayrollException.class, () -> registry.replaceFiles(AUDITOR, many));
        List<SkillFilePackage.SkillFile> heavy =
                IntStream.rangeClosed(1, 9)
                        .mapToObj(index -> file("references/heavy" + index + ".md", "x".repeat(500_000)))
                        .toList();
        assertThrows(PayrollException.class, () -> registry.replaceFiles(AUDITOR, heavy));

        assertFalse(nacos.configs.containsKey(AUDITOR + ".files.json"));
    }

    @Test
    @DisplayName("接受技能项目根文件、assets 与脚本，拒绝 data 与非白名单根文件")
    void acceptsProjectFiles() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true);
        registry.create(AUDITOR, "薪酬审计角色", roleContent("独立审计工资数据。"));

        List<SkillFilePackage.Summary> saved =
                registry.replaceFiles(
                        AUDITOR,
                        List.of(
                                file("SKILL.md", "# 技能"),
                                file("PROMPT.md", "提示"),
                                file("README.md", "说明"),
                                file("LICENSE", "MIT"),
                                file("setup.sh", "echo ok"),
                                file("references/faq.md", "# FAQ"),
                                file("scripts/check.py", "print('ok')"),
                                file("assets/data.json", "{}")));

        assertEquals(8, saved.size());
        assertEquals(SkillFilePackage.KIND_SCRIPT, kindOf(saved, "scripts/check.py"));
        assertEquals(SkillFilePackage.KIND_ASSET, kindOf(saved, "assets/data.json"));
        assertEquals(SkillFilePackage.KIND_REFERENCE, kindOf(saved, "README.md"));

        assertTrue(SkillFilePackage.isReadable("SKILL.md"));
        assertTrue(SkillFilePackage.isReadable("PROMPT.md"));
        assertFalse(SkillFilePackage.isReadable("setup.sh"));
        assertFalse(SkillFilePackage.isReadable("assets/data.json"));
        assertThrows(PayrollException.class, () -> SkillFilePackage.normalizeReadPath("setup.sh"));

        assertThrows(
                PayrollException.class,
                () -> registry.replaceFiles(AUDITOR, List.of(file("data/poetry.json", "{}"))));
        assertThrows(
                PayrollException.class,
                () -> registry.replaceFiles(AUDITOR, List.of(file("notes.md", "x"))));
    }

    @Test
    @DisplayName("保存正文保留导入项目的 version/license/activation/setup 等元信息")
    void preservesProjectMetadata() throws IOException {
        nacos = new FakeNacosServer();
        String body = roleContent("诗词查询规则。");
        nacos.configs.put(
                "poetry.md",
                "---\n"
                        + "name: poetry\n"
                        + "version: 1.0.0\n"
                        + "license: MIT\n"
                        + "description: \"诗词检索与生成\"\n"
                        + "type: prompt\n"
                        + "activation:\n"
                        + "  - trigger: \"诗词生成\"\n"
                        + "    description: \"写诗时激活\"\n"
                        + "setup: \"sh setup.sh\"\n"
                        + "data:\n"
                        + "  max-bytes: 536870912\n"
                        + "execution:\n"
                        + "  enabled: true\n"
                        + "  timeout-seconds: 20\n"
                        + "---\n"
                        + body);
        NacosSkillRegistry registry = registry(true);

        SkillDetail detail = registry.detail("poetry");
        assertEquals("诗词检索与生成", detail.description());
        assertEquals("1.0.0", detail.metadata().get("version"));
        assertTrue(detail.executionEnabled());
        assertTrue(detail.metadata().containsKey("activation"));

        registry.update("poetry", "诗词检索与生成", body, true);

        String raw = nacos.configs.get("poetry.md");
        assertTrue(raw.contains("version: 1.0.0"), raw);
        assertTrue(raw.contains("license: MIT"), raw);
        assertTrue(raw.contains("type: prompt"), raw);
        assertTrue(raw.contains("setup: sh setup.sh"), raw);
        assertTrue(raw.contains("max-bytes: 536870912"), raw);
        assertTrue(raw.contains("trigger: \"诗词生成\""), raw);
        assertTrue(raw.contains("execution:\n  timeout-seconds: 20\n  enabled: true"), raw);

        SkillDetail reloaded = registry(true).detail("poetry");
        assertEquals("1.0.0", reloaded.metadata().get("version"));
        assertTrue(reloaded.executionEnabled());
    }

    @Test
    @DisplayName("v2 写入并兼容读取 v1 文件包")
    void readsLegacyV1Package() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true);
        registry.create(AUDITOR, "薪酬审计角色", roleContent("独立审计工资数据。"));
        String body = registry.detail(AUDITOR).content();
        nacos.configs.put(
                AUDITOR + ".files.json",
                "{\"version\":1,\"mdHash\":\""
                        + SkillFilePackage.mdHash(body)
                        + "\",\"files\":[{\"path\":\"references/faq.md\",\"content\":\"# FAQ\"}]}");

        NacosSkillRegistry reloaded = registry(true);
        SkillDetail detail = reloaded.detail(AUDITOR);
        assertEquals(1, detail.files().size());
        assertEquals("# FAQ", reloaded.fileContent(AUDITOR, "references/faq.md"));

        registry.replaceFiles(AUDITOR, List.of(file("references/faq.md", "# FAQ")));
        assertTrue(
                nacos.configs.get(AUDITOR + ".files.json").contains("\"version\" : 2"),
                nacos.configs.get(AUDITOR + ".files.json"));
    }

    @Test
    @DisplayName("删除单个文件与删除技能联动清理文件包")
    void deletesFileAndPackage() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true);
        registry.create(AUDITOR, "薪酬审计角色", roleContent("独立审计工资数据。"));
        registry.replaceFiles(
                AUDITOR,
                List.of(file("references/faq.md", "a"), file("references/tpl.md", "b")));

        registry.deleteFile(AUDITOR, "references/faq.md");
        assertEquals(
                List.of("references/tpl.md"),
                registry.files(AUDITOR).stream().map(SkillFilePackage.Summary::path).toList());
        assertThrows(
                PayrollException.class, () -> registry.fileContent(AUDITOR, "references/faq.md"));

        assertTrue(registry.delete(AUDITOR));
        assertFalse(nacos.configs.containsKey(AUDITOR + ".md"));
        assertFalse(nacos.configs.containsKey(AUDITOR + ".files.json"));
    }

    @Test
    @DisplayName("内置技能需先覆盖到 Nacos 才能上传文件")
    void builtinNeedsOverrideForFiles() throws IOException {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true);

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () ->
                                registry.replaceFiles(
                                        RoleSkills.CALCULATOR,
                                        List.of(file("references/faq.md", "x"))));

        assertTrue(error.getMessage().contains("内置技能"), error.getMessage());
    }

    @Test
    @DisplayName("Nacos 未启用时管理能力只读")
    void disabledIsReadOnly() {
        NacosSkillSettings settings =
                new NacosSkillSettings(
                        false,
                        "http://127.0.0.1:8848",
                        "payroll-agent",
                        "",
                        "",
                        "",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(60));
        NacosSkillRegistry registry = new NacosSkillRegistry(settings);

        assertFalse(registry.writable());
        assertEquals("代码内置", registry.location());
        assertEquals(RoleSkills.ids().size(), registry.list().size());
        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> registry.create("payroll-auditor", "", "正文"));
        assertTrue(error.getMessage().contains("Nacos 未启用"), error.getMessage());
    }

    @Test
    @DisplayName("文件缓存条数超限时按访问序淘汰最久未用条目")
    void evictsLeastRecentlyUsedFileCacheEntry() throws Exception {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true, Duration.ofSeconds(60), 2, 32L * 1024 * 1024);
        for (String id : List.of("skill-a", "skill-b", "skill-c")) {
            registry.create(id, "角色", roleContent("规则"));
            registry.replaceFiles(id, List.of(file("references/faq.md", "# " + id)));
        }
        assertEquals(Set.of("skill-b", "skill-c"), fileCacheKeys(registry), "最早写入的条目应被淘汰");

        // 访问 skill-b 使其成为最近使用项，再写入 skill-d 应淘汰 skill-c。
        registry.fileContent("skill-b", "references/faq.md");
        registry.create("skill-d", "角色", roleContent("规则"));
        registry.replaceFiles("skill-d", List.of(file("references/faq.md", "# skill-d")));

        assertEquals(Set.of("skill-b", "skill-d"), fileCacheKeys(registry));
    }

    @Test
    @DisplayName("文件缓存正文总字节超限时同样按 LRU 淘汰")
    void evictsFileCacheByBytes() throws Exception {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true, Duration.ofSeconds(60), 8, 150);
        String payload = "x".repeat(100);
        registry.create("skill-a", "角色", roleContent("规则"));
        registry.replaceFiles("skill-a", List.of(file("references/a.md", payload)));
        registry.create("skill-b", "角色", roleContent("规则"));
        registry.replaceFiles("skill-b", List.of(file("references/b.md", payload)));

        assertEquals(Set.of("skill-b"), fileCacheKeys(registry), "两倍上限的正文只应保留最近一条");
    }

    @Test
    @DisplayName("正文变化后文件缓存按 mdHash 失效，不再返回旧文件")
    void invalidatesFileCacheWhenBodyChanges() throws Exception {
        nacos = new FakeNacosServer();
        NacosSkillRegistry registry = registry(true);
        registry.create(AUDITOR, "角色", roleContent("初版规则。"));
        registry.replaceFiles(AUDITOR, List.of(file("references/faq.md", "# FAQ")));
        assertEquals("# FAQ", registry.fileContent(AUDITOR, "references/faq.md"));

        // 模拟其他节点改写正文：快照重建后 mdHash 变化，缓存必须失效而不是继续返回旧文件。
        nacos.configs.put(
                AUDITOR + ".md",
                "---\nname: " + AUDITOR + "\ndescription: \"角色\"\n---\n" + roleContent("新版规则。"));
        registry.create("cache-probe", "角色", roleContent("规则"));

        assertTrue(registry.files(AUDITOR).isEmpty(), "正文变化后不应继续使用旧文件缓存");
    }

    private static SkillFilePackage.SkillFile file(String path, String content) {
        return new SkillFilePackage.SkillFile(path, content);
    }

    private static String kindOf(List<SkillFilePackage.Summary> files, String path) {
        return files.stream()
                .filter(file -> file.path().equals(path))
                .map(SkillFilePackage.Summary::kind)
                .findFirst()
                .orElseThrow();
    }

    private static String roleContent(String marker) {
        return "## 角色\n"
                + marker
                + "\n\n## 规则\n"
                + "1. 先确认用户诉求与处理对象，再给出结论。\n"
                + "2. 只基于已获取的数据作答，不得编造或推测缺失数据。\n"
                + "3. 输出先结论、后依据，最后列出风险与待确认项。\n\n"
                + "## 输出要求\n"
                + "使用简洁中文分点作答，金额保留两位小数，不输出内部工具调用过程。\n"
                + "涉及政策口径时注明数据来源；信息缺失时明确说明缺少哪些字段，并请用户补充。\n"
                + "每次答复结尾给出下一步建议，便于用户确认是否需要继续处理。\n"
                + "若用户问题超出本角色职责，直接说明原因并给出可执行的替代方案。\n";
    }

    private NacosSkillRegistry registry(boolean enabled) {
        return registry(enabled, Duration.ofSeconds(60));
    }

    private NacosSkillRegistry registry(boolean enabled, Duration ttl) {
        NacosSkillSettings settings =
                new NacosSkillSettings(
                        enabled,
                        nacos.url(),
                        "payroll-agent",
                        "",
                        "",
                        "",
                        Duration.ofSeconds(2),
                        ttl);
        return new NacosSkillRegistry(settings);
    }

    private NacosSkillRegistry registry(
            boolean enabled, Duration ttl, int maxEntries, long maxBytes) {
        NacosSkillSettings settings =
                new NacosSkillSettings(
                        enabled,
                        nacos.url(),
                        "payroll-agent",
                        "",
                        "",
                        "",
                        Duration.ofSeconds(2),
                        ttl);
        return new NacosSkillRegistry(settings, SkillFileSettings.defaults(), maxEntries, maxBytes);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> fileCacheKeys(NacosSkillRegistry registry) throws Exception {
        Field field = NacosSkillRegistry.class.getDeclaredField("fileCache");
        field.setAccessible(true);
        Object cache = field.get(registry);
        Field entriesField = cache.getClass().getDeclaredField("entries");
        entriesField.setAccessible(true);
        Map<String, ?> entries = (Map<String, ?>) entriesField.get(cache);
        return Set.copyOf(entries.keySet());
    }
}
