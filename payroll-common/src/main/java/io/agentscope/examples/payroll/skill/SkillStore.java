package io.agentscope.examples.payroll.skill;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.util.List;

/**
 * 管理面读写端口的技能存储。
 *
 * <p>管理台控制器、导入服务与健康检查只依赖本接口，不依赖具体存储；实现有 {@link NacosSkillRegistry}
 * 与 {@link JdbcSkillStore}（技能迁库 {@code docs/skill-storage-mysql-oss-spec.md} §8），由
 * {@code payroll.skills.source} 二选一，接口层不需要改动。
 *
 * <p>写方法沿用现网语义：端口的基础签名不带乐观并发控制，因为 Nacos 源的保存是「读整包 → 改 → 整体发布」。
 * 带 {@code expectedRevision} 的重载是默认方法，默认实现忽略令牌直接走基础签名——Nacos 源没有行级 revision
 * （规格 §8 末 P0-5 第 1 条）；{@link JdbcSkillStore} 覆盖这一组，令牌不一致时抛
 * {@link io.agentscope.examples.payroll.domain.PayrollConflictException}（409）。带 {@code operator} 的重载同理，
 * 但它是溯源列（{@code created_by}/{@code updated_by}）而非并发控制——Nacos 源没有这两列，只能丢弃。
 */
public interface SkillStore {

    /** 是否允许写操作；{@code false} 时所有写方法抛 {@link io.agentscope.examples.payroll.domain.PayrollException}。 */
    boolean writable();

    /** 存储后端是否在配置中启用；{@code /health} 的 nacos 组件据此报 SKIPPED。 */
    boolean enabled();

    /** 最近一次成功目录读取的 epoch 毫秒（0 = 从未成功）；只读快照，不触发后端请求。 */
    long lastSuccessfulListAt();

    /** 面向运维的存储位置描述，不含账号凭据。 */
    String location();

    /**
     * 列表阶段不返回正文与文件字节。{@link SkillSummary#fileCount()} 由实现决定：Nacos 源省掉整包读取固定给
     * 0，db 源直接读行上的 {@code resource_count}，展示层不能把它当成「一定加载过」。
     */
    List<SkillSummary> list();

    /**
     * 带版本号、写入者与数据需求判定的列表：管理台一屏要显示这四列，逐条调 {@link #detail(String)}
     * 会退化成 N+1。默认实现退回 {@link #list()}，三个新字段为 {@code null}——第三方/测试实现
     * 不改也能编译，界面把它们显示成「—」。
     */
    default List<SkillSummary> summaries() {
        return list();
    }

    SkillDetail detail(String id);

    /**
     * 当前版本的文件路径 → sha256。导入预览要按文件判断「新增 / 覆盖 / 不变」，只能逐路径比哈希；
     * 默认空映射表示「来源无法给出文件指纹」，调用方应把状态降级为「未知」而不是「全部新增」。
     */
    default java.util.Map<String, String> resourceHashes(String id) {
        return java.util.Map.of();
    }

    /**
     * 历史版本正文与清单（只读）。默认拒绝：Nacos 源整包发布、没有版本行，按序号取历史在语义上
     * 不成立；控制器把它翻译成 404，前端据此隐藏「对比」。
     */
    default SkillVersionContent versionContent(String id, int versionNo) {
        throw new io.agentscope.examples.payroll.domain.PayrollNotFoundException(
                "当前技能来源没有版本历史：v" + versionNo);
    }

    /** 文件列表；技能不存在或后端不可用时为空。 */
    List<SkillFilePackage.Summary> files(String id);

    /** 读取单个文件内容；路径越界与不存在分别报错。 */
    String fileContent(String id, String path);

    /** 全量替换文件包；空列表等于删除文件包。 */
    List<SkillFilePackage.Summary> replaceFiles(String id, List<SkillFilePackage.SkillFile> files);

    /**
     * 条件写：{@code expectedRevision} 取 {@link SkillSummary#revision()} 或 {@link SkillDetail#revision()}，
     * 与库内当前行不一致时 409。空串/{@code null} 等于不检查（不是「检查失败」），灰度期允许缺失。
     */
    default List<SkillFilePackage.Summary> replaceFiles(
            String id, List<SkillFilePackage.SkillFile> files, String expectedRevision) {
        return replaceFiles(id, files);
    }

    /**
     * 同上，外加操作者。{@code operator} 写进 {@code skill_definition.updated_by}——文件包写入不动版本行，
     * 但它是这行记录最后一次被人改动的时刻，漏写会让「最后修改人」停在很久以前那次正文保存上。
     *
     * <p>空串/{@code null} 表示操作者未知（启动期迁移、命令行、无审计上下文的内部写），落库写 NULL 而不是
     * 编一个 {@code "admin"}：把未知写成有名有姓的人，比留空更难追查。Nacos 源没有这两列，默认实现直接丢弃。
     */
    default List<SkillFilePackage.Summary> replaceFiles(
            String id,
            List<SkillFilePackage.SkillFile> files,
            String expectedRevision,
            String operator) {
        return replaceFiles(id, files, expectedRevision);
    }

    boolean deleteFile(String id, String path);

    default boolean deleteFile(String id, String path, String expectedRevision) {
        return deleteFile(id, path);
    }

    /** 同 {@link #replaceFiles(String, List, String, String)}：删单个文件也要记操作者。 */
    default boolean deleteFile(
            String id, String path, String expectedRevision, String operator) {
        return deleteFile(id, path, expectedRevision);
    }

    /**
     * 写单个文件（管理台编辑器的一次保存）。路径已存在则覆盖，不存在则新增；文件包仍受
     * {@link SkillFilePackage} 的目录/后缀/大小约束。
     *
     * <p>默认实现退回整包替换：先读出未改动的文件再原样写回，所以只能用 {@code expectedRevision}
     * 兜住并发，不能承诺「并发改不同文件互不冲突」。db 源覆写为单行 upsert，代价与
     * {@link #deleteFile} 相同，管理台因此只需发一次写请求、不读回整包。
     */
    default List<SkillFilePackage.Summary> writeFile(
            String id, String path, String content, String expectedRevision, String operator) {
        List<SkillFilePackage.SkillFile> all = new java.util.ArrayList<>();
        for (SkillFilePackage.Summary file : detail(id).files()) {
            if (file.path().equals(path)) {
                continue;
            }
            all.add(new SkillFilePackage.SkillFile(file.path(), fileContent(id, file.path())));
        }
        all.add(new SkillFilePackage.SkillFile(path, content));
        return replaceFiles(id, all, expectedRevision, operator);
    }

    SkillSummary create(String id, String description, String content, boolean executionEnabled);

    /** 带操作者的创建：{@code created_by}（版本行与定义行）取 {@code operator}。 */
    default SkillSummary create(
            String id,
            String description,
            String content,
            boolean executionEnabled,
            String operator) {
        return create(id, description, content, executionEnabled);
    }

    SkillSummary update(String id, String description, String content, boolean executionEnabled);

    default SkillSummary update(
            String id,
            String description,
            String content,
            boolean executionEnabled,
            String expectedRevision) {
        return update(id, description, content, executionEnabled);
    }

    /**
     * 同上，外加「变更说明」：它写进本次落库的那条版本行（规格 §9.2）。正文没变时不追加版本行，
     * 说明改挂到当前版本行上——丢掉用户刚填的话比覆盖一句旧话更糟。空串/{@code null} 表示不改。
     */
    default SkillSummary update(
            String id,
            String description,
            String content,
            boolean executionEnabled,
            String expectedRevision,
            String changeNote) {
        return update(id, description, content, executionEnabled, expectedRevision);
    }

    /**
     * 管理面保存的完整形状：令牌 + 变更说明 + 操作者，对应 {@code skill_version.created_by} 与
     * {@code skill_definition.created_by/updated_by}。
     *
     * <p>参数里有三个相邻的 {@code String}，串位照样编译得过、调用也不抛异常，只有落库的列会错——
     * 所以 {@code JdbcSkillStoreTest} 用三个互不相同的哨兵值一次验完
     * （{@code operatorWritesEveryProvenanceColumn}），任何串位都会红在那一条上。
     */
    default SkillSummary update(
            String id,
            String description,
            String content,
            boolean executionEnabled,
            String expectedRevision,
            String changeNote,
            String operator) {
        return update(id, description, content, executionEnabled, expectedRevision, changeNote);
    }

    /**
     * 版本时间线，按 {@code version_no} 倒序。默认空列表：Nacos 源是整包发布、没有版本行，
     * 空列表就是「这条技能在权威存储里没有可比对的历史」，不是读取失败。
     */
    default List<SkillVersion> versions(String id) {
        return List.of();
    }

    /**
     * 回滚 = 用目标版本的正文与清单<b>追加</b>一条新版本行并把指针移过去，不改历史（规格 §9.2）。
     *
     * <p>默认拒绝而不是返回假的摘要：Nacos 源没有版本行，任何「回滚」都只能把整包重新发布一遍，
     * 既不产生序号也说不清回到了哪一版——与其给一个语义不同的成功，不如明确报不支持。
     */
    default SkillSummary rollback(String id, int versionNo, String expectedRevision) {
        throw new PayrollException(
                "当前技能来源没有版本历史，不支持回滚（只有 db 源写 skill_version）");
    }

    /** 带操作者的回滚：新追加的那条版本行记的是<b>按下回滚的人</b>，不是原作者。 */
    default SkillSummary rollback(
            String id, int versionNo, String expectedRevision, String operator) {
        return rollback(id, versionNo, expectedRevision);
    }

    /**
     * 导入目录式技能项目：正文与元信息整体写入，并全量替换文件包。内置技能允许被覆盖，
     * 但 {@code overwrite=false} 时明确拒绝，不静默改写。
     */
    SkillSummary importProject(SkillProjectParser.ParsedProject project, boolean overwrite);

    /**
     * 管理台导入的完整形状：Git/ZIP 是整包覆盖，最容易盖掉别人刚改的一版，所以它也要带行级令牌；
     * 操作者进 {@code created_by}，让时间线能区分「谁从仓库灌的」与「谁在页面上手改的」。
     *
     * <p>注意与端口的基础签名区分：这里的 409 有两类——「技能已存在，覆盖需要显式确认」不带
     * {@code currentRevision}，令牌不一致才带（见规格 §13-6）。前端把两类混成一条提示就会把「先确认覆盖」
     * 当成「刷新重试」，等于替用户按下覆盖。
     */
    default SkillSummary importProject(
            SkillProjectParser.ParsedProject project,
            boolean overwrite,
            String expectedRevision,
            String operator) {
        return importProject(project, overwrite);
    }

    boolean delete(String id);

    default boolean delete(String id, String expectedRevision) {
        return delete(id);
    }
}
