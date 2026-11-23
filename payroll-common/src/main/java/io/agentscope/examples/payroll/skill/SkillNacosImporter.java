package io.agentscope.examples.payroll.skill;

import io.agentscope.examples.payroll.domain.PayrollUnavailableException;
import io.agentscope.examples.payroll.skill.JdbcSkillStore.Row;
import io.agentscope.examples.payroll.skill.SkillNacosSource.RemoteSkill;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nacos → 库的一次性导入器（规格 §11.2）。
 *
 * <p>规则落点：列表分页与解析在 {@link SkillNacosSource}；坏文件包只记问题不中断正文；逐技能一个事务
 * 由 {@link JdbcSkillStore#importProject} 保证；内置技能按 D10a 落库并置 {@code builtin = TRUE}
 * （存储侧现算，这里只负责给出 {@code overwrite=true}）。
 *
 * <p>§11.2 规则 4 的「已存在即跳过」在这里落在<b>整表</b>粒度：表非空就不再导入，而不是逐条跳过已有行。
 * 逐条跳过看着更"自愈"，但切库后 Nacos 配置按 §11.4 R2 不删，只要有人忘了关 {@code import-from-nacos}，
 * 管理台删掉的技能就会在下次重启被灌回来——静默复活比残缺表难查得多。残缺表由 §11.3 的
 * {@code missingInDb} 报出来，运维按门禁结论决定清表重导还是回滚。
 *
 * <p>整体不抛异常：本类由 {@code ApplicationRunner} 在启动期调用，一次导入失败不该让管理台起不来。
 * 结论全部进 {@link Report}，切换与否由 §11.3 的门禁裁决。
 */
public final class SkillNacosImporter {

    /** 导入报告里逐类问题最多列出的条数，超出只报总数——日志与 CLI 输出不能被上百条技能刷爆。 */
    private static final int MAX_LISTED = 20;

    private static final Logger log = LoggerFactory.getLogger(SkillNacosImporter.class);

    /**
     * @param executed {@code false} 表示本轮没有执行任何导入（表非空、Nacos 或库不可用）
     * @param note 未执行的原因，或执行后的补充说明
     */
    public record Report(
            boolean executed,
            List<String> imported,
            List<String> fileProblems,
            List<String> failures,
            String note) {

        static Report skippedAll(String reason) {
            return new Report(false, List.of(), List.of(), List.of(), reason);
        }

        /** 是否存在阻断切换的问题：正文导入失败才算，文件包问题按 §11.2 规则 2 不阻断。 */
        public boolean clean() {
            return failures.isEmpty();
        }

        public int total() {
            return imported.size() + failures.size();
        }

        public String summary() {
            if (!executed) {
                return "技能导入未执行：" + note;
            }
            StringBuilder text =
                    new StringBuilder()
                            .append("技能导入：新建 ")
                            .append(imported.size())
                            .append("，失败 ")
                            .append(failures.size());
            if (!fileProblems.isEmpty()) {
                text.append("，文件包问题 ").append(fileProblems.size());
            }
            if (!failures.isEmpty()) {
                text.append("（").append(sample(failures)).append("）");
            }
            if (!note.isBlank()) {
                text.append("；").append(note);
            }
            return text.toString();
        }

        private static String sample(List<String> items) {
            String joined =
                    String.join(
                            "、", items.size() <= MAX_LISTED ? items : items.subList(0, MAX_LISTED));
            return items.size() <= MAX_LISTED
                    ? joined
                    : joined + " 等 " + items.size() + " 个";
        }
    }

    private final SkillNacosSource source;
    private final JdbcSkillStore target;
    private final SkillRuntimeReader reader;

    public SkillNacosImporter(SkillNacosSource source, JdbcSkillStore target) {
        this(source, target, target);
    }

    /**
     * @param reader 判空以库的目录为准，不能拿 {@link SkillStore#list()} 代替：那个视图会把尚未落库的
     *     内置技能也算进去，于是首次导入永远被当成「表非空」
     */
    SkillNacosImporter(SkillNacosSource source, JdbcSkillStore target, SkillRuntimeReader reader) {
        if (source == null || target == null || reader == null) {
            throw new IllegalArgumentException("Nacos 来源、目标存储与读端口必填");
        }
        this.source = source;
        this.target = target;
        this.reader = reader;
    }

    /** 表非空即跳过（幂等）；空表时把 Nacos 全量技能导入库。 */
    public synchronized Report importAllIfEmpty() {
        List<Row> rows;
        try {
            rows = reader.catalog();
        } catch (PayrollUnavailableException e) {
            return log(Report.skippedAll("读取技能表失败：" + message(e)));
        }
        if (!rows.isEmpty()) {
            return log(
                    Report.skippedAll(
                            "skill_definition 已有 " + rows.size() + " 行，跳过导入（幂等）"));
        }
        List<RemoteSkill> remotes;
        try {
            remotes = source.readSkills();
        } catch (RuntimeException e) {
            return log(Report.skippedAll("读取 Nacos 技能失败：" + message(e)));
        }
        List<String> imported = new ArrayList<>();
        List<String> fileProblems = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (RemoteSkill remote : remotes) {
            if (!remote.fileProblem().isBlank()) {
                fileProblems.add(remote.id() + "：" + remote.fileProblem());
            }
            try {
                target.importProjectForMigration(
                        projectOf(remote), isBuiltin(remote.id()), remote.executionEnabled());
                imported.add(remote.id());
            } catch (RuntimeException e) {
                failures.add(remote.id() + "：" + message(e));
                log.warn(
                        "[Skill] 导入技能 {} 失败，继续下一个：{}",
                        remote.id(),
                        e.getClass().getSimpleName() + ": " + message(e));
            }
        }
        return log(new Report(true, imported, fileProblems, failures, source.location()));
    }

    private static Report log(Report report) {
        if (report.executed() && report.clean()) {
            log.info("[Skill] {}", report.summary());
        } else if (report.executed()) {
            log.error("[Skill] {}", report.summary());
        } else {
            log.warn("[Skill] {}", report.summary());
        }
        return report;
    }

    /** 内置技能允许首次落库（{@code overwrite} 只为绕过 {@code builtin} 保护）。 */
    private static boolean isBuiltin(String skillId) {
        return RoleSkills.ids().contains(skillId);
    }

    private static SkillProjectParser.ParsedProject projectOf(RemoteSkill remote) {
        return new SkillProjectParser.ParsedProject(
                remote.id(),
                remote.metadata(),
                remote.description(),
                remote.body(),
                remote.files(),
                "",
                SkillProjectParser.DEFAULT_DATA_MAX_BYTES,
                List.of());
    }

    private static String message(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
