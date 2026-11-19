package io.agentscope.examples.payroll.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.SandboxGateway;
import io.agentscope.examples.payroll.skill.NacosAgentRegistry;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry.SkillDetail;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry.SkillSummary;
import io.agentscope.examples.payroll.skill.RoleSkills;
import io.agentscope.examples.payroll.skill.SkillFilePackage;
import io.agentscope.examples.payroll.skill.SkillImportService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SkillAdminControllerTest {

    private final NacosSkillRegistry registry = mock(NacosSkillRegistry.class);
    private final NacosAgentRegistry agentRegistry = mock(NacosAgentRegistry.class);
    private final AuditService auditService = mock(AuditService.class);
    private final SkillImportService importService = mock(SkillImportService.class);
    private final SandboxGateway gateway = mock(SandboxGateway.class);
    private final SkillAdminController controller =
            new SkillAdminController(registry, agentRegistry, auditService, importService, gateway);

    @Test
    @DisplayName("列表返回存储状态并按关键字过滤")
    void listReturnsStorageAndFilters() {
        when(registry.list())
                .thenReturn(
                        List.of(
                                summary(RoleSkills.CALCULATOR, "计算", "builtin", true),
                                summary("payroll-auditor", "薪酬审计", "nacos", false)));
        when(registry.writable()).thenReturn(true);
        when(registry.location()).thenReturn("http://nacos/payroll-agent");
        when(agentRegistry.list()).thenReturn(List.of());

        Map<?, ?> all = (Map<?, ?>) controller.list(null, null, null, null, null).data();
        assertEquals(true, all.get("writable"));
        assertEquals("http://nacos/payroll-agent", all.get("location"));
        assertEquals(2, all.get("total"));
        assertEquals(1, all.get("page"));
        assertEquals(SkillAdminController.DEFAULT_PAGE_SIZE, all.get("size"));
        assertEquals(2, ((List<?>) all.get("skills")).size());

        Map<?, ?> filtered = (Map<?, ?>) controller.list("audit", null, null, null, null).data();
        assertEquals(1, ((List<?>) filtered.get("skills")).size());
    }

    @Test
    @DisplayName("列表分页按 size 截断并返回规模指标")
    void listPaginatesAndReportsScale() {
        when(registry.list())
                .thenReturn(
                        List.of(
                                summary("skill-a", "甲", "nacos", false),
                                summary("skill-b", "乙", "nacos", false),
                                summary("skill-c", "丙", "builtin", true)));
        when(agentRegistry.list())
                .thenReturn(List.of(agent("甲智能体", "skill-a"), agent("乙智能体", "skill-b")));

        Map<?, ?> second = (Map<?, ?>) controller.list(null, 2, 2, null, null).data();

        assertEquals(3, second.get("total"));
        assertEquals(2, second.get("page"));
        assertEquals(2, second.get("size"));
        List<?> page = (List<?>) second.get("skills");
        assertEquals(1, page.size());
        assertEquals("skill-c", ((SkillSummary) page.get(0)).id());

        Map<?, ?> stats = (Map<?, ?>) second.get("stats");
        assertEquals(3, stats.get("totalSkills"));
        assertEquals(2L, stats.get("boundAgents"));
        assertEquals(2, stats.get("boundSkills"));
        assertEquals(30L, stats.get("contentBytes"));
    }

    @Test
    @DisplayName("列表支持来源过滤与按大小倒序")
    void listFiltersBySourceAndSorts() {
        when(registry.list())
                .thenReturn(
                        List.of(
                                new SkillSummary("small", "小", "nacos", false, false, 1L, 10, 0),
                                new SkillSummary("large", "大", "nacos", false, false, 2L, 90, 0),
                                new SkillSummary(RoleSkills.CALCULATOR, "内置", "builtin", true, false, 3L, 50, 0)));
        when(agentRegistry.list()).thenReturn(List.of());

        List<?> nacos =
                (List<?>)
                        ((Map<?, ?>) controller.list(null, null, null, "nacos", "size").data())
                                .get("skills");

        assertEquals(2, nacos.size());
        assertEquals("large", ((SkillSummary) nacos.get(0)).id());
        assertEquals("small", ((SkillSummary) nacos.get(1)).id());
    }

    @Test
    @DisplayName("不支持的排序字段返回明确错误")
    void listRejectsUnknownSort() {
        when(registry.list()).thenReturn(List.of());

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> controller.list(null, null, null, null, "unknown"));

        assertTrue(error.getMessage().contains("unknown"), error.getMessage());
    }

    @Test
    @DisplayName("新增返回摘要并写入审计")
    void createWritesAudit() {
        SkillSummary saved = summary("payroll-auditor", "薪酬审计", "nacos", false);
        when(registry.create("payroll-auditor", "薪酬审计", "正文", false)).thenReturn(saved);

        ApiResponse response =
                controller.create(
                        new SkillAdminController.SkillInput(
                                "payroll-auditor", "薪酬审计", "正文", false));

        assertSame(saved, response.data());
        verify(auditService).record(eq("skill.create"), eq("skill"), eq("payroll-auditor"), eq(null), any());
    }

    @Test
    @DisplayName("编辑写入前后快照审计")
    void updateWritesAudit() {
        SkillDetail before =
                new SkillDetail(
                        "payroll-auditor", "旧描述", "旧正文", "nacos", false, false, 1L, List.of(),
                        false);
        SkillSummary after = summary("payroll-auditor", "新描述", "nacos", false);
        when(registry.detail("payroll-auditor")).thenReturn(before);
        when(registry.update("payroll-auditor", "新描述", "新正文", false)).thenReturn(after);

        ApiResponse response =
                controller.update(
                        "payroll-auditor",
                        new SkillAdminController.SkillInput(null, "新描述", "新正文", null));

        assertSame(after, response.data());
        verify(auditService)
                .record("skill.update", "skill", "payroll-auditor", meta(before), meta(after));
    }

    @Test
    @DisplayName("编辑可开启脚本执行，审计快照记录开关状态")
    void updatePersistsExecutionFlag() {
        SkillDetail before =
                new SkillDetail(
                        "payroll-auditor", "旧描述", "旧正文", "nacos", false, false, 1L, List.of(),
                        true);
        SkillSummary after = summary("payroll-auditor", "新描述", "nacos", false);
        when(registry.detail("payroll-auditor")).thenReturn(before);
        when(registry.update("payroll-auditor", "新描述", "新正文", true)).thenReturn(after);

        controller.update(
                "payroll-auditor",
                new SkillAdminController.SkillInput(null, "新描述", "新正文", true));

        verify(registry).update("payroll-auditor", "新描述", "新正文", true);
        verify(auditService)
                .record("skill.update", "skill", "payroll-auditor", meta(before), meta(after));
        assertEquals(true, meta(before).get("executionEnabled"));
    }

    @Test
    @DisplayName("自定义技能被智能体引用时禁止删除")
    void deleteRejectsReferencedCustomSkill() {
        when(registry.detail("payroll-auditor"))
                .thenReturn(
                        new SkillDetail(
                                "payroll-auditor",
                                "薪酬审计",
                                "正文",
                                "nacos",
                                false,
                                false,
                                1L,
                                List.of(),
                                false));
        when(agentRegistry.list()).thenReturn(List.of(agent("个税顾问", "payroll-auditor")));

        PayrollException error =
                assertThrows(PayrollException.class, () -> controller.delete("payroll-auditor"));

        assertTrue(error.getMessage().contains("个税顾问"), error.getMessage());
        verify(registry, never()).delete(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("删除内置覆盖允许，删除后回退内置版本")
    void deleteAllowsBuiltinOverride() {
        when(registry.detail(RoleSkills.CALCULATOR))
                .thenReturn(
                        new SkillDetail(
                                RoleSkills.CALCULATOR,
                                "覆盖版",
                                "正文",
                                "nacos",
                                true,
                                true,
                                1L,
                                List.of(),
                                false));
        when(agentRegistry.list())
                .thenReturn(List.of(agent("薪酬计算专家", RoleSkills.CALCULATOR)));
        when(registry.delete(RoleSkills.CALCULATOR)).thenReturn(true);

        ApiResponse response = controller.delete(RoleSkills.CALCULATOR);

        assertEquals(Map.of("deleted", RoleSkills.CALCULATOR), response.data());
        verify(auditService).record(eq("skill.delete"), eq("skill"), eq(RoleSkills.CALCULATOR), any(), any());
    }

    @Test
    @DisplayName("删除未被引用的自定义技能写入审计")
    void deleteWritesAudit() {
        SkillDetail before =
                new SkillDetail(
                        "payroll-auditor", "薪酬审计", "正文", "nacos", false, false, 1L, List.of(),
                        false);
        when(registry.detail("payroll-auditor")).thenReturn(before);
        when(registry.delete("payroll-auditor")).thenReturn(true);

        controller.delete("payroll-auditor");

        verify(auditService)
                .record(
                        "skill.delete",
                        "skill",
                        "payroll-auditor",
                        meta(before),
                        Map.of("deleted", "payroll-auditor"));
    }

    @Test
    @DisplayName("详情直接透传技能注册表")
    void detailReturnsRegistryValue() {
        SkillDetail detail =
                new SkillDetail(
                        "payroll-auditor", "薪酬审计", "正文", "nacos", false, false, 1L, List.of(),
                        false);
        when(registry.detail("payroll-auditor")).thenReturn(detail);

        assertSame(detail, controller.detail("payroll-auditor").data());
    }

    @Test
    @DisplayName("读取单个文件返回路径与正文")
    void fileReturnsContent() {
        when(registry.fileContent("payroll-auditor", "references/policy.md")).thenReturn("政策正文");

        Map<?, ?> data =
                (Map<?, ?>) controller.file("payroll-auditor", "references/policy.md").data();

        assertEquals("payroll-auditor", data.get("id"));
        assertEquals("references/policy.md", data.get("path"));
        assertEquals("政策正文", data.get("content"));
    }

    @Test
    @DisplayName("全量替换文件包写入前后快照审计")
    void replaceFilesWritesAudit() {
        List<SkillFilePackage.Summary> before =
                List.of(new SkillFilePackage.Summary("references/old.md", 3, "reference"));
        List<SkillFilePackage.Summary> after =
                List.of(
                        new SkillFilePackage.Summary("references/new.md", 5, "reference"),
                        new SkillFilePackage.Summary("scripts/run.py", 9, "script"));
        when(registry.files("payroll-auditor")).thenReturn(before);
        when(registry.replaceFiles(eq("payroll-auditor"), any())).thenReturn(after);

        Map<?, ?> data =
                (Map<?, ?>)
                        controller.replaceFiles(
                                        "payroll-auditor",
                                        new SkillAdminController.FilesInput(
                                                List.of(
                                                        new SkillAdminController.FileInput(
                                                                "references/new.md", "hello"))))
                                .data();

        assertEquals(2, data.get("count"));
        verify(auditService)
                .record("skill.files.update", "skill", "payroll-auditor", filesMeta(before), filesMeta(after));
    }

    @Test
    @DisplayName("删除单个文件写入审计")
    void deleteFileWritesAudit() {
        Map<?, ?> data =
                (Map<?, ?>) controller.deleteFile("payroll-auditor", "references/old.md").data();

        assertEquals("references/old.md", data.get("deleted"));
        verify(auditService)
                .record(
                        "skill.files.delete",
                        "skill",
                        "payroll-auditor",
                        Map.of("path", "references/old.md"),
                        Map.of("deleted", "references/old.md"));
    }

    @Test
    @DisplayName("Git 导入写入来源与提交审计")
    void importGitWritesAudit() {
        SkillImportService.ImportResult result =
                new SkillImportService.ImportResult(
                        "poetry",
                        "abc123",
                        "诗词技能",
                        Map.of("version", "1.0.0"),
                        List.of(new SkillFilePackage.Summary("scripts/query.py", 12, "script")),
                        "sh setup.sh",
                        1024L,
                        List.of(),
                        false,
                        false);
        when(importService.importGit(
                        "https://github.com/Wscats/poetry-skills.git", "main", "poetry", true, false))
                .thenReturn(result);

        ApiResponse response =
                controller.importGit(
                        new SkillAdminController.GitImportInput(
                                "https://github.com/Wscats/poetry-skills.git",
                                "main",
                                "poetry",
                                true,
                                false));

        assertSame(result, response.data());
        verify(auditService).record(eq("skill.import"), eq("skill"), eq("poetry"), eq(null), any());
    }

    @Test
    @DisplayName("dryRun 导入只返回预览，不写审计")
    void importDryRunSkipsAudit() {
        SkillImportService.ImportResult preview =
                new SkillImportService.ImportResult(
                        "poetry",
                        null,
                        "诗词技能",
                        Map.of(),
                        List.of(),
                        null,
                        1024L,
                        List.of("activation 首期只展示，不参与技能路由"),
                        false,
                        true);
        when(importService.importGit(
                        "https://github.com/Wscats/poetry-skills.git", null, "poetry", false, true))
                .thenReturn(preview);

        ApiResponse response =
                controller.importGit(
                        new SkillAdminController.GitImportInput(
                                "https://github.com/Wscats/poetry-skills.git",
                                null,
                                "poetry",
                                false,
                                true));

        assertSame(preview, response.data());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("数据状态解析 setup 并透传初始化进度")
    void dataStatusReportsSetupAndState() {
        when(registry.detail("poetry-skill")).thenReturn(scriptedDetail());
        when(gateway.enabled()).thenReturn(true);
        when(gateway.skillData("poetry-skill"))
                .thenReturn(
                        new SandboxGateway.SkillData(
                                SandboxGateway.SkillData.READY,
                                "poetry-skill",
                                0,
                                1_000L,
                                2_000L,
                                4_096L,
                                "done",
                                "初始化完成"));

        Map<?, ?> data = (Map<?, ?>) controller.dataStatus("poetry-skill").data();

        assertEquals(true, data.get("requiresData"));
        assertEquals("setup.sh", data.get("setupPath"));
        assertEquals(SandboxGateway.SkillData.READY, data.get("status"));
        assertEquals(4_096L, data.get("sizeBytes"));
        verify(auditService, never()).record(eq("skill.init.finish"), any(), any(), any(), any());
    }

    @Test
    @DisplayName("未声明 setup 的技能拒绝初始化")
    void initDataRejectsMissingSetup() {
        when(registry.detail("payroll-auditor"))
                .thenReturn(
                        new SkillDetail(
                                "payroll-auditor",
                                "薪酬审计",
                                "正文",
                                "nacos",
                                false,
                                false,
                                1L,
                                List.of(new SkillFilePackage.Summary("scripts/run.py", 3, "script")),
                                true,
                                Map.of()));

        PayrollException error =
                assertThrows(
                        PayrollException.class, () -> controller.initData("payroll-auditor", null));

        assertTrue(error.getMessage().contains("setup"), error.getMessage());
        verify(gateway, never()).initSkill(any());
    }

    @Test
    @DisplayName("数据已就绪时初始化需要显式 confirm")
    void initDataRequiresOverwriteWhenReady() {
        when(registry.detail("poetry-skill")).thenReturn(scriptedDetail());
        when(registry.fileContent("poetry-skill", "setup.sh")).thenReturn("#!/bin/sh");
        when(registry.fileContent("poetry-skill", "scripts/query.py")).thenReturn("print(1)");
        when(gateway.enabled()).thenReturn(true);
        when(gateway.skillData("poetry-skill"))
                .thenReturn(
                        new SandboxGateway.SkillData(
                                SandboxGateway.SkillData.READY, "poetry-skill", 0, 1, 2, 3, "", ""));

        assertThrows(
                PayrollConflictException.class,
                () -> controller.initData("poetry-skill", new SkillAdminController.DataInitInput(false)));

        verify(gateway, never()).initSkill(any());
        verify(gateway, never()).deleteSkillData(any());
    }

    @Test
    @DisplayName("初始化提交整包并按终态补记 skill.init.finish")
    void initDataSubmitsPackageAndRecordsFinish() {
        when(registry.detail("poetry-skill")).thenReturn(scriptedDetail());
        when(registry.fileContent("poetry-skill", "setup.sh")).thenReturn("#!/bin/sh\necho seed");
        when(registry.fileContent("poetry-skill", "scripts/query.py")).thenReturn("print(1)");
        when(gateway.enabled()).thenReturn(true);
        when(gateway.skillData("poetry-skill"))
                .thenReturn(
                        new SandboxGateway.SkillData(
                                SandboxGateway.SkillData.UNKNOWN, "poetry-skill", null, 0, 0, 0, "", ""));
        when(gateway.deleteSkillData("poetry-skill"))
                .thenReturn(
                        new SandboxGateway.SkillData(
                                SandboxGateway.SkillData.UNKNOWN, "poetry-skill", null, 0, 0, 0, "", "工作区已删除"));
        when(gateway.initSkill(any()))
                .thenReturn(
                        new SandboxGateway.SkillData(
                                SandboxGateway.SkillData.INITIALIZING, "poetry-skill", null, 1, 0, 0, "", "已提交"));

        Map<?, ?> data =
                (Map<?, ?>)
                        controller
                                .initData("poetry-skill", new SkillAdminController.DataInitInput(true))
                                .data();

        assertEquals(SandboxGateway.SkillData.INITIALIZING, data.get("status"));
        ArgumentCaptor<SandboxGateway.InitRequest> captured =
                ArgumentCaptor.forClass(SandboxGateway.InitRequest.class);
        verify(gateway).initSkill(captured.capture());
        SandboxGateway.InitRequest request = captured.getValue();
        assertEquals("setup.sh", request.setupPath());
        assertEquals("#!/bin/sh\necho seed", request.setupContent());
        assertEquals(2, request.files().size());
        assertEquals(
                List.of("setup.sh", "scripts/query.py"),
                request.files().stream().map(SandboxGateway.Script::path).toList());
        verify(gateway).deleteSkillData("poetry-skill");
        verify(auditService).record(eq("skill.init.start"), eq("skill"), eq("poetry-skill"), any(), any());

        when(gateway.skillData("poetry-skill"))
                .thenReturn(
                        new SandboxGateway.SkillData(
                                SandboxGateway.SkillData.READY,
                                "poetry-skill",
                                0,
                                1,
                                2,
                                4_096L,
                                "done",
                                "初始化完成"));
        controller.dataStatus("poetry-skill");

        verify(auditService).record(eq("skill.init.finish"), eq("skill"), eq("poetry-skill"), eq(null), any());
    }

    @Test
    @DisplayName("删除技能数据写入审计")
    void deleteDataWritesAudit() {
        when(registry.detail("poetry-skill")).thenReturn(scriptedDetail());
        when(gateway.enabled()).thenReturn(true);
        when(gateway.skillData("poetry-skill"))
                .thenReturn(
                        new SandboxGateway.SkillData(
                                SandboxGateway.SkillData.READY, "poetry-skill", 0, 1, 2, 4_096L, "", ""));
        when(gateway.deleteSkillData("poetry-skill"))
                .thenReturn(
                        new SandboxGateway.SkillData(
                                SandboxGateway.SkillData.UNKNOWN, "poetry-skill", null, 0, 0, 0, "", "工作区已删除"));

        controller.deleteData("poetry-skill");

        verify(auditService)
                .record(eq("skill.data.delete"), eq("skill"), eq("poetry-skill"), any(), any());
    }

    private static SkillDetail scriptedDetail() {
        return new SkillDetail(
                "poetry-skill",
                "诗词技能",
                "正文",
                "nacos",
                false,
                false,
                1L,
                List.of(
                        new SkillFilePackage.Summary("setup.sh", 10, "reference"),
                        new SkillFilePackage.Summary("scripts/query.py", 8, "script"),
                        new SkillFilePackage.Summary("assets/sample.json", 16, "reference"),
                        new SkillFilePackage.Summary("references/quickstart.md", 20, "reference"),
                        new SkillFilePackage.Summary("notes.md", 5, "reference")),
                true,
                Map.of("setup", "bash setup.sh"));
    }

    private static AgentDefinition agent(String name, String skillId) {
        return new AgentDefinition(
                "agent-1",
                name,
                "🤖",
                "",
                List.of(),
                List.of(skillId),
                AgentDefinition.VISIBILITY_PUBLIC,
                AgentDefinition.STATUS_ONLINE,
                "system",
                null,
                null);
    }

    private static SkillSummary summary(String id, String description, String source, boolean builtin) {
        return new SkillSummary(id, description, source, builtin, builtin, 1L, 10, 0);
    }

    private static Map<String, Object> meta(SkillSummary skill) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", skill.id());
        meta.put("description", skill.description());
        meta.put("source", skill.source());
        meta.put("builtin", skill.builtin());
        meta.put("contentLength", skill.contentLength());
        meta.put("fileCount", skill.fileCount());
        return meta;
    }

    private static Map<String, Object> filesMeta(List<SkillFilePackage.Summary> files) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("count", files.size());
        meta.put(
                "files",
                files.stream()
                        .map(
                                file -> {
                                    Map<String, Object> item = new LinkedHashMap<>();
                                    item.put("path", file.path());
                                    item.put("size", file.size());
                                    item.put("kind", file.kind());
                                    return item;
                                })
                        .toList());
        return meta;
    }

    private static Map<String, Object> meta(SkillDetail skill) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", skill.id());
        meta.put("description", skill.description());
        meta.put("source", skill.source());
        meta.put("builtin", skill.builtin());
        meta.put("contentLength", skill.content().length());
        meta.put("executionEnabled", skill.executionEnabled());
        return meta;
    }
}
