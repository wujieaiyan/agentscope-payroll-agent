package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.PayrollConflictException;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 技能落库写路径（T-04）：版本只跟正文走、三个 {@code resource_*} 与清单同事务、revision 乐观并发、
 * 回滚只清本轮新写的字节。跨源指纹等价是切换存储不出故障的前提，也在这里钉住。
 */
class JdbcSkillStoreTest {

    private static final Map<String, Object> META =
            Map.of("version", "1.2.0", "tags", List.of("Payroll", "个税"), "author", "hr-team");

    private static final String RULES = "先取员工档案与考勤，再按参保城市政策测算，最后输出可复核的明细与依据。";

    @TempDir Path blobRoot;

    private PayrollDatabase database;
    private LocalDiskSkillBlobStore blobs;
    private JdbcSkillStore store;

    @BeforeEach
    void setUp() {
        database = PayrollDatabase.inMemory("jdbc-skill-" + System.nanoTime());
        blobs = new LocalDiskSkillBlobStore(blobRoot.toString(), 1024 * 1024);
        // 显式给配置：测试结论不能随 PAYROLL_SKILL_FILES_ENABLED 这类环境变量漂移。
        store = new JdbcSkillStore(database, blobs, SkillFileSettings.defaults());
    }

    @Test
    @DisplayName("一次导入写齐正文、清单与派生列，资源指纹与 Nacos 源同算法")
    void importWritesBodyResourcesAndDerivedColumns() throws Exception {
        Map<String, String> pack = new LinkedHashMap<>();
        pack.put("references/a.md", "个税专项附加扣除口径");
        pack.put("scripts/run.sh", "echo calc");
        String cleaned = body("测算助手");
        store.importProject(project("calc-helper", cleaned, files(pack), META), true);

        JdbcSkillStore.Row row = row("calc-helper");
        assertEquals(cleaned, store.bodyOf(row));
        assertEquals(cleaned.length(), row.contentLength());
        assertEquals(SkillManifests.contentHash(cleaned), row.contentHash());
        assertEquals(2, row.resourceCount());
        assertEquals(1, row.revision());
        assertEquals("1.2.0", row.version());
        assertEquals(List.of("payroll", "个税"), row.tags());
        // 规格 §8 步骤 4：三个 resource_* 必须与步骤 3 写入的行一致，否则指纹会漂。
        assertEquals(
                utf8Bytes(pack),
                scalar("SELECT resource_bytes FROM skill_definition WHERE skill_id='calc-helper'"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM skill_resource WHERE version_id=" + row.versionId()));
        assertEquals(2, scalar("SELECT COUNT(*) FROM skill_blob"));
        assertEquals(SkillManifests.resourceHash(pack), row.resourceHash());

        SkillDetail detail = store.detail("calc-helper");
        assertEquals(JdbcSkillStore.SOURCE_DB, detail.source());
        assertEquals(cleaned, detail.content());
        assertEquals("hr-team", detail.metadata().get("author"));
        assertEquals(List.of("references/a.md", "scripts/run.sh"), paths(store.files("calc-helper")));
        assertEquals("echo calc", store.fileContent("calc-helper", "scripts/run.sh"));
        assertEquals(pack, store.contents(row.id(), store.resourcesOf(row)));
        assertEquals("text/plain", store.resourcesOf(row).get(0).mediaType());
        assertFalse(store.resourcesOf(row).get(0).binary());
        assertTrue(store.blobLocation().startsWith("local:"));
    }

    @Test
    @DisplayName("create 撞已有 id 报「技能已存在」")
    void createRejectsExistingId() throws Exception {
        store.create("dup-helper", "描述", body("一"), false);
        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> store.create("dup-helper", "描述", body("二"), false));
        assertTrue(error.getMessage().contains("技能已存在"), error.getMessage());
        assertEquals(1, scalar("SELECT COUNT(*) FROM skill_definition WHERE skill_id='dup-helper'"));
    }

    @Test
    @DisplayName("携带过期 revision 的四类写入都返回 409 且一行不改")
    void staleRevisionRejectsEveryWriter() throws Exception {
        store.importProject(
                project("conc-helper", body("并发"), pack("references/a.md", "甲"), META), true);
        JdbcSkillStore.Row before = row("conc-helper");
        String stale = String.valueOf(before.revision() + 41);

        assertThrows(
                PayrollConflictException.class,
                () -> store.update("conc-helper", "描述", body("改一"), false, stale));
        assertThrows(
                PayrollConflictException.class,
                () ->
                        store.replaceFiles(
                                "conc-helper", pack("references/b.md", "乙"), stale));
        assertThrows(
                PayrollConflictException.class,
                () -> store.deleteFile("conc-helper", "references/a.md", stale));
        PayrollConflictException error =
                assertThrows(
                        PayrollConflictException.class,
                        () -> store.delete("conc-helper", stale));
        assertEquals(String.valueOf(before.revision()), error.currentRevision());

        JdbcSkillStore.Row after = row("conc-helper");
        assertEquals(before.versionId(), after.versionId());
        assertEquals(before.revision(), after.revision());
        assertEquals(before.resourceHash(), after.resourceHash());
        assertEquals(
                "甲",
                store.fileContent("conc-helper", "references/a.md"),
                "逐个删除撞 409 不能先把清单行删掉");
        assertEquals(body("并发"), store.bodyOf(after), "冲突时不能覆盖他人正文");
        assertEquals(1, scalar("SELECT COUNT(*) FROM skill_version"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM skill_blob"));
        // 正确 revision 仍然能写：证明 409 来自比对而不是行锁残留。
        store.update("conc-helper", "描述", body("改一"), false, String.valueOf(before.revision()));
        assertEquals(2, row("conc-helper").revision());
    }

    @Test
    @DisplayName("详情与列表都回吐 revision：文件包写入只涨 revision，内置源两个键都空")
    void detailCarriesRevisionAndVersionNo() {
        store.importProject(
                project("rev-helper", body("初版"), pack("references/a.md", "甲"), META), true);
        SkillDetail first = store.detail("rev-helper");
        assertEquals("1", first.revision());
        assertEquals(1, first.versionNo());
        // 管理台从列表就能拿到下一次写的 token，不必逐条点详情。
        assertEquals("1", summary("rev-helper").revision());
        assertEquals("", summary(RoleSkills.VALIDATOR).revision());
        assertEquals(0, store.detail(RoleSkills.VALIDATOR).versionNo());

        store.replaceFiles(
                "rev-helper", pack("references/a.md", "甲改"), first.revision());
        SkillDetail afterFiles = store.detail("rev-helper");
        assertEquals("2", afterFiles.revision());
        assertEquals(1, afterFiles.versionNo(), "§9.2 版本只跟正文走：换文件包不追加正文版本");
        assertEquals("甲改", store.fileContent("rev-helper", "references/a.md"));

        store.update("rev-helper", "描述", body("二版"), false, afterFiles.revision());
        SkillDetail afterBody = store.detail("rev-helper");
        assertEquals("3", afterBody.revision());
        // versionNo 必须与同一次读取的正文配对：审计里出现「v2 + 旧正文」会让回滚基线算错。
        assertEquals(2, afterBody.versionNo());
        assertEquals(body("二版"), afterBody.content());
        assertEquals("3", summary("rev-helper").revision());
    }

    @Test
    @DisplayName("正文不变不追加版本；正文变了追加 v2、复制清单且不动旧版本行")
    void versionsFollowBodyOnly() throws Exception {
        store.importProject(
                project("ver-helper", body("初版"), pack("references/a.md", "甲"), META), true);
        JdbcSkillStore.Row first = row("ver-helper");

        store.update("ver-helper", "换个描述", body("初版"), true);
        JdbcSkillStore.Row sameBody = row("ver-helper");
        assertEquals(first.versionId(), sameBody.versionId());
        assertEquals(1, scalar("SELECT COUNT(*) FROM skill_version WHERE skill_id='ver-helper'"));
        assertEquals("换个描述", sameBody.description());
        assertTrue(sameBody.executionEnabled());
        assertEquals(1, sameBody.resourceCount(), "只改描述不能清掉文件包");
        assertEquals(first.resourceHash(), sameBody.resourceHash());
        assertEquals(2, sameBody.revision());

        store.update("ver-helper", "换个描述", body("新版"), false, String.valueOf(sameBody.revision()));
        JdbcSkillStore.Row changed = row("ver-helper");
        assertNotEquals(first.versionId(), changed.versionId());
        assertEquals(2, scalar("SELECT version_no FROM skill_version WHERE version_id=" + changed.versionId()));
        assertEquals(1, changed.resourceCount(), "新版本要带上旧版本的清单");
        assertEquals(
                2,
                scalar("SELECT COUNT(*) FROM skill_resource"),
                "每个版本各留一份清单行，字节按 sha 复用");
        assertEquals(
                store.resourcesOf(first).get(0).sha256(), store.resourcesOf(changed).get(0).sha256());
        assertEquals(1, scalar("SELECT COUNT(*) FROM skill_blob"), "同内容不该再写一份字节");
        assertEquals(2, scalar("SELECT COUNT(*) FROM skill_version WHERE skill_id='ver-helper'"));
        assertEquals(
                body("初版"),
                bodyText(first.versionId()),
                "已发布版本不可变：正文不能被就地改写");
        assertFalse(store.detail("ver-helper").executionEnabled());
    }

    @Test
    @DisplayName("回滚追加新版本：正文与清单回到目标，描述与执行开关不动，历史行不改写")
    void rollbackRestoresBodyAndManifest() throws Exception {
        store.importProject(
                project("rb-helper", body("初版"), pack("references/a.md", "甲"), META), true);
        JdbcSkillStore.Row first = row("rb-helper");
        store.update("rb-helper", "改过的描述", body("二版"), true, "1", "升到二版");
        JdbcSkillStore.Row second = row("rb-helper");
        assertEquals(2, currentVersionNo("rb-helper"));

        SkillSummary rolled = store.rollback("rb-helper", 1, String.valueOf(second.revision()));

        assertEquals("rb-helper", rolled.id());
        JdbcSkillStore.Row after = row("rb-helper");
        // §9.2：回滚是「新增一条 v3」而不是把指针退回旧行，序号只会单调向前。
        assertEquals(3, currentVersionNo("rb-helper"));
        assertEquals(3, after.revision());
        assertEquals(body("初版"), store.bodyOf(after));
        assertEquals("改过的描述", after.description());
        assertTrue(after.executionEnabled(), "回滚只退正文与文件，描述不属于版本内容");
        assertEquals(1, after.resourceCount());
        assertEquals("甲", store.fileContent("rb-helper", "references/a.md"));
        assertEquals(
                store.resourcesOf(first).get(0).sha256(),
                store.resourcesOf(after).get(0).sha256(),
                "清单整批复制到新版本，指纹不能重算成别的值");
        assertEquals(3, scalar("SELECT COUNT(*) FROM skill_version WHERE skill_id='rb-helper'"));
        assertEquals(
                3,
                scalar("SELECT COUNT(*) FROM skill_resource"),
                "每个版本各留一份清单行，回滚不能借用旧版本的行");
        assertEquals(1, scalar("SELECT COUNT(*) FROM skill_blob"), "复用旧字节，不再写一份");
        assertEquals("回滚自 v1", noteOf("rb-helper", 3));
        assertEquals(body("初版"), bodyText(first.versionId()), "历史版本行必须原样留在库里，可反复回滚");
        assertEquals(body("二版"), bodyText(second.versionId()));
        assertEquals(3, store.detail("rb-helper").versionNo());
        assertEquals("3", summary("rb-helper").revision());
    }

    @Test
    @DisplayName("回滚拒绝当前版本、不存在的版本与未落库技能；令牌过期只返回 409 且一行不改")
    void rollbackRejectsUnusableTargets() throws Exception {
        store.importProject(project("rej-helper", body("初版"), List.of(), META), true);
        store.update("rej-helper", "描述", body("二版"), false, "1", "升到二版");
        JdbcSkillStore.Row before = row("rej-helper");

        assertTrue(
                assertThrows(
                                PayrollException.class,
                                () -> store.rollback("rej-helper", 2, "2"))
                        .getMessage()
                        .contains("已是当前版本"),
                "回到自己该报错，而不是悄悄写一条重复正文");
        assertTrue(
                assertThrows(
                                PayrollException.class, () -> store.rollback("rej-helper", 9, "2"))
                        .getMessage()
                        .contains("没有 v9 版本"));
        assertEquals(
                "2",
                assertThrows(
                                PayrollConflictException.class,
                                () -> store.rollback("rej-helper", 1, "41"))
                        .currentRevision());
        assertThrows(PayrollException.class, () -> store.rollback(RoleSkills.VALIDATOR, 1, ""));

        JdbcSkillStore.Row after = row("rej-helper");
        assertEquals(before.versionId(), after.versionId());
        assertEquals(before.revision(), after.revision());
        assertEquals(2, scalar("SELECT COUNT(*) FROM skill_version WHERE skill_id='rej-helper'"));
    }

    @Test
    @DisplayName("目标版本的文件字节已缺失时回滚整笔不提交，不留空版本行")
    void rollbackFailsClosedWhenBlobMissing() throws Exception {
        store.importProject(
                project("blob-helper", body("初版"), pack("references/a.md", "甲"), META), true);
        JdbcSkillStore.Row first = row("blob-helper");
        store.update("blob-helper", "描述", body("二版"), false, "1", "升到二版");
        blobs.delete(store.resourcesOf(first).get(0).sha256());

        assertTrue(
                assertThrows(
                                PayrollException.class, () -> store.rollback("blob-helper", 1, "2"))
                        .getMessage()
                        .contains("字节缺失"));

        JdbcSkillStore.Row after = row("blob-helper");
        assertEquals(
                2, currentVersionNo("blob-helper"), "字节缺失时不能把指针移到读不出文件的版本");
        assertEquals(2, scalar("SELECT COUNT(*) FROM skill_version WHERE skill_id='blob-helper'"));
        assertEquals(body("二版"), store.bodyOf(after));
        assertEquals(2, after.revision());
    }

    @Test
    @DisplayName("版本时间线倒序返回，current 跟指针走而不是最大序号")
    void versionsTimelineFollowsPointer() {
        store.importProject(project("tl-helper", body("初版"), List.of(), META), true);
        store.update("tl-helper", "描述", body("二版"), false, "1", "升到二版");

        List<SkillVersion> timeline = store.versions("tl-helper");
        assertEquals(List.of(2, 1), timeline.stream().map(SkillVersion::versionNo).toList());
        assertEquals(List.of("升到二版", ""), timeline.stream().map(SkillVersion::changeNote).toList());
        assertEquals(List.of(true, false), timeline.stream().map(SkillVersion::current).toList());
        assertEquals(body("二版").length(), timeline.get(0).contentLength());
        assertTrue(timeline.get(0).createdAt() > 0L);
        assertEquals(
                "",
                timeline.get(0).createdBy(),
                "窄签名不带操作者，created_by 落 NULL、回读空串——归属要查审计页");

        store.rollback("tl-helper", 1, "2");
        List<SkillVersion> rolled = store.versions("tl-helper");
        assertEquals(List.of(3, 2, 1), rolled.stream().map(SkillVersion::versionNo).toList());
        // 回滚后生效的是 v1 的正文，但当前版本是 v3：标错行会让管理台把「当前」显示成历史版本。
        assertEquals(
                List.of(true, false, false), rolled.stream().map(SkillVersion::current).toList());
        assertEquals(List.of(), store.versions(RoleSkills.VALIDATOR), "内置未落库没有版本行");
        assertEquals(List.of(), store.versions("no-such-skill"));
    }

    @Test
    @DisplayName("五类写入各带不同操作者哨兵，三处溯源列一一归位")
    void operatorWritesEveryProvenanceColumn() throws Exception {
        store.importProject(
                project("op-helper", body("初版"), List.of(), META), true, null, "灌包人");
        assertEquals("灌包人", definitionColumn("op-helper", "created_by"));
        assertEquals("灌包人", definitionColumn("op-helper", "updated_by"));
        assertEquals("灌包人", versionColumn("op-helper", 1, "created_by"));

        store.update("op-helper", "描述", body("二版"), false, "1", "升到二版", "改稿人");
        assertEquals("灌包人", definitionColumn("op-helper", "created_by"), "创建者只跟第一次写入");
        assertEquals("改稿人", definitionColumn("op-helper", "updated_by"));
        assertEquals("改稿人", versionColumn("op-helper", 2, "created_by"));
        assertEquals(
                "升到二版",
                versionColumn("op-helper", 2, "change_note"),
                "说明进 change_note，串位时这一列会先红");

        store.replaceFiles("op-helper", pack("references/a.md", "甲"), "2", "换文件人");
        assertEquals("换文件人", definitionColumn("op-helper", "updated_by"));
        assertEquals(2, currentVersionNo("op-helper"), "换文件包不追加正文版本");
        assertEquals("灌包人", definitionColumn("op-helper", "created_by"));

        store.deleteFile("op-helper", "references/a.md", "3", "删文件人");
        assertEquals("删文件人", definitionColumn("op-helper", "updated_by"));

        store.rollback("op-helper", 1, "4", "回滚人");
        assertEquals(3, currentVersionNo("op-helper"));
        assertEquals("回滚人", versionColumn("op-helper", 3, "created_by"), "v3 是回滚人造的，不是 v1 的作者");
        assertEquals("回滚人", definitionColumn("op-helper", "updated_by"));
        assertEquals("灌包人", definitionColumn("op-helper", "created_by"));
        assertEquals(
                List.of("回滚人", "改稿人", "灌包人"),
                store.versions("op-helper").stream().map(SkillVersion::createdBy).toList());
    }

    @Test
    @DisplayName("没有操作者时溯源列落 NULL，既不写空串也不编一个 admin")
    void unknownOperatorLeavesProvenanceNull() throws Exception {
        store.importProject(project("null-op-helper", body("初版"), List.of(), META), true);
        store.update("null-op-helper", "描述", body("二版"), false, "1", "升到二版");

        assertNull(definitionColumn("null-op-helper", "created_by"));
        assertNull(definitionColumn("null-op-helper", "updated_by"));
        assertNull(versionColumn("null-op-helper", 1, "created_by"));
        assertEquals(
                "",
                store.versions("null-op-helper").get(0).createdBy(),
                "NULL 回读成空串，管理台据此显示「未知」");
    }

    @Test
    @DisplayName("导入的两类 409 靠 currentRevision 区分：令牌过期带回指纹，覆盖确认不带")
    void importConflictsDifferByCurrentRevision() throws Exception {
        store.importProject(project("im-helper", body("初版"), List.of(), META), true, null, "灌包人");

        PayrollConflictException overwrite =
                assertThrows(
                        PayrollConflictException.class,
                        () ->
                                store.importProject(
                                        project("im-helper", body("二版"), List.of(), META), false));
        assertNull(
                overwrite.currentRevision(),
                "「覆盖需要显式确认」不是并发冲突，回一个刷新就能解决的令牌会误导操作者");

        PayrollConflictException stale =
                assertThrows(
                        PayrollConflictException.class,
                        () ->
                                store.importProject(
                                        project("im-helper", body("二版"), List.of(), META),
                                        true,
                                        "41",
                                        "灌包人"));
        assertEquals("1", stale.currentRevision());
        assertEquals(
                body("初版"),
                bodyText(row("im-helper").versionId()),
                "令牌不一致时整笔不提交，正文一个字都不改");

        store.importProject(project("im-helper", body("三版"), List.of(), META), true, "1", "接手人");
        assertEquals("接手人", definitionColumn("im-helper", "updated_by"));
        assertEquals("灌包人", definitionColumn("im-helper", "created_by"), "覆盖导入不改创建者");
    }

    @Test
    @DisplayName("变更说明：正文变了挂新版本，正文没变补挂当前版本，空白不清空，超长整笔回滚")
    void changeNoteFollowsBodyOrCurrentRow() throws Exception {
        store.importProject(project("note-helper", body("初版"), List.of(), META), true);
        assertEquals("", noteOf("note-helper", 1), "新建的第一版没有「变更」可言");

        store.update("note-helper", "描述", body("二版"), false, "1", "  按 11 月基数调整  ");
        assertEquals(2, currentVersionNo("note-helper"));
        assertEquals("按 11 月基数调整", noteOf("note-helper", 2), "入库前去掉首尾空白");
        assertEquals("", noteOf("note-helper", 1), "追加新版本不能改写历史行的说明");

        store.update("note-helper", "只改描述", body("二版"), false, "2", "补一句说明");
        assertEquals(2, currentVersionNo("note-helper"));
        assertEquals("补一句说明", noteOf("note-helper", 2), "正文没变时说明改挂当前版本行");

        store.update("note-helper", "又改描述", body("二版"), false, "3", "   ");
        JdbcSkillStore.Row unchanged = row("note-helper");
        assertEquals(2, currentVersionNo("note-helper"));
        assertEquals("补一句说明", noteOf("note-helper", 2), "空白说明等于没填，不能抹掉已有说明");
        assertEquals("又改描述", unchanged.description(), "说明没填不影响其他字段写入");

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () ->
                                store.update(
                                        "note-helper",
                                        "描述",
                                        body("三版"),
                                        false,
                                        String.valueOf(unchanged.revision()),
                                        "超".repeat(201)));
        assertTrue(error.getMessage().contains("变更说明过长"));
        JdbcSkillStore.Row rejected = row("note-helper");
        assertEquals(2, currentVersionNo("note-helper"));
        assertEquals(unchanged.revision(), rejected.revision(), "校验失败不能把 revision 涨上去");
        assertEquals("又改描述", rejected.description());

        store.update(
                "note-helper", "描述", body("三版"), false, String.valueOf(rejected.revision()),
                "超".repeat(200));
        assertEquals("超".repeat(200), noteOf("note-helper", 3), "200 字符正好落在列宽内");
    }

    @Test
    @DisplayName("只改正文的保存不清空标签、版本号与自定义元信息")
    void bodyOnlySavePreservesMetadata() {
        store.importProject(project("meta-helper", body("初版"), List.of(), META), true);
        store.update("meta-helper", "新描述", body("二版"), false);

        JdbcSkillStore.Row row = row("meta-helper");
        assertEquals(List.of("payroll", "个税"), row.tags());
        assertEquals("1.2.0", row.version());
        assertEquals("新描述", row.description());
        SkillDetail detail = store.detail("meta-helper");
        assertEquals("hr-team", detail.metadata().get("author"));
        assertEquals("1.2.0", detail.metadata().get("version"));
        assertEquals(List.of("payroll", "个税"), detail.metadata().get("tags"));
        assertEquals("meta-helper", detail.metadata().get("name"));
    }

    @Test
    @DisplayName("整包替换文件不追加版本、重算派生列，被移除的字节留给保留期巡检")
    void replaceFilesRecomputesFingerprintInPlace() throws Exception {
        store.importProject(
                project(
                        "pkg-helper",
                        body("初版"),
                        pack("references/a.md", "甲", "scripts/run.sh", "乙"),
                        META),
                true);
        JdbcSkillStore.Row before = row("pkg-helper");
        String removedSha =
                store.resourcesOf(before).stream()
                        .filter(ref -> ref.path().equals("scripts/run.sh"))
                        .findFirst()
                        .orElseThrow()
                        .sha256();

        Map<String, String> next = new LinkedHashMap<>();
        next.put("references/a.md", "甲");
        next.put("references/deep/b.md", "丙丙丙");
        store.replaceFiles("pkg-helper", files(next));

        JdbcSkillStore.Row after = row("pkg-helper");
        assertEquals(before.versionId(), after.versionId());
        assertEquals(before.contentHash(), after.contentHash());
        assertEquals(before.revision() + 1, after.revision());
        assertEquals(1, scalar("SELECT COUNT(*) FROM skill_version"));
        assertEquals(2, after.resourceCount());
        assertEquals(2, scalar("SELECT COUNT(*) FROM skill_resource WHERE version_id=" + after.versionId()));
        assertEquals(SkillManifests.resourceHash(next), after.resourceHash());
        assertEquals(
                utf8Bytes(next),
                scalar("SELECT resource_bytes FROM skill_definition WHERE skill_id='pkg-helper'"));
        assertEquals(List.of("references/a.md", "references/deep/b.md"), paths(store.files("pkg-helper")));
        assertEquals("丙丙丙", store.fileContent("pkg-helper", "references/deep/b.md"));
        assertTrue(blobs.exists(removedSha), "写路径不回收字节，否则并发节点的同一份内容会被删掉");
        assertEquals(3, scalar("SELECT COUNT(*) FROM skill_blob"));
    }

    @Test
    @DisplayName("介质写坏时整事务回滚，只删本轮新写的字节")
    void rollbackCleansFreshBlobsOnly() throws Exception {
        store.importProject(
                project("rb-helper", body("初版"), pack("references/keep.md", "已提交"), META), true);
        JdbcSkillStore.Row before = row("rb-helper");
        String committedSha = store.resourcesOf(before).get(0).sha256();
        String freshSha = SkillManifests.contentHash("本轮新字节");

        FlakyBlobStore flaky = new FlakyBlobStore(blobRoot, 2);
        JdbcSkillStore failing = new JdbcSkillStore(database, flaky, SkillFileSettings.defaults());
        assertThrows(
                IllegalStateException.class,
                () ->
                        failing.replaceFiles(
                                "rb-helper",
                                pack("references/fresh.md", "本轮新字节", "references/boom.md", "写坏了")));

        JdbcSkillStore.Row after = row("rb-helper");
        assertEquals(before.versionId(), after.versionId());
        assertEquals(before.resourceCount(), after.resourceCount());
        assertEquals(before.resourceHash(), after.resourceHash());
        assertEquals(1, scalar("SELECT COUNT(*) FROM skill_blob"), "本轮的 blob 行随事务回滚");
        assertFalse(flaky.exists(freshSha), "本轮新写且无引用的字节必须清掉");
        assertEquals(List.of(freshSha), flaky.deleted);
        assertTrue(flaky.exists(committedSha), "已提交的字节不是本轮产生的，不能删");
    }

    @Test
    @DisplayName("逐个删除文件：派生列跟随，删空后指纹读回空串而不是空格")
    void deleteFileRecomputesUntilEmpty() throws Exception {
        store.importProject(
                project(
                        "df-helper",
                        body("初版"),
                        pack("references/a.md", "甲", "references/b.md", "乙"),
                        META),
                true);

        store.deleteFile("df-helper", "references/a.md");
        JdbcSkillStore.Row one = row("df-helper");
        assertEquals(1, one.resourceCount());
        assertEquals(SkillManifests.resourceHash(Map.of("references/b.md", "乙")), one.resourceHash());
        assertEquals(List.of("references/b.md"), resourcePaths(one));
        assertThrows(PayrollException.class, () -> store.deleteFile("df-helper", "references/a.md"));

        store.deleteFile("df-helper", "references/b.md");
        JdbcSkillStore.Row none = row("df-helper");
        assertEquals(0, none.resourceCount());
        assertEquals(List.of(), resourcePaths(none));
        assertEquals("", none.resourceHash(), "CHAR(64) 空串会被读成一串空格，指纹比较前必须归一化");
        assertEquals(0, scalar("SELECT resource_bytes FROM skill_definition WHERE skill_id='df-helper'"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM skill_blob"), "删清单行不回收字节");
    }

    @Test
    @DisplayName("目录按 sort_order 排，未落库的内置技能仍出现在列表里")
    void listKeepsBuiltinsFirstAndCatalogOrdered() {
        assertEquals(List.of(), store.catalog());
        List<SkillSummary> empty = store.list();
        assertEquals(RoleSkills.ids(), ids(empty));
        assertTrue(empty.stream().allMatch(item -> item.builtin()));
        assertTrue(empty.stream().noneMatch(item -> JdbcSkillStore.SOURCE_DB.equals(item.source())));

        store.importProject(project(RoleSkills.CALCULATOR, body("内置覆盖"), List.of(), META), true);
        store.create("alpha-helper", "甲", body("甲"), false);
        store.create("beta-helper", "乙", body("乙"), false);

        assertEquals(
                List.of(RoleSkills.CALCULATOR, "alpha-helper", "beta-helper"),
                store.catalog().stream().map(JdbcSkillStore.Row::id).toList());
        List<SkillSummary> listed = store.list();
        // 内置的三个即使没落库也必须出现在列表里，否则切到 db 源的第一天技能页会少掉内置项。
        assertEquals(
                List.of(
                        RoleSkills.CALCULATOR,
                        RoleSkills.VALIDATOR,
                        RoleSkills.ACCOUNTANT,
                        "alpha-helper",
                        "beta-helper"),
                ids(listed));
        assertEquals(JdbcSkillStore.SOURCE_DB, listed.get(0).source());
        assertTrue(listed.get(0).builtin());
        assertTrue(listed.get(0).overridden());
        assertEquals(NacosSkillRegistry.SOURCE_BUILTIN, listed.get(1).source());
        assertFalse(listed.get(1).overridden());
        assertEquals(List.of(), store.detail(RoleSkills.CALCULATOR).files());
        assertTrue(store.find(RoleSkills.VALIDATOR).isEmpty(), "内置技能未落库时不该有定义行");
    }

    @Test
    @DisplayName("列表摘要能带着文件跑完整条 SQL：LIKE 里的百分号不能被当成格式符")
    void summariesFormatsSetupQueryWithLiteralPercent() {
        store.importProject(
                project("setup-helper", body("需要初始化"), pack("scripts/setup.sh", "echo init"), META),
                true);
        store.importProject(
                project("plain-helper", body("无需初始化"), pack("references/a.md", "甲"), META), true);

        // 只要库里有行，summaries 就走拼接过的 setup 查询；模板里的 '%' 漏转义会在这里抛
        // UnknownFormatConversionException，列表接口整页 400。
        Map<String, SkillSummary> byId = new LinkedHashMap<>();
        store.summaries().forEach(item -> byId.put(item.id(), item));

        assertEquals(Boolean.TRUE, byId.get("setup-helper").needsData(), "scripts/setup.sh 应被判为需要数据");
        assertEquals(Boolean.FALSE, byId.get("plain-helper").needsData());
        assertEquals(1, byId.get("setup-helper").versionNo());
        assertEquals(1, byId.get("setup-helper").fileCount());
    }

    @Test
    @DisplayName("目录那次查询的行数进 gauge；读失败保留上一次的好值不写 0")
    void catalogReportsQueryRowsAndKeepsLastGoodValue() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.globalRegistry.add(registry);
        try {
            store.create("rows-a", "甲", body("甲"), false);
            store.create("rows-b", "乙", body("乙"), false);

            assertEquals(2, store.catalog().size());
            assertEquals(2D, catalogRows(registry));

            try (Statement statement = database.connection().createStatement()) {
                statement.executeUpdate("DROP TABLE skill_definition");
            }

            assertThrows(PayrollUnavailableException.class, store::catalog);
            // gauge 报的是最后一次成功读取的行数：写失败记成 0 与库里真的没技能长得一样。
            assertEquals(2D, catalogRows(registry));
        } finally {
            Metrics.globalRegistry.remove(registry);
        }
    }

    private double catalogRows(MeterRegistry registry) {
        Gauge gauge =
                registry.find(SkillMetrics.CATALOG_QUERY_ROWS).tag("source", "db").gauge();
        assertNotNull(gauge, "目录读取没有报出行数，§15 的单查询门禁没有数据");
        return gauge.value();
    }

    @Test
    @DisplayName("未落库的内置技能不能删除，也不能不带 overwrite 就导入")
    void builtinNeedsExplicitOverwrite() {
        PayrollException error =
                assertThrows(PayrollException.class, () -> store.delete(RoleSkills.VALIDATOR));
        assertTrue(error.getMessage().contains("内置技能"), error.getMessage());
        assertThrows(
                PayrollConflictException.class,
                () ->
                        store.importProject(
                                project(RoleSkills.VALIDATOR, body("内置覆盖"), List.of(), META), false));

        store.importProject(project(RoleSkills.VALIDATOR, body("内置覆盖"), List.of(), META), true);
        assertTrue(store.delete(RoleSkills.VALIDATOR));
        assertEquals(List.of(), store.catalog());
        assertEquals(NacosSkillRegistry.SOURCE_BUILTIN, store.detail(RoleSkills.VALIDATOR).source());
    }

    @Test
    @DisplayName("文件包停用：只写正文、已有清单不动、编辑文件直接报错")
    void killSwitchGuardsFileWriters() throws Exception {
        store.importProject(
                project("ks-helper", body("初版"), pack("references/a.md", "甲"), META), true);
        JdbcSkillStore disabled =
                new JdbcSkillStore(database, blobs, new SkillFileSettings(false, 4096, 8192));

        disabled.importProject(
                project("ks-helper", body("二版"), pack("references/b.md", "乙"), META), true);

        JdbcSkillStore.Row row = disabled.find("ks-helper").orElseThrow();
        assertEquals(body("二版"), disabled.bodyOf(row));
        assertEquals(List.of(), disabled.resourcesOf(row), "停用期间不装配文件包");
        assertEquals(1, row.resourceCount(), "已有清单必须保留，回滚开关不需要先清数据");
        assertEquals(1, scalar("SELECT COUNT(*) FROM skill_blob"));
        assertEquals(List.of(), disabled.detail("ks-helper").files());
        assertThrows(
                PayrollException.class,
                () -> disabled.replaceFiles("ks-helper", pack("references/c.md", "丙")));
        assertThrows(PayrollException.class, () -> disabled.deleteFile("ks-helper", "references/a.md"));
    }

    @Test
    @DisplayName("目录指纹逐次写入都变化，且带上库内最后一次写入时刻")
    void fingerprintFollowsEveryWrite() {
        JdbcSkillStore peer = new JdbcSkillStore(database, blobs, SkillFileSettings.defaults());
        SkillRuntimeReader.CatalogFingerprint empty = store.fingerprint();
        assertEquals(empty, peer.fingerprint());
        assertEquals(0L, empty.lastWriteMillis(), "空表没有写入时刻；轮询侧靠它把滞后截零");

        store.create("ag-helper", "甲", body("甲"), false);
        SkillRuntimeReader.CatalogFingerprint created = store.fingerprint();
        assertNotEquals(empty.revision(), created.revision());
        assertTrue(created.lastWriteMillis() > 0L);

        store.update("ag-helper", "甲", body("甲"), true);
        SkillRuntimeReader.CatalogFingerprint afterSwitchOnly = store.fingerprint();
        assertNotEquals(
                created.revision(),
                afterSwitchOnly.revision(),
                "只改执行开关也是一次失效，轮询必须看见");
        assertEquals(afterSwitchOnly.revision(), peer.fingerprint().revision());
    }

    @Test
    @DisplayName("版本号超过列宽直接报错，不静默截断")
    void overlongVersionFailsLoud() throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>(META);
        metadata.put("version", "1".repeat(40));
        assertThrows(
                PayrollException.class,
                () ->
                        store.importProject(
                                project("vl-helper", body("初版"), List.of(), metadata), true));
        assertEquals(0, scalar("SELECT COUNT(*) FROM skill_definition"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM skill_version"));
    }

    @Test
    @DisplayName("缺失技能只影响自己：正文与字节的读取不会回落其他存储")
    void missingRowsFailLoud() {
        assertThrows(PayrollException.class, () -> store.detail("ghost-helper"));
        assertThrows(PayrollException.class, () -> store.fileContent("ghost-helper", "references/a.md"));
        store.importProject(project("lost-helper", body("初版"), pack("references/a.md", "甲"), META), true);
        JdbcSkillStore.Row row = row("lost-helper");
        String sha = store.resourcesOf(row).get(0).sha256();
        blobs.delete(sha);
        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> store.contents(row.id(), store.resourcesOf(row)));
        // 文案带技能 id：整批读字节时抛的是最先撞上的那条技能，只有路径分不清是脏数据还是 blob 根指错。
        assertTrue(
                error.getMessage().contains("技能资源缺失：lost-helper/references/a.md"),
                error.getMessage());
    }

    private JdbcSkillStore.Row row(String id) {
        return store.find(id).orElseThrow(() -> new AssertionError("技能行缺失：" + id));
    }

    private SkillSummary summary(String id) {
        return store.list().stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("列表缺少技能：" + id));
    }

    /** 直查库，不走被测代码：派生列写歪了必须在这里露出来。 */
    private long scalar(String sql) throws Exception {
        try (Connection connection = database.connection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    private String bodyText(long versionId) throws Exception {
        try (Connection connection = database.connection();
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT body_md FROM skill_version WHERE version_id=" + versionId)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /**
     * 当前生效的版本序号。定义行只存 {@code current_version_id}，回滚到底「移动指针」还是
     * 「追加一条再把指针移过去」必须直查这一对表才看得出来。
     */
    private int currentVersionNo(String id) throws Exception {
        return (int)
                scalar(
                        "SELECT v.version_no FROM skill_definition d"
                                + " JOIN skill_version v ON v.version_id = d.current_version_id"
                                + " WHERE d.skill_id='"
                                + id
                                + "'");
    }

    /** 说明列必须直查：它既可能挂在新追加的版本行上，也可能补写在当前版本行上，走 DTO 看不出区别。 */
    private String noteOf(String id, int versionNo) throws Exception {
        return columnText(
                "SELECT change_note FROM skill_version WHERE skill_id='"
                        + id
                        + "' AND version_no="
                        + versionNo);
    }

    /** 定义行的一个文本列——溯源列写没写、写的是 NULL 还是空串，只有绕过被测代码才看得出来。 */
    private String definitionColumn(String id, String column) throws Exception {
        return columnText(
                "SELECT " + column + " FROM skill_definition WHERE skill_id='" + id + "'");
    }

    private String versionColumn(String id, int versionNo, String column) throws Exception {
        return columnText(
                "SELECT "
                        + column
                        + " FROM skill_version WHERE skill_id='"
                        + id
                        + "' AND version_no="
                        + versionNo);
    }

    private String columnText(String sql) throws Exception {
        try (Connection connection = database.connection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static List<String> ids(List<SkillSummary> summaries) {
        return summaries.stream().map(SkillSummary::id).toList();
    }

    private static List<String> paths(List<SkillFilePackage.Summary> summaries) {
        return summaries.stream().map(SkillFilePackage.Summary::path).toList();
    }

    private List<String> resourcePaths(JdbcSkillStore.Row row) {
        return store.resourcesOf(row).stream().map(JdbcSkillStore.ResourceRef::path).toList();
    }

    private static List<SkillFilePackage.SkillFile> files(Map<String, String> pack) {
        return pack.entrySet().stream()
                .map(entry -> new SkillFilePackage.SkillFile(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static List<SkillFilePackage.SkillFile> pack(String path, String content) {
        Map<String, String> single = new LinkedHashMap<>();
        single.put(path, content);
        return files(single);
    }

    private static List<SkillFilePackage.SkillFile> pack(String p1, String c1, String p2, String c2) {
        Map<String, String> pair = new LinkedHashMap<>();
        pair.put(p1, c1);
        pair.put(p2, c2);
        return files(pair);
    }

    private static int utf8Bytes(Map<String, String> pack) {
        return pack.values().stream().mapToInt(v -> v.getBytes(StandardCharsets.UTF_8).length).sum();
    }

    private static SkillProjectParser.ParsedProject project(
            String id, String body, List<SkillFilePackage.SkillFile> files, Map<String, Object> metadata) {
        return new SkillProjectParser.ParsedProject(
                id, metadata, "用于" + id + "的说明", body, files, "", 0L, List.of());
    }

    private static String body(String marker) {
        return "## 角色\n\n" + marker + "\n\n## 规则\n\n" + RULES.repeat(8);
    }

    /** 在第 {@code failOnPut} 次写介质时抛异常，用来制造事务中途失败；顺带记录被删过的指纹。 */
    private static final class FlakyBlobStore implements SkillBlobStore {

        private final SkillBlobStore delegate;
        private final int failOnPut;
        private final List<String> deleted = new ArrayList<>();
        private int puts;

        private FlakyBlobStore(Path root, int failOnPut) {
            this.delegate = new LocalDiskSkillBlobStore(root.toString(), 1024 * 1024);
            this.failOnPut = failOnPut;
        }

        @Override
        public BlobRef put(byte[] content, String mediaType) {
            puts++;
            if (puts == failOnPut) {
                throw new IllegalStateException("介质写入失败");
            }
            return delegate.put(content, mediaType);
        }

        @Override
        public void delete(String sha256) {
            deleted.add(sha256);
            delegate.delete(sha256);
        }

        @Override
        public Optional<byte[]> get(String sha256) {
            return delegate.get(sha256);
        }

        @Override
        public Map<String, byte[]> getAll(Collection<String> sha256s) {
            return delegate.getAll(sha256s);
        }

        @Override
        public boolean exists(String sha256) {
            return delegate.exists(sha256);
        }

        @Override
        public String location() {
            return delegate.location();
        }
    }
}
