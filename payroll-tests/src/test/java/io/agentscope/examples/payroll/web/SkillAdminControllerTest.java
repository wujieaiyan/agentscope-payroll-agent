package io.agentscope.examples.payroll.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.examples.payroll.db.H2AdminRepository;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.AppUser;
import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollNotFoundException;
import io.agentscope.examples.payroll.domain.SandboxUnavailableException;
import io.agentscope.examples.payroll.sandbox.SkillDataProtocol;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.SandboxGateway;
import io.agentscope.examples.payroll.skill.NacosAgentRegistry;
import io.agentscope.examples.payroll.skill.RoleSkills;
import io.agentscope.examples.payroll.skill.SkillCreationOriginStore;
import io.agentscope.examples.payroll.skill.SkillDataState;
import io.agentscope.examples.payroll.skill.SkillDataStateStore;
import io.agentscope.examples.payroll.skill.SkillDetail;
import io.agentscope.examples.payroll.skill.SkillFilePackage;
import io.agentscope.examples.payroll.skill.SkillImportService;
import io.agentscope.examples.payroll.skill.SkillManifests;
import io.agentscope.examples.payroll.skill.SkillProjectParser;
import io.agentscope.examples.payroll.skill.SkillStore;
import io.agentscope.examples.payroll.skill.SkillSummary;
import io.agentscope.examples.payroll.skill.SkillTemplates;
import io.agentscope.examples.payroll.skill.SkillVersion;
import io.agentscope.examples.payroll.skill.SkillVersionContent;
import io.agentscope.examples.payroll.support.AuditContext;
import io.agentscope.examples.payroll.support.Permissions;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
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
        when(registry.summaries())
                .thenReturn(
                        List.of(
                                summary(RoleSkills.CALCULATOR, "计算", "builtin", true),
                                summary("payroll-auditor", "薪酬审计", "nacos", false)));
        when(registry.writable()).thenReturn(true);
        when(registry.location()).thenReturn("http://nacos/payroll-agent");
        when(agentRegistry.list()).thenReturn(List.of());

        Map<?, ?> all = (Map<?, ?>) controller.list(null, null, null, null, null, null).data();
        assertEquals(true, all.get("writable"));
        assertEquals("http://nacos/payroll-agent", all.get("location"));
        assertEquals(2, all.get("total"));
        assertEquals(1, all.get("page"));
        assertEquals(SkillAdminController.DEFAULT_PAGE_SIZE, all.get("size"));
        assertEquals(2, ((List<?>) all.get("skills")).size());

        Map<?, ?> filtered = (Map<?, ?>) controller.list("audit", null, null, null, null, null).data();
        assertEquals(1, ((List<?>) filtered.get("skills")).size());
    }

    @Test
    @DisplayName("列表视图筛选区分自定义、内置、覆盖与绑定，并拒绝未知视图")
    void listFiltersByView() {
        when(registry.summaries())
                .thenReturn(
                        List.of(
                                summary(RoleSkills.CALCULATOR, "计算", "builtin", true),
                                summary("payroll-auditor", "薪酬审计", "db", false),
                                summary("poetry-skills", "诗词", "db", false)));
        when(agentRegistry.list()).thenReturn(List.of(agent("审计智能体", "payroll-auditor")));

        assertEquals(
                2,
                skillsOf(controller.list(null, null, null, null, "custom", null)).size());
        assertEquals(
                1,
                skillsOf(controller.list(null, null, null, null, "builtin", null)).size());
        assertEquals(
                1,
                skillsOf(controller.list(null, null, null, null, "overridden", null)).size());
        assertEquals(
                1,
                skillsOf(controller.list(null, null, null, null, "bound", null)).size());

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> controller.list(null, null, null, null, "unknown-view", null));
        assertTrue(error.getMessage().contains("unknown-view"), error.getMessage());
    }

    @Test
    @DisplayName("列表分页按 size 截断并返回规模指标")
    void listPaginatesAndReportsScale() {
        when(registry.summaries())
                .thenReturn(
                        List.of(
                                summary("skill-a", "甲", "nacos", false),
                                summary("skill-b", "乙", "nacos", false),
                                summary("skill-c", "丙", "builtin", true)));
        when(agentRegistry.list())
                .thenReturn(List.of(agent("甲智能体", "skill-a"), agent("乙智能体", "skill-b")));

        Map<?, ?> second = (Map<?, ?>) controller.list(null, 2, 2, null, null, null).data();

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
        when(registry.summaries())
                .thenReturn(
                        List.of(
                                new SkillSummary("small", "小", "nacos", false, false, 1L, 10, 0, ""),
                                new SkillSummary("large", "大", "nacos", false, false, 2L, 90, 0, "7"),
                                new SkillSummary(
                                        RoleSkills.CALCULATOR, "内置", "builtin", true, false, 3L, 50, 0, "")));
        when(agentRegistry.list()).thenReturn(List.of());

        List<?> nacos =
                (List<?>)
                        ((Map<?, ?>) controller.list(null, null, null, "nacos", null, "size").data())
                                .get("skills");

        assertEquals(2, nacos.size());
        assertEquals("large", ((SkillSummary) nacos.get(0)).id());
        assertEquals("small", ((SkillSummary) nacos.get(1)).id());
    }

    @Test
    @DisplayName("不支持的排序字段返回明确错误")
    void listRejectsUnknownSort() {
        when(registry.summaries()).thenReturn(List.of());

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> controller.list(null, null, null, null, null, "unknown"));

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

        Map<?, ?> data = (Map<?, ?>) response.data();
        assertEquals("poetry", data.get("id"));
        assertEquals("abc123", data.get("commitSha"));
        assertEquals(false, data.get("dryRun"));
        Map<?, ?> source = (Map<?, ?>) data.get("source");
        assertEquals("git", source.get("kind"));
        assertEquals("main", source.get("ref"));
        assertEquals("abc123", source.get("resolvedSha"));
        assertEquals(1, ((Map<?, ?>) data.get("totals")).get("files"));
        assertEquals(
                "ADDED",
                ((Map<?, ?>) ((List<?>) data.get("files")).get(0)).get("status"));
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

        Map<?, ?> data = (Map<?, ?>) response.data();
        assertEquals(true, data.get("dryRun"));
        assertEquals(List.of("activation 首期只展示，不参与技能路由"), data.get("warnings"));
        assertEquals(0, ((Map<?, ?>) data.get("totals")).get("files"));
        assertEquals(null, data.get("currentVersionNo"));
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("列表批量装配绑定、数据状态与显示名，无需数据的技能不出现")
    void listAssemblesBindingsDataStatesAndUsers() {
        SkillDataStateStore states = mock(SkillDataStateStore.class);
        H2AdminRepository users = mock(H2AdminRepository.class);
        SkillAdminController rich =
                richController(states, SkillCreationOriginStore.none(), users);
        when(registry.summaries())
                .thenReturn(
                        List.of(
                                new SkillSummary(
                                        "payroll-auditor", "薪酬审计", "db", false, false, 5L,
                                        10, 0, "3", 2, "hr01", true, 2048L),
                                new SkillSummary(
                                        RoleSkills.CALCULATOR, "计算", "builtin", true, true, 6L,
                                        20, 0, "", null, null, false, null)));
        when(states.findAll())
                .thenReturn(
                        Map.of(
                                "payroll-auditor",
                                new SkillDataState(
                                        "payroll-auditor", SkillDataState.READY, 512L, 0,
                                        10L, 20L, "done", 30L)));
        when(agentRegistry.list())
                .thenReturn(
                        List.of(
                                agent("个税顾问", "payroll-auditor"),
                                agent("薪酬计算专家", RoleSkills.CALCULATOR)));
        when(users.users())
                .thenReturn(
                        List.of(
                                new AppUser(11L, "hr01", "人事专员", "HR", "人事", null, null, true, null, null, null),
                                new AppUser(12L, "other", "其他人", "HR", "人事", null, null, true, null, null, null)));
        when(gateway.enabled()).thenReturn(true);

        Map<?, ?> data = (Map<?, ?>) rich.list(null, null, null, null, null, null, null).data();

        Map<?, ?> bindings = (Map<?, ?>) data.get("bindings");
        List<?> auditorBindings = (List<?>) bindings.get("payroll-auditor");
        assertEquals(1, auditorBindings.size());
        assertEquals("个税顾问", ((Map<?, ?>) auditorBindings.get(0)).get("name"));

        Map<?, ?> dataStates = (Map<?, ?>) data.get("dataStates");
        Map<?, ?> auditorState = (Map<?, ?>) dataStates.get("payroll-auditor");
        assertEquals(SkillDataState.READY, auditorState.get("status"));
        assertEquals(512L, auditorState.get("sizeBytes"));
        assertEquals(2048L, auditorState.get("declaredMaxBytes"));
        assertNull(dataStates.get(RoleSkills.CALCULATOR), "无需数据的技能不应出现在数据状态里");

        assertEquals(Map.of("hr01", "人事专员"), data.get("users"));
    }

    @Test
    @DisplayName("数据状态过滤与规模指标共用一个分桶，沙箱关闭时全部降级为未知")
    void listFiltersByDataStatusAndCountsBuckets() {
        SkillDataStateStore states = mock(SkillDataStateStore.class);
        SkillAdminController rich =
                richController(states, SkillCreationOriginStore.none(), null);
        when(registry.summaries())
                .thenReturn(
                        List.of(
                                new SkillSummary(
                                        "ready-skill", "就绪", "db", false, false, 1L, 10, 0, "",
                                        1, "hr01", true, 1024L),
                                new SkillSummary(
                                        "failed-skill", "失败", "db", false, false, 1L, 10, 0, "",
                                        1, "hr01", true, 1024L),
                                new SkillSummary(
                                        "none-skill", "无需数据", "db", false, false, 1L, 10, 0,
                                        "", 1, "hr01", false, null),
                                new SkillSummary(
                                        "unknown-skill", "来源未知", "nacos", false, false, 1L,
                                        10, 0, "", null, null, null, null)));
        when(states.findAll())
                .thenReturn(
                        Map.of(
                                "ready-skill",
                                new SkillDataState(
                                        "ready-skill", SkillDataState.READY, 1L, 0, 0, 0, "", 0),
                                "failed-skill",
                                new SkillDataState(
                                        "failed-skill", SkillDataState.FAILED, 0L, 1, 0, 0, "boom", 0)));
        when(agentRegistry.list()).thenReturn(List.of());
        when(gateway.enabled()).thenReturn(true);

        List<?> attention =
                (List<?>)
                        ((Map<?, ?>) rich.list(null, null, null, null, null, "attention", null).data())
                                .get("skills");
        assertEquals(1, attention.size());
        assertEquals("failed-skill", ((SkillSummary) attention.get(0)).id());

        Map<?, ?> stats =
                (Map<?, ?>) ((Map<?, ?>) rich.list(null, null, null, null, null, null, null).data())
                        .get("stats");
        assertEquals(2L, stats.get("usable"));
        assertEquals(1L, stats.get("needsAttention"));
        assertEquals(1L, stats.get("unknownData"));
        assertEquals(0L, stats.get("initializing"));
        assertEquals(1L, ((Map<?, ?>) stats.get("views")).get("attention"));

        // 沙箱关闭时无法判定数据需求：有 setup 的技能降级为未知，「无需数据」仍算可用。
        when(gateway.enabled()).thenReturn(false);
        List<?> readyOnly =
                (List<?>)
                        ((Map<?, ?>) rich.list(null, null, null, null, null, "ready", null).data())
                                .get("skills");
        assertEquals(1, readyOnly.size());
        assertEquals("none-skill", ((SkillSummary) readyOnly.get(0)).id());
        Map<?, ?> offline =
                (Map<?, ?>) ((Map<?, ?>) rich.list(null, null, null, null, null, null, null).data())
                        .get("stats");
        assertEquals(1L, offline.get("usable"));
        assertEquals(3L, offline.get("unknownData"));

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> rich.list(null, null, null, null, null, "broken", null));
        assertTrue(error.getMessage().contains("broken"), error.getMessage());
    }

    @Test
    @DisplayName("概览一次返回身份、健康、清单与时间线，并解析显示名")
    void overviewAssemblesIdentityHealthChecklistAndTimeline() {
        SkillDataStateStore states = mock(SkillDataStateStore.class);
        H2AdminRepository users = mock(H2AdminRepository.class);
        SkillAdminController rich =
                richController(states, SkillCreationOriginStore.none(), users);
        String content =
                "## 角色\n"
                        + "你是薪酬审计助手，负责核对工资明细、考勤数据与城市政策口径，发现异常时给出可复核的结论。\n"
                        + "## 规则\n"
                        + "先核对员工档案与考勤记录，再按参保城市的政策复核个税、社保与公积金，最后逐项输出差异金额与依据。\n"
                        + "## 步骤\n"
                        + "一、读取批次明细；二、复核政策版本；三、标记异常；四、输出复核建议与需要补充的材料清单。\n"
                        + "## 输出要求\n"
                        + "用表格列出异常项、差异金额、依据条款与修订建议，不要跳步，也不要在缺少依据时自行补全数字。";
        SkillDetail detail =
                new SkillDetail(
                        "payroll-auditor", "薪酬审计", content, "db", false, false,
                        1_600_000_000_000L, 1_700_000_000_000L,
                        List.of(
                                new SkillFilePackage.Summary(
                                        "scripts/run.py", 9, SkillFilePackage.KIND_SCRIPT),
                                new SkillFilePackage.Summary("setup.sh", 10, "reference")),
                        true, Map.of("setup", "bash setup.sh"), "5", 4, "hr01", "hr02", true);
        when(registry.detail("payroll-auditor")).thenReturn(detail);
        when(registry.writable()).thenReturn(true);
        when(states.find("payroll-auditor"))
                .thenReturn(
                        Optional.of(
                                new SkillDataState(
                                        "payroll-auditor", SkillDataState.READY, 4096L, 0,
                                        10L, 20L, "done", 30L)));
        when(agentRegistry.list()).thenReturn(List.of(agent("个税顾问", "payroll-auditor")));
        when(registry.versions("payroll-auditor"))
                .thenReturn(
                        List.of(
                                new SkillVersion(
                                        4, "改口径", content.length(), "hr01",
                                        1_700_000_000_000L, true),
                                new SkillVersion(
                                        3, "", 100, "hr02", 1_690_000_000_000L, false)));
        when(users.users())
                .thenReturn(
                        List.of(
                                new AppUser(11L, "hr01", "人事专员", "HR", "人事", null, null, true, null, null, null),
                                new AppUser(12L, "hr02", "薪酬主管", "HR", "人事", null, null, true, null, null, null)));

        Map<?, ?> data = (Map<?, ?>) rich.overview("payroll-auditor").data();

        Map<?, ?> identity = (Map<?, ?>) data.get("identity");
        assertEquals(SkillManifests.contentHash(content), identity.get("contentHash"));
        assertEquals(4, identity.get("versionNo"));
        assertEquals("hr01", identity.get("createdBy"));
        assertEquals(true, identity.get("writable"));

        Map<?, ?> health = (Map<?, ?>) data.get("health");
        Map<?, ?> dataHealth = (Map<?, ?>) health.get("data");
        assertEquals(SkillDataState.READY, dataHealth.get("status"));
        assertEquals(4096L, dataHealth.get("sizeBytes"));
        assertEquals(SkillProjectParser.DEFAULT_DATA_MAX_BYTES, dataHealth.get("declaredMaxBytes"));
        Map<?, ?> scripts = (Map<?, ?>) health.get("scripts");
        assertEquals(1L, scripts.get("count"));
        assertEquals("setup.sh", scripts.get("setupCommand"));
        assertEquals(1, ((Map<?, ?>) health.get("bindings")).get("count"));

        List<?> checklist = (List<?>) data.get("checklist");
        assertEquals(4, checklist.size());
        assertEquals(
                List.of("body", "files", "data", "binding"),
                checklist.stream().map(item -> ((Map<?, ?>) item).get("key")).toList());
        assertTrue(
                checklist.stream()
                        .allMatch(item -> Boolean.TRUE.equals(((Map<?, ?>) item).get("ok"))),
                String.valueOf(checklist));

        List<?> timeline = (List<?>) data.get("timeline");
        assertEquals(2, timeline.size());
        assertEquals(true, ((Map<?, ?>) timeline.get(0)).get("current"));

        assertEquals(Map.of("hr01", "人事专员", "hr02", "薪酬主管"), data.get("users"));
        assertNull(data.get("warnings"));
    }

    @Test
    @DisplayName("版本时间线读取失败只降级为警告，概览照常返回")
    void overviewDegradesOnVersionFailure() {
        when(registry.detail("payroll-auditor"))
                .thenReturn(detail("payroll-auditor", "薪酬审计", "正文", List.of(), "5", 4));
        when(registry.versions("payroll-auditor")).thenThrow(new PayrollException("数据库不可用"));
        when(agentRegistry.list()).thenReturn(List.of());

        Map<?, ?> data = (Map<?, ?>) controller.overview("payroll-auditor").data();

        assertEquals(List.of("版本时间线读取失败"), data.get("warnings"));
        assertEquals(List.of(), data.get("timeline"));
        assertEquals("payroll-auditor", data.get("id"));
    }

    @Test
    @DisplayName("概览把技能的 404 原样抛出，不吞成空概览")
    void overviewPropagatesNotFound() {
        when(registry.detail("missing"))
                .thenThrow(new PayrollNotFoundException("技能不存在：missing"));

        assertThrows(PayrollNotFoundException.class, () -> controller.overview("missing"));
    }

    @Test
    @DisplayName("模板列表统计使用次数与最近使用，仅取当前操作者的三条")
    void templatesReportUsageAndRecent() {
        SkillCreationOriginStore origins = mock(SkillCreationOriginStore.class);
        SkillAdminController rich =
                richController(SkillDataStateStore.none(), origins, null);
        when(origins.origins())
                .thenReturn(
                        Map.of(
                                "s1", new SkillCreationOriginStore.Origin("policy-note", "hr01", 1_000L),
                                "s2", new SkillCreationOriginStore.Origin("policy-note", "hr01", 3_000L),
                                "s3", new SkillCreationOriginStore.Origin("kb-qa-assistant", "hr01", 2_000L),
                                "s4", new SkillCreationOriginStore.Origin("kb-qa-assistant", "hr02", 4_000L),
                                "s5", new SkillCreationOriginStore.Origin("", "hr01", 5_000L)));

        AuditContext.begin(HR, "127.0.0.1", "junit");
        try {
            Map<?, ?> data = (Map<?, ?>) rich.templates().data();
            List<?> list = (List<?>) data.get("templates");
            assertEquals(4, list.size());
            Map<?, ?> policy =
                    list.stream()
                            .map(item -> (Map<?, ?>) item)
                            .filter(item -> "policy-note".equals(item.get("id")))
                            .findFirst()
                            .orElseThrow();
            assertEquals("政策口径说明", policy.get("title"));
            assertEquals(2, policy.get("usedCount"));
            assertEquals(3_000L, policy.get("lastUsedAt"));

            List<?> recent = (List<?>) data.get("recent");
            assertEquals(
                    List.of("policy-note", "kb-qa-assistant"),
                    recent.stream().map(item -> ((Map<?, ?>) item).get("id")).toList());
        } finally {
            AuditContext.clear();
        }
    }

    @Test
    @DisplayName("从模板创建记录来源并在审计里带 templateId，保留字与未知模板被拒绝")
    void createFromTemplateRecordsOriginAndAudit() {
        SkillCreationOriginStore origins = mock(SkillCreationOriginStore.class);
        SkillAdminController rich =
                richController(SkillDataStateStore.none(), origins, null);
        SkillTemplates.Template template = new SkillTemplates().require("policy-note");
        SkillSummary saved =
                summary("policy-check", template.description(), "db", false, "1");
        when(registry.create(
                        "policy-check", template.description(), template.body(), false, "hr01"))
                .thenReturn(saved);
        when(registry.detail("policy-check"))
                .thenReturn(
                        detail(
                                "policy-check",
                                template.description(),
                                template.body(),
                                List.of(),
                                "1",
                                1));

        AuditContext.begin(HR, "127.0.0.1", "junit");
        try {
            ApiResponse response =
                    rich.createFromTemplate(
                            new SkillAdminController.FromTemplateInput(
                                    "policy-note", " policy-check ", null, null));
            assertSame(saved, response.data());
            verify(origins).record("policy-check", "policy-note", "hr01");

            ArgumentCaptor<Object> captured = ArgumentCaptor.forClass(Object.class);
            verify(auditService)
                    .record(
                            eq("skill.create"),
                            eq("skill"),
                            eq("policy-check"),
                            eq(null),
                            captured.capture());
            Map<?, ?> after = (Map<?, ?>) captured.getValue();
            assertEquals("policy-note", after.get("templateId"));
            assertEquals(1, after.get("versionNo"));

            assertThrows(
                    PayrollException.class,
                    () ->
                            rich.createFromTemplate(
                                    new SkillAdminController.FromTemplateInput(
                                            "policy-note", "new", null, null)));
            assertThrows(
                    PayrollException.class,
                    () ->
                            rich.createFromTemplate(
                                    new SkillAdminController.FromTemplateInput(
                                            "unknown-template", "fresh-skill", null, null)));
        } finally {
            AuditContext.clear();
        }

        // 保留字与未知模板都停在参数校验：全程只有成功那一次 create。
        verify(registry, times(1)).create(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("单版本正文透传正文、变更说明、文件清单与当前标记")
    void versionContentPassesThrough() {
        when(registry.versionContent(" payroll-auditor ", 2))
                .thenReturn(
                        new SkillVersionContent(
                                2,
                                "旧正文",
                                "改口径",
                                "hr01",
                                1_700_000_000_000L,
                                3,
                                false,
                                List.of(
                                        new SkillFilePackage.Summary(
                                                "scripts/run.py", 9, SkillFilePackage.KIND_SCRIPT))));

        Map<?, ?> data =
                (Map<?, ?>) controller.versionContent(" payroll-auditor ", 2).data();

        assertEquals("payroll-auditor", data.get("id"));
        assertEquals(2, data.get("versionNo"));
        assertEquals("旧正文", data.get("content"));
        assertEquals("改口径", data.get("changeNote"));
        assertEquals("hr01", data.get("createdBy"));
        assertEquals(1_700_000_000_000L, data.get("createdAt"));
        assertEquals(3, data.get("contentLength"));
        assertEquals(false, data.get("current"));
        assertEquals(1, ((List<?>) data.get("files")).size());
    }

    @Test
    @DisplayName("没有版本行的来源取历史版本时按 404 语义拒绝")
    void versionContentWithoutHistoryRejects() {
        SkillStore nacosLike = mock(SkillStore.class, Answers.CALLS_REAL_METHODS);
        SkillAdminController local =
                new SkillAdminController(
                        nacosLike, agentRegistry, auditService, importService, gateway);

        assertThrows(
                PayrollNotFoundException.class, () -> local.versionContent("poetry", 3));
    }

    @Test
    @DisplayName("SkillStore 默认实现把 summaries 退回 list，旧实现不改也能编译")
    void summariesFallsBackToList() {
        SkillStore legacy = mock(SkillStore.class, Answers.CALLS_REAL_METHODS);
        when(legacy.list())
                .thenReturn(List.of(summary("poetry", "诗词", "nacos", false)));

        assertEquals(1, legacy.summaries().size());
        assertEquals(0, legacy.resourceHashes("poetry").size());
    }

    @Test
    @DisplayName("导入预览逐文件比对指纹，已有技能读不到指纹时全部记为未知")
    void importPreviewMarksFileStatuses() {
        SkillImportService.ImportResult result =
                new SkillImportService.ImportResult(
                        "poetry",
                        "abc123",
                        "诗词技能",
                        Map.of(),
                        List.of(
                                new SkillFilePackage.Summary("references/a.md", 10, "reference"),
                                new SkillFilePackage.Summary("scripts/run.py", 12, "script"),
                                new SkillFilePackage.Summary("references/new.md", 5, "reference")),
                        null,
                        1024L,
                        List.of(),
                        true,
                        true,
                        "git",
                        Map.of(
                                "references/a.md", "h1",
                                "scripts/run.py", "h2",
                                "references/new.md", "h3"));
        when(importService.importGit(
                        "https://example.com/repo.git", null, "poetry", true, true, null, ""))
                .thenReturn(result);
        when(registry.resourceHashes("poetry"))
                .thenReturn(Map.of("references/a.md", "h1", "scripts/run.py", "old"));
        when(registry.detail("poetry"))
                .thenReturn(detail("poetry", "诗词技能", "正文", List.of(), "3", 3));

        Map<?, ?> data =
                (Map<?, ?>) controller.importGit(
                                new SkillAdminController.GitImportInput(
                                        "https://example.com/repo.git", null, "poetry", true,
                                        true),
                                null)
                        .data();

        List<?> files = (List<?>) data.get("files");
        assertEquals(
                List.of("UNCHANGED", "REPLACED", "ADDED"),
                files.stream().map(file -> ((Map<?, ?>) file).get("status")).toList());
        Map<?, ?> totals = (Map<?, ?>) data.get("totals");
        assertEquals(1, totals.get("unchanged"));
        assertEquals(1, totals.get("replaced"));
        assertEquals(1, totals.get("added"));
        assertNull(totals.get("unknown"));
        assertEquals(3, data.get("currentVersionNo"));

        when(registry.resourceHashes("poetry")).thenReturn(Map.of());
        Map<?, ?> unknownData =
                (Map<?, ?>) controller.importGit(
                                new SkillAdminController.GitImportInput(
                                        "https://example.com/repo.git", null, "poetry", true,
                                        true),
                                null)
                        .data();
        List<?> unknownFiles = (List<?>) unknownData.get("files");
        assertTrue(
                unknownFiles.stream()
                        .allMatch(file -> "UNKNOWN".equals(((Map<?, ?>) file).get("status"))),
                String.valueOf(unknownFiles));
        assertEquals(3, ((Map<?, ?>) unknownData.get("totals")).get("unknown"));
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

    @Test
    @DisplayName("数据目录树把沙箱条目映射为前端字段，缺失 isText 不补默认值")
    void dataTreeMapsSandboxEntries() {
        when(registry.detail("poetry-skill")).thenReturn(scriptedDetail());
        when(gateway.enabled()).thenReturn(true);
        when(gateway.dataTree("poetry-skill", null, SkillDataProtocol.DEFAULT_DEPTH))
                .thenReturn(
                        new SandboxGateway.DataTree(
                                "poetry-skill",
                                "",
                                List.of(
                                        new SandboxGateway.DataEntry(
                                                "reports", "reports", "dir", 0, 1L, null),
                                        new SandboxGateway.DataEntry(
                                                "a.txt", "a.txt", "file", 5, 2L, true)),
                                false,
                                500));

        Map<?, ?> data = (Map<?, ?>) controller.dataTree("poetry-skill", null, null).data();

        assertEquals("poetry-skill", data.get("skillId"));
        assertEquals("", data.get("path"));
        assertEquals(false, data.get("truncated"));
        assertEquals(500, data.get("entryLimit"));
        List<?> entries = (List<?>) data.get("entries");
        Map<?, ?> dir = (Map<?, ?>) entries.get(0);
        assertEquals("reports", dir.get("path"));
        assertEquals("dir", dir.get("type"));
        assertFalse(dir.containsKey("isText"), String.valueOf(dir));
        Map<?, ?> file = (Map<?, ?>) entries.get(1);
        assertEquals(true, file.get("isText"));
        assertEquals(5L, file.get("size"));
        verify(gateway).dataTree("poetry-skill", null, SkillDataProtocol.DEFAULT_DEPTH);
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("数据文件预览回传正文与 MIME，审计只记元数据不含正文")
    void dataFileReturnsContentAndAuditsMetadata() {
        when(registry.detail("poetry-skill")).thenReturn(scriptedDetail());
        when(gateway.enabled()).thenReturn(true);
        when(gateway.dataFile("poetry-skill", "reports/a.md", SkillDataProtocol.DEFAULT_PREVIEW_BYTES))
                .thenReturn(
                        new SandboxGateway.DataFile(
                                "reports/a.md", 5, 2L, true, "utf-8", false, "hello", "text/markdown"));

        Map<?, ?> data =
                (Map<?, ?>) controller.dataFile("poetry-skill", "reports/a.md", null).data();

        assertEquals("reports/a.md", data.get("path"));
        assertEquals(5L, data.get("size"));
        assertEquals("utf-8", data.get("encoding"));
        assertEquals("hello", data.get("content"));
        assertEquals("text/markdown", data.get("mime"));
        verify(auditService)
                .record(
                        "skill.data.read",
                        "skill",
                        "poetry-skill",
                        null,
                        Map.of(
                                "skillId", "poetry-skill",
                                "path", "reports/a.md",
                                "sizeBytes", 5L,
                                "truncated", false,
                                "isText", true));

        when(gateway.dataFile("poetry-skill", "a.bin", SkillDataProtocol.DEFAULT_PREVIEW_BYTES))
                .thenReturn(
                        new SandboxGateway.DataFile(
                                "a.bin", 3, 2L, false, null, false, null, "application/octet-stream"));
        Map<?, ?> binary = (Map<?, ?>) controller.dataFile("poetry-skill", "a.bin", null).data();
        assertFalse(binary.containsKey("encoding"));
        assertFalse(binary.containsKey("content"));
        assertEquals("application/octet-stream", binary.get("mime"));
    }

    @Test
    @DisplayName("非 db 来源、未声明 setup 或沙箱关闭时拒绝浏览，不触达网关")
    void dataBrowseRequiresDbSetupAndSandbox() {
        SkillDetail nacos =
                new SkillDetail(
                        "poetry", "诗词", "正文", "nacos", false, false, 1L, List.of(), false,
                        Map.of("setup", "bash setup.sh"), "", 0);
        when(registry.detail("poetry")).thenReturn(nacos);
        assertThrows(PayrollException.class, () -> controller.dataTree("poetry", null, null));

        when(registry.detail("payroll-auditor"))
                .thenReturn(detail("payroll-auditor", "审计", "正文", List.of(), "1", 1));
        assertThrows(
                PayrollException.class, () -> controller.dataFile("payroll-auditor", "a.txt", null));

        when(registry.detail("poetry-skill")).thenReturn(scriptedDetail());
        when(gateway.enabled()).thenReturn(false);
        assertThrows(
                PayrollException.class, () -> controller.dataTree("poetry-skill", null, null));

        verify(gateway, never()).dataTree(any(), any(), anyInt());
        verify(gateway, never()).dataFile(any(), any(), anyInt());
    }

    @Test
    @DisplayName("沙箱客户端不支持数据浏览时映射为 503 的沙箱不可用异常")
    void dataBrowseMapsUnsupportedGateway() {
        when(registry.detail("poetry-skill")).thenReturn(scriptedDetail());
        when(gateway.enabled()).thenReturn(true);
        when(gateway.dataTree(any(), any(), anyInt()))
                .thenThrow(new UnsupportedOperationException("旧版沙箱"));

        assertThrows(
                SandboxUnavailableException.class,
                () -> controller.dataTree("poetry-skill", null, null));
    }

    @Test
    @DisplayName("数据浏览接口同样要求 skill:manage")
    void dataBrowseEndpointsRequireSkillManage() {
        for (String name : List.of("dataTree", "dataFile")) {
            Method method =
                    Arrays.stream(SkillAdminController.class.getDeclaredMethods())
                            .filter(candidate -> candidate.getName().equals(name))
                            .findFirst()
                            .orElseThrow();
            RequirePermission required = method.getAnnotation(RequirePermission.class);
            assertTrue(
                    required != null
                            && Arrays.asList(required.value()).contains(Permissions.SKILL_MANAGE),
                    name + " 缺少 skill:manage");
        }
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

    private SkillAdminController richController(
            SkillDataStateStore states, SkillCreationOriginStore origins, H2AdminRepository users) {
        return new SkillAdminController(
                registry,
                agentRegistry,
                auditService,
                importService,
                states,
                origins,
                new SkillTemplates(),
                users,
                gateway);
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

    private static List<?> skillsOf(ApiResponse response) {
        return (List<?>) ((Map<?, ?>) response.data()).get("skills");
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
