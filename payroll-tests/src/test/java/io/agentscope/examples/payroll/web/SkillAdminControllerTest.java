package io.agentscope.examples.payroll.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.SandboxGateway;
import io.agentscope.examples.payroll.skill.NacosAgentRegistry;
import io.agentscope.examples.payroll.skill.RoleSkills;
import io.agentscope.examples.payroll.skill.SkillDetail;
import io.agentscope.examples.payroll.skill.SkillFilePackage;
import io.agentscope.examples.payroll.skill.SkillImportService;
import io.agentscope.examples.payroll.skill.SkillManifests;
import io.agentscope.examples.payroll.skill.SkillStore;
import io.agentscope.examples.payroll.skill.SkillSummary;
import io.agentscope.examples.payroll.skill.SkillVersion;
import io.agentscope.examples.payroll.support.AuditContext;
import io.agentscope.examples.payroll.support.Permissions;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

class SkillAdminControllerTest {

    /** 审计上下文里的登录者；{@code operator()} 取的正是这个 username。 */
    private static final AuthUser HR =
            new AuthUser(11L, "hr01", "人事专员", "HR", "人事", null, null, Set.of());

    private final SkillStore registry = mock(SkillStore.class);
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
                                new SkillSummary("small", "小", "nacos", false, false, 1L, 10, 0, ""),
                                new SkillSummary("large", "大", "nacos", false, false, 2L, 90, 0, "7"),
                                new SkillSummary(
                                        RoleSkills.CALCULATOR, "内置", "builtin", true, false, 3L, 50, 0, "")));
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
    @DisplayName("新增返回摘要，审计快照带写后重读的版本号")
    void createWritesAudit() {
        SkillSummary saved = summary("payroll-auditor", "薪酬审计", "db", false, "1");
        SkillDetail persisted =
                detail("payroll-auditor", "薪酬审计", "正文", List.of(), "1", 1);
        when(registry.create("payroll-auditor", "薪酬审计", "正文", false, "")).thenReturn(saved);
        when(registry.detail("payroll-auditor")).thenReturn(persisted);

        ApiResponse response =
                controller.create(
                        new SkillAdminController.SkillInput(
                                "payroll-auditor", "薪酬审计", "正文", false, null));

        assertSame(saved, response.data());
        Map<String, Object> expected = meta(saved);
        expected.put("versionNo", 1);
        verify(auditService).record("skill.create", "skill", "payroll-auditor", null, expected);
    }

    @Test
    @DisplayName("编辑回传 revision 与变更说明，审计快照带 revision 与 versionNo")
    void updateWritesAudit() {
        SkillDetail before = detail(
                "payroll-auditor", "旧描述", "旧正文", List.of(), "3", 2);
        SkillSummary after = summary("payroll-auditor", "新描述", "db", false, "4");
        when(registry.detail("payroll-auditor")).thenReturn(before);
        when(registry.update(
                        "payroll-auditor", "新描述", "新正文", false, "3", "按 11 月社保基数调整", ""))
                .thenReturn(after);

        ApiResponse response =
                controller.update(
                        "payroll-auditor",
                        new SkillAdminController.SkillInput(
                                null, "新描述", "新正文", null, "按 11 月社保基数调整"),
                        "3");

        assertSame(after, response.data());
        verify(registry)
                .update("payroll-auditor", "新描述", "新正文", false, "3", "按 11 月社保基数调整", "");
        Map<String, Object> expectedAfter = meta(after);
        expectedAfter.put("versionNo", before.versionNo());
        verify(auditService)
                .record("skill.update", "skill", "payroll-auditor", meta(before), expectedAfter);
    }

    @Test
    @DisplayName("编辑可开启脚本执行；未落库的源不写假的 versionNo")
    void updatePersistsExecutionFlag() {
        SkillDetail before =
                new SkillDetail(
                        "payroll-auditor", "旧描述", "旧正文", "nacos", false, false, 1L, List.of(),
                        true, Map.of(), "", 0);
        SkillSummary after = summary("payroll-auditor", "新描述", "nacos", false);
        when(registry.detail("payroll-auditor")).thenReturn(before);
        when(registry.update("payroll-auditor", "新描述", "新正文", true, null, null, ""))
                .thenReturn(after);

        controller.update(
                "payroll-auditor",
                new SkillAdminController.SkillInput(null, "新描述", "新正文", true, null),
                null);

        verify(registry)
                .update("payroll-auditor", "新描述", "新正文", true, null, null, "");
        Map<String, Object> expectedBefore = meta(before);
        assertEquals(true, expectedBefore.get("executionEnabled"));
        verify(auditService)
                .record("skill.update", "skill", "payroll-auditor", expectedBefore, meta(after));
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
                assertThrows(
                        PayrollException.class, () -> controller.delete("payroll-auditor", "3"));

        assertTrue(error.getMessage().contains("个税顾问"), error.getMessage());
        verify(registry, never()).delete(any(), any());
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
        when(registry.delete(RoleSkills.CALCULATOR, null)).thenReturn(true);

        ApiResponse response = controller.delete(RoleSkills.CALCULATOR, null);

        assertEquals(Map.of("deleted", RoleSkills.CALCULATOR), response.data());
        verify(auditService).record(eq("skill.delete"), eq("skill"), eq(RoleSkills.CALCULATOR), any(), any());
    }

    @Test
    @DisplayName("删除未被引用的自定义技能回传 revision 并写审计")
    void deleteWritesAudit() {
        SkillDetail before =
                detail("payroll-auditor", "薪酬审计", "正文", List.of(), "6", 3);
        when(registry.detail("payroll-auditor")).thenReturn(before);
        when(registry.delete("payroll-auditor", "6")).thenReturn(true);

        controller.delete("payroll-auditor", "6");

        verify(registry).delete("payroll-auditor", "6");
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
    @DisplayName("读取单个文件返回路径、正文与 sha256 ETag")
    void fileReturnsContentAndEtag() {
        when(registry.fileContent("payroll-auditor", "references/policy.md")).thenReturn("政策正文");

        ResponseEntity<ApiResponse> response =
                controller.file("payroll-auditor", "/references/policy.md", null);
        Map<?, ?> data = (Map<?, ?>) response.getBody().data();

        assertEquals("payroll-auditor", data.get("id"));
        assertEquals("references/policy.md", data.get("path"));
        assertEquals("政策正文", data.get("content"));
        assertEquals(
                '"' + SkillManifests.contentHash("政策正文") + '"',
                response.getHeaders().getETag());
    }

    @Test
    @DisplayName("If-None-Match 命中时返回 304 且不带正文，弱标记与多值头同样识别")
    void fileHonoursConditionalGet() {
        String content = "政策正文";
        String etag = '"' + SkillManifests.contentHash(content) + '"';
        when(registry.fileContent("payroll-auditor", "references/policy.md")).thenReturn(content);

        ResponseEntity<ApiResponse> matched =
                controller.file("payroll-auditor", "references/policy.md", etag);
        assertEquals(HttpStatus.NOT_MODIFIED, matched.getStatusCode());
        assertEquals(etag, matched.getHeaders().getETag());
        assertNull(matched.getBody());

        assertEquals(
                HttpStatus.NOT_MODIFIED,
                controller
                        .file("payroll-auditor", "references/policy.md", "W/" + etag)
                        .getStatusCode());
        assertEquals(
                HttpStatus.NOT_MODIFIED,
                controller
                        .file("payroll-auditor", "references/policy.md", "\"其它\", " + etag)
                        .getStatusCode());
        assertEquals(
                HttpStatus.NOT_MODIFIED,
                controller.file("payroll-auditor", "references/policy.md", "*").getStatusCode());
        assertEquals(
                HttpStatus.OK,
                controller
                        .file("payroll-auditor", "references/policy.md", "\"stale\"")
                        .getStatusCode());
    }

    @Test
    @DisplayName("版本时间线透传注册表，响应 id 走 SkillIds 校验")
    void versionsReturnsTimeline() {
        when(registry.versions(" payroll-auditor "))
                .thenReturn(
                        List.of(
                                new SkillVersion(3, "改口径", 12, "", 1_700_000_000_000L, true),
                                new SkillVersion(2, "", 10, "", 1_699_000_000_000L, false)));

        Map<?, ?> data = (Map<?, ?>) controller.versions(" payroll-auditor ").data();

        assertEquals("payroll-auditor", data.get("id"));
        assertEquals(2, data.get("total"));
        assertEquals(2, ((List<?>) data.get("versions")).size());
        assertThrows(PayrollException.class, () -> controller.versions("Bad Id"));
    }

    @Test
    @DisplayName("回滚写审计并带 from/to 版本序号与新的行令牌")
    void rollbackWritesAuditWithVersionPair() {
        SkillDetail before = detail("payroll-auditor", "薪酬审计", "旧正文", List.of(), "7", 4);
        SkillDetail after = detail("payroll-auditor", "薪酬审计", "旧正文", List.of(), "8", 5);
        SkillSummary saved = summary("payroll-auditor", "薪酬审计", "db", false, "8");
        when(registry.detail("payroll-auditor")).thenReturn(before, after);
        when(registry.rollback("payroll-auditor", 2, "7", "")).thenReturn(saved);

        ApiResponse response = controller.rollback("payroll-auditor", 2, "7");

        assertSame(saved, response.data());
        verify(registry).rollback("payroll-auditor", 2, "7", "");
        Map<String, Object> from = meta(before);
        from.put("fromVersionNo", 4);
        Map<String, Object> to = meta(saved);
        to.put("versionNo", 5);
        to.put("toVersionNo", 5);
        verify(auditService)
                .record("skill.version.rollback", "skill", "payroll-auditor", from, to);
    }

    @Test
    @DisplayName("回滚把 revision 记进审计上下文，令牌不一致时 409 不落审计")
    void rollbackMarksRevisionAndPropagatesConflict() {
        when(registry.detail(RoleSkills.CALCULATOR))
                .thenReturn(detail(RoleSkills.CALCULATOR, "薪酬审计", "正文", List.of(), "9", 6));
        when(registry.rollback(RoleSkills.CALCULATOR, 1, "6", ""))
                .thenThrow(new PayrollConflictException("配置已被他人修改，请刷新后重试", "9"));

        AuditContext.begin(null, "127.0.0.1", "junit");
        try {
            assertThrows(
                    PayrollConflictException.class,
                    () -> controller.rollback(RoleSkills.CALCULATOR, 1, "6"));
            assertEquals("6", AuditContext.current().revision());
        } finally {
            AuditContext.clear();
        }
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("全量替换文件包回传 revision，前后快照都带版本定位")
    void replaceFilesWritesAudit() {
        List<SkillFilePackage.Summary> before =
                List.of(new SkillFilePackage.Summary("references/old.md", 3, "reference"));
        List<SkillFilePackage.Summary> after =
                List.of(
                        new SkillFilePackage.Summary("references/new.md", 5, "reference"),
                        new SkillFilePackage.Summary("scripts/run.py", 9, "script"));
        SkillDetail oldSnapshot =
                detail("payroll-auditor", "薪酬审计", "正文", before, "11", 2);
        SkillDetail newSnapshot = detail("payroll-auditor", "薪酬审计", "正文", after, "12", 2);
        when(registry.detail("payroll-auditor")).thenReturn(oldSnapshot, newSnapshot);
        when(registry.replaceFiles(eq("payroll-auditor"), any(), eq("11"), eq("")))
                .thenReturn(after);

        Map<?, ?> data =
                (Map<?, ?>)
                        controller.replaceFiles(
                                        "payroll-auditor",
                                        new SkillAdminController.FilesInput(
                                                List.of(
                                                        new SkillAdminController.FileInput(
                                                                "references/new.md", "hello"))),
                                        "11")
                                .data();

        assertEquals(2, data.get("count"));
        assertEquals("12", data.get("revision"));
        assertEquals(2, data.get("versionNo"));
        verify(registry).replaceFiles(eq("payroll-auditor"), any(), eq("11"), eq(""));
        verify(auditService)
                .record(
                        "skill.files.update",
                        "skill",
                        "payroll-auditor",
                        withVersion(filesMeta(before), oldSnapshot),
                        withVersion(filesMeta(after), newSnapshot));
    }

    @Test
    @DisplayName("删除单个文件回传 revision，响应回填新的行令牌")
    void deleteFileWritesAudit() {
        SkillDetail snapshot = detail("payroll-auditor", "薪酬审计", "正文", List.of(), "8", 5);
        when(registry.detail("payroll-auditor")).thenReturn(snapshot);

        Map<?, ?> data =
                (Map<?, ?>)
                        controller.deleteFile("payroll-auditor", "references/old.md", "8").data();

        assertEquals("references/old.md", data.get("deleted"));
        assertEquals("8", data.get("revision"));
        assertEquals(5, data.get("versionNo"));
        verify(registry).deleteFile("payroll-auditor", "references/old.md", "8", "");
        verify(auditService)
                .record(
                        "skill.files.delete",
                        "skill",
                        "payroll-auditor",
                        withVersion(Map.of("path", "references/old.md"), snapshot),
                        withVersion(Map.of("deleted", "references/old.md"), snapshot));
    }

    @Test
    @DisplayName("令牌不一致时 409 直接抛出，不写成功审计")
    void staleRevisionPropagatesWithoutAudit() {
        when(registry.detail("payroll-auditor"))
                .thenReturn(detail("payroll-auditor", "旧描述", "旧正文", List.of(), "5", 3));
        when(registry.update("payroll-auditor", "新描述", "新正文", false, "3", null, ""))
                .thenThrow(new PayrollConflictException("配置已被他人修改，请刷新后重试", "5"));

        PayrollConflictException error =
                assertThrows(
                        PayrollConflictException.class,
                        () ->
                                controller.update(
                                        "payroll-auditor",
                                        new SkillAdminController.SkillInput(
                                                null, "新描述", "新正文", null, null),
                                        "3"));

        assertEquals("5", error.currentRevision());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("写接口把回传的 revision 记进审计上下文")
    void writeEndpointsMarkRevisionOnAuditContext() {
        when(registry.detail(RoleSkills.CALCULATOR))
                .thenReturn(detail(RoleSkills.CALCULATOR, "薪酬审计", "正文", List.of(), "7", 2));
        when(registry.delete(RoleSkills.CALCULATOR, "7")).thenReturn(true);

        AuditContext.begin(null, "127.0.0.1", "junit");
        try {
            controller.delete(RoleSkills.CALCULATOR, "7");
            assertEquals("7", AuditContext.current().revision());
        } finally {
            AuditContext.clear();
        }
    }

    @Test
    @DisplayName("操作者取自审计上下文，与 audit_log.actor_name 同一个来源")
    void writeEndpointsThreadOperatorFromAuditContext() {
        when(registry.detail(RoleSkills.CALCULATOR))
                .thenReturn(detail(RoleSkills.CALCULATOR, "薪酬审计", "正文", List.of(), "7", 2));
        when(registry.update(
                        RoleSkills.CALCULATOR, "新描述", "新正文", false, "7", "改一版", "hr01"))
                .thenReturn(summary(RoleSkills.CALCULATOR, "新描述", "db", false, "8"));
        when(registry.replaceFiles(
                        eq(RoleSkills.CALCULATOR), any(), eq("7"), eq("hr01")))
                .thenReturn(List.of());

        AuditContext.begin(HR, "127.0.0.1", "junit");
        try {
            controller.update(
                    RoleSkills.CALCULATOR,
                    new SkillAdminController.SkillInput(null, "新描述", "新正文", null, "改一版"),
                    "7");
            controller.replaceFiles(
                    RoleSkills.CALCULATOR,
                    new SkillAdminController.FilesInput(List.of()),
                    "7");
        } finally {
            AuditContext.clear();
        }

        verify(registry)
                .update(RoleSkills.CALCULATOR, "新描述", "新正文", false, "7", "改一版", "hr01");
        verify(registry).replaceFiles(eq(RoleSkills.CALCULATOR), any(), eq("7"), eq("hr01"));
    }

    @Test
    @DisplayName("真导入把 revision 与操作者一起透传，并记进审计上下文")
    void realImportThreadsRevisionAndMarksIt() {
        SkillImportService.ImportResult result =
                new SkillImportService.ImportResult(
                        "poetry", "abc123", "诗词技能", Map.of(), List.of(), null, 1024L, List.of(),
                        true, false);
        when(importService.importGit(
                        "https://github.com/Wscats/poetry-skills.git",
                        null,
                        "poetry",
                        true,
                        false,
                        "9",
                        "hr01"))
                .thenReturn(result);

        AuditContext.begin(HR, "127.0.0.1", "junit");
        try {
            controller.importGit(
                    new SkillAdminController.GitImportInput(
                            "https://github.com/Wscats/poetry-skills.git", null, "poetry", true,
                            false),
                    "9");
            assertEquals("9", AuditContext.current().revision());
        } finally {
            AuditContext.clear();
        }

        verify(importService)
                .importGit(
                        "https://github.com/Wscats/poetry-skills.git",
                        null,
                        "poetry",
                        true,
                        false,
                        "9",
                        "hr01");
    }

    @Test
    @DisplayName("dryRun 导入不占令牌位：审计上下文不留 revision，预览不会被记成成功的条件写入")
    void dryRunImportLeavesRevisionUnmarked() {
        SkillImportService.ImportResult preview =
                new SkillImportService.ImportResult(
                        "poetry", null, "诗词技能", Map.of(), List.of(), null, 1024L, List.of(),
                        true, true);
        when(importService.importZip(any(), eq("poetry"), eq(true), eq(true), eq("9"), eq("hr01")))
                .thenReturn(preview);

        AuditContext.begin(HR, "127.0.0.1", "junit");
        try {
            controller.importZip(
                    new MockMultipartFile("file", "poetry.zip", "", new byte[] {1, 2, 3}),
                    "poetry",
                    true,
                    true,
                    "9");
            assertNull(AuditContext.current().revision());
        } finally {
            AuditContext.clear();
        }

        verify(importService)
                .importZip(any(), eq("poetry"), eq(true), eq(true), eq("9"), eq("hr01"));
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("审计快照回读失败不会把已成功的写变成异常")
    void auditSnapshotFailureKeepsWrite() {
        SkillDetail before =
                detail("payroll-auditor", "旧描述", "旧正文", List.of(), "3", 2);
        SkillSummary saved = summary("payroll-auditor", "新描述", "db", false, "4");
        when(registry.detail("payroll-auditor"))
                .thenReturn(before)
                .thenThrow(new PayrollException("数据库不可用"));
        when(registry.update("payroll-auditor", "新描述", "新正文", false, "3", null, ""))
                .thenReturn(saved);

        ApiResponse response =
                controller.update(
                        "payroll-auditor",
                        new SkillAdminController.SkillInput(null, "新描述", "新正文", null, null),
                        "3");

        assertSame(saved, response.data());
        verify(auditService)
                .record("skill.update", "skill", "payroll-auditor", meta(before), meta(saved));
    }

    @Test
    @DisplayName("初始化与数据删除的审计带 versionNo")
    void dataAuditsCarryVersion() {
        when(registry.detail("poetry-skill")).thenReturn(scriptedDetail());
        when(registry.fileContent("poetry-skill", "setup.sh")).thenReturn("#!/bin/sh");
        when(registry.fileContent("poetry-skill", "scripts/query.py")).thenReturn("print(1)");
        when(gateway.enabled()).thenReturn(true);
        when(gateway.skillData("poetry-skill"))
                .thenReturn(
                        new SandboxGateway.SkillData(
                                SandboxGateway.SkillData.UNKNOWN, "poetry-skill", null, 0, 0, 0, "", ""));
        when(gateway.deleteSkillData("poetry-skill"))
                .thenReturn(
                        new SandboxGateway.SkillData(
                                SandboxGateway.SkillData.UNKNOWN, "poetry-skill", null, 0, 0, 0, "", ""));
        when(gateway.initSkill(any()))
                .thenReturn(
                        new SandboxGateway.SkillData(
                                SandboxGateway.SkillData.INITIALIZING,
                                "poetry-skill",
                                null,
                                1,
                                0,
                                0,
                                "",
                                "已提交"));

        controller.initData("poetry-skill", new SkillAdminController.DataInitInput(false));
        controller.deleteData("poetry-skill");

        ArgumentCaptor<Object> init = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> removed = ArgumentCaptor.forClass(Object.class);
        verify(auditService).record(eq("skill.init.start"), any(), any(), any(), init.capture());
        verify(auditService).record(eq("skill.data.delete"), any(), any(), any(), removed.capture());
        for (Object snapshot : List.of(init.getValue(), removed.getValue())) {
            Map<?, ?> meta = (Map<?, ?>) snapshot;
            assertEquals(4, meta.get("versionNo"), String.valueOf(meta));
            assertEquals("9", meta.get("revision"), String.valueOf(meta));
        }
    }

    @Test
    @DisplayName("所有写接口都要求 skill:manage")
    void writeEndpointsRequireSkillManage() {
        List<String> missing = new ArrayList<>();
        for (Method method : SkillAdminController.class.getDeclaredMethods()) {
            if (!isWriteMapping(method)) {
                continue;
            }
            RequirePermission required = method.getAnnotation(RequirePermission.class);
            if (required == null
                    || Arrays.stream(required.value()).noneMatch(Permissions.SKILL_MANAGE::equals)) {
                missing.add(method.getName());
            }
        }
        assertEquals(List.of(), missing, "写接口缺少 skill:manage：" + missing);
    }

    private static boolean isWriteMapping(Method method) {
        return method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class);
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
                        "https://github.com/Wscats/poetry-skills.git",
                        "main",
                        "poetry",
                        true,
                        false,
                        null,
                        ""))
                .thenReturn(result);

        ApiResponse response =
                controller.importGit(
                        new SkillAdminController.GitImportInput(
                                "https://github.com/Wscats/poetry-skills.git",
                                "main",
                                "poetry",
                                true,
                                false),
                        null);

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
                        "https://github.com/Wscats/poetry-skills.git",
                        null,
                        "poetry",
                        false,
                        true,
                        null,
                        ""))
                .thenReturn(preview);

        ApiResponse response =
                controller.importGit(
                        new SkillAdminController.GitImportInput(
                                "https://github.com/Wscats/poetry-skills.git",
                                null,
                                "poetry",
                                false,
                                true),
                        null);

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
                                Map.of(),
                                "",
                                0));

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

    /** 已落库、带版本行的技能详情：管理台初始化与数据删除的审计要带 version_no。 */
    private static SkillDetail scriptedDetail() {
        return new SkillDetail(
                "poetry-skill",
                "诗词技能",
                "正文",
                "db",
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
                Map.of("setup", "bash setup.sh"),
                "9",
                4);
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
        return summary(id, description, source, builtin, "");
    }

    private static SkillSummary summary(
            String id, String description, String source, boolean builtin, String revision) {
        return new SkillSummary(id, description, source, builtin, builtin, 1L, 10, 0, revision);
    }

    private static SkillDetail detail(
            String id,
            String description,
            String content,
            List<SkillFilePackage.Summary> files,
            String revision,
            int versionNo) {
        return new SkillDetail(
                id,
                description,
                content,
                "db",
                false,
                false,
                1L,
                files,
                false,
                Map.of(),
                revision,
                versionNo);
    }

    private static Map<String, Object> meta(SkillSummary skill) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", skill.id());
        meta.put("description", skill.description());
        meta.put("source", skill.source());
        meta.put("builtin", skill.builtin());
        meta.put("contentLength", skill.contentLength());
        meta.put("fileCount", skill.fileCount());
        if (!skill.revision().isBlank()) {
            meta.put("revision", skill.revision());
        }
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

    /** 控制器的审计/响应快照会补版本定位键（§13-6），断言用同一规则构造期望值。 */
    private static Map<String, Object> withVersion(
            Map<String, Object> base, SkillDetail snapshot) {
        Map<String, Object> meta = new LinkedHashMap<>(base);
        if (snapshot == null) {
            return meta;
        }
        if (snapshot.revision() != null && !snapshot.revision().isBlank()) {
            meta.putIfAbsent("revision", snapshot.revision());
        }
        if (snapshot.versionNo() > 0) {
            meta.putIfAbsent("versionNo", snapshot.versionNo());
        }
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
        if (skill.revision() != null && !skill.revision().isBlank()) {
            meta.put("revision", skill.revision());
        }
        if (skill.versionNo() > 0) {
            meta.put("versionNo", skill.versionNo());
        }
        return meta;
    }
}
