package io.agentscope.examples.payroll.skill;

import io.agentscope.examples.payroll.skill.JdbcSkillStore.Row;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 运行期读路径需要的最小读端口（规格 §7）。
 *
 * <p>与 {@link SkillStore} 的分工：那个端口服务管理台（要 DTO、要写、要逐条读文件），本端口只服务
 * {@link DbSkillRepository}（要目录、要一次装配全部正文与资源）。拆成两个端口的第二个理由是可测性：
 * {@link JdbcSkillStore} 是 {@code final} 的，没有端口就没有「目录只查一次、且不碰字节」的桩可打
 * （§18 T-03）。
 *
 * <p>技能文件包开关（{@code PAYROLL_SKILL_FILES_ENABLED}）由实现侧统一裁决：关闭时
 * {@link #runtimeData(Collection)} 不返回资源，行上的 {@code resourceHash} 也归零。两个值必须同进同退
 * ——指纹进智能体缓存，若目录说有资源而装配出来没有，缓存就对不上内容。
 */
public interface SkillRuntimeReader {

    /** 全表目录：单查询、不含正文与字节，顺序为 {@code sort_order, skill_id}（§5.2 红线列）。 */
    List<Row> catalog();

    /** 单行目录，供快照尚未覆盖到的 id 补查（跨节点刚创建的技能不必等 TTL）。 */
    Optional<Row> find(String id);

    /**
     * 批量装配多个技能当前版本的运行期数据。
     *
     * <p>实现必须把整批正文与整批字节各读一次：逐技能往返就是 §2.2-10 要在本次迁移里消掉的成本。
     *
     * @param rows 来自 {@link #catalog()} 或 {@link #find(String)} 的行
     * @return 以技能 id 为键；定义行还在、版本行已被并发删除的技能不出现在结果里
     */
    Map<String, Runtime> runtimeData(Collection<Row> rows);

    /** 元数据存储位置，供 {@code repositoryInfo} 与排错展示，不含凭据。 */
    String location();

    /**
     * 目录指纹，供 §10 的跨实例失效轮询比对。
     *
     * <p>与 {@link #catalog()} 分开：轮询器每 15s 跑一次，只要一个哈希和一个时间戳，
     * 拿全表目录做比对等于把「省下的正文查询」又还回去。
     */
    CatalogFingerprint fingerprint();

    /**
     * 一次比对的输入。
     *
     * @param revision 全表 {@code skill_id:revision} 排序后的哈希，任一行写入都会变
     * @param lastWriteMillis 全表 {@code MAX(updated_at)}，空表为 0。来自库的时钟，
     *     与 {@link System#currentTimeMillis()} 不同源，只能用来估算滞后，不能用来排序
     */
    record CatalogFingerprint(String revision, long lastWriteMillis) {}

    /**
     * 装配一个 {@code AgentSkill} 所需的全部内容。
     *
     * @param body 当前版本正文（已去 frontmatter 的形态由写入侧保证）
     * @param metadata frontmatter 元信息（列上的 {@code tags}/{@code version} 与
     *     {@code meta_json} 合并后的视图）
     * @param resources 路径 → 文本内容，顺序与清单一致
     */
    record Runtime(String body, Map<String, Object> metadata, Map<String, String> resources) {}
}
