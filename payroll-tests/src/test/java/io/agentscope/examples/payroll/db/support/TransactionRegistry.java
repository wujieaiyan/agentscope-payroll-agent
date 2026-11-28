package io.agentscope.examples.payroll.db.support;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * docs/mybatis-migration-prd.md §5.0.1 的 29 行事务登记表（27 L2 + 2 L1）。
 *
 * <p>这是唯一权威副本：文档里的表与本文件必须逐条对应。{@code migrated=false} 表示该
 * store 尚未迁到 MyBatis，此时给它加 {@code @Transactional} 是错的——Spring 事务绑定的是
 * 适配器连接，而 store 仍走 {@code PayrollDatabase.connection()} 自己的连接。
 * 注解必须与该 store 的 Mapper 改造同一提交，并把对应行的 migrated 改成 true。
 */
public final class TransactionRegistry {

    public enum Level {
        /** 跨 store 级联，只在服务层标注。 */
        L1,
        /** store 方法内多条语句同成败。 */
        L2
    }

    public record Entry(
            String batch, String store, String method, Level level, String note, boolean migrated) {

        public String key() {
            return store + '#' + method;
        }
    }

    private static final String DB = "io.agentscope.examples.payroll.db.";
    private static final String SKILL = "io.agentscope.examples.payroll.skill.";

    public static final List<Entry> ENTRIES =
            List.of(
                    entry("P1", DB + "H2ChatFeedbackStore", "upsert", Level.L2, "先查存在性 → INSERT / UPDATE"),
                    entry("P1", DB + "DevTaskStore", "replacePlan", Level.L2, "DELETE 全量 → 批量插入；失败即计划被清空"),
                    entry("P1", DB + "DevArtifactStore", "save", Level.L2, "nextVersion SELECT → INSERT"),
                    entry("P2", DB + "H2ChatTurnStore", "createWithUserMessage", Level.L2, "用户消息 → 轮次 → 会话时间"),
                    entry("P2", DB + "H2ChatTurnStore", "nextFencing", Level.L2, "SELECT 最大值 → UPDATE/INSERT 计数器 → 回读"),
                    entry("P2", DB + "H2ChatStore", "appendAssistant", Level.L2, "消息 → 提案 / 追溯 / 引用"),
                    entry("P2", DB + "H2ChatStore", "appendTraces", Level.L2, "循环 INSERT 追溯"),
                    entry("P2", DB + "H2ChatStore", "delete", Level.L1, "上移 ChatDataService；4 个 deleteForSession + 会话"),
                    entry("P2", DB + "H2ChatStore", "clear", Level.L1, "上移 ChatDataService；现状无事务，语义改为全成全败"),
                    entry("P2", DB + "ChatAttachmentStore", "bind", Level.L2, "循环条件 UPDATE"),
                    entry("P2", DB + "H2AgentTeamStore", "markPrimary", Level.L2, "SELECT 运行 → 清主标记 → 置主标记"),
                    entry("P2", DB + "H2PayrollRunStore", "createDraft", Level.L2, "批次 → 明细 → 失败行"),
                    entry("P2", DB + "H2PayrollRunStore", "recordEmailResults", Level.L2, "明细批量 UPDATE → email_deliveries 批量 INSERT"),
                    entry("P2", DB + "H2ApprovalStore", "createFlow", Level.L2, "INSERT 链 → 原子替换节点"),
                    entry("P2", DB + "H2ApprovalStore", "updateFlow", Level.L2, "UPDATE 链 → 原子替换节点"),
                    entry("P2", DB + "H2ApprovalStore", "deleteFlow", Level.L2, "DELETE 节点 → DELETE 链"),
                    entry("P2", DB + "H2AdminRepository", "updateDepartment", Level.L2, "UPDATE 员工部门名 → UPDATE departments"),
                    entry("P2", DB + "H2UserProfileStore", "upsert", Level.L2, "upsert 条目 → 回读"),
                    entry("P2", DB + "H2UserProfileStore", "purge", Level.L2, "DELETE 条目 → DELETE 抽取记录"),
                    entry("P2", DB + "H2UserProfileStore", "complete", Level.L2, "循环 upsert → 置抽取记录 DONE"),
                    entry("P2", DB + "JdbcAgentDefinitionRepository", "save", Level.L2, "lockRevisions → INSERT / UPDATE"),
                    entry("P2", DB + "JdbcAgentDefinitionRepository", "delete", Level.L2, "lockRevisions → DELETE"),
                    entry("P2", DB + "JdbcAgentDefinitionRepository", "importIfEmpty", Level.L2, "lockRevisions → 循环 INSERT"),
                    entry("P4", SKILL + "JdbcSkillStore", "rollback", Level.L2, "锁行 → 追加版本 → 重建清单"),
                    entry("P4", SKILL + "JdbcSkillStore", "replaceFiles", Level.L2, "锁行 → 重建清单与 blob 指针"),
                    entry("P4", SKILL + "JdbcSkillStore", "deleteFile", Level.L2, "锁行 → 删清单行 → 刷新定义"),
                    entry("P4", SKILL + "JdbcSkillStore", "writeFile", Level.L2, "锁行 → 删旧行 → 写新行 → 刷新定义"),
                    entry("P4", SKILL + "JdbcSkillStore", "delete", Level.L2, "删清单 → 删版本 → 删定义"),
                    entry("P4", SKILL + "JdbcSkillStore", "write", Level.L2, "私有方法；P4 先拆公开入口再标注"));

    private TransactionRegistry() {
    }

    /** 已迁到 MyBatis、允许（且必须）携带 @Transactional 的方法。 */
    public static Set<String> migratedKeys() {
        return ENTRIES.stream()
                .filter(Entry::migrated)
                .map(Entry::key)
                .collect(Collectors.toUnmodifiableSet());
    }

    public static Set<String> allKeys() {
        return ENTRIES.stream().map(Entry::key).collect(Collectors.toUnmodifiableSet());
    }

    private static Entry entry(String batch, String store, String method, Level level, String note) {
        return new Entry(batch, store, method, level, note, false);
    }
}