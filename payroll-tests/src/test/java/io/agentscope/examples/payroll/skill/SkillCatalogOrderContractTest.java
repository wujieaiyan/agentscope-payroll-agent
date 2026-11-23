package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.examples.payroll.skill.JdbcSkillStore.Row;
import io.agentscope.examples.payroll.skill.SkillRuntimeReader.Runtime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 目录顺序契约（规格 §18 T-13）。
 *
 * <p>切库前后系统提示词里的技能列表必须逐 id 可比：Nacos 源按 id 字典序（
 * {@code NacosSkillRegistry} 的 {@code sorted(comparing(RemoteSkill::id))}），db 源按
 * {@code sort_order, skill_id}。顺序最终由 {@code JdbcSkillStore} 的 SQL 决定，因此这一层只锁一件事——
 * {@link DbSkillRepository} <b>不得再排一次</b>。仓储里任何一次 {@code TreeSet}/{@code HashMap} 重排
 * 都会让导入器算好的 {@code sort_order} 失效，而指纹又不含顺序，应用侧缓存看不出变化，只有提示词悄悄变了。
 *
 * <p>跨源的逐 id 一致性要等 P0-6 导入器落 {@code sort_order} 之后才能自动化，见 §18 T-13 的偏差说明。
 * {@code nameById()} 不在这条契约里：它只给工具组激活中间件查表，内置源返回的就是无序 map，
 * 硬锁顺序等于给一个没人依赖的性质上锁。
 */
class SkillCatalogOrderContractTest {

    /** 故意打乱：既非字典序，也非插入序能蒙对的样子。 */
    private static final List<String> STORE_ORDER =
            List.of("payroll-validator", "poetry-lyrics", "payroll-calculator", "df-helper");

    private final FixedOrderStore store = new FixedOrderStore();
    private final AtomicLong clock = new AtomicLong(0L);
    private final DbSkillRepository repository = new DbSkillRepository(store, 60_000L, clock::get);

    @Test
    @DisplayName("目录视图不重排：进提示词的三个入口给出同一顺序")
    void catalogViewsKeepStoreOrder() {
        assertEquals(STORE_ORDER, ids(repository.listManifests()));
        assertEquals(STORE_ORDER, repository.getAllSkillNames());
        assertEquals(STORE_ORDER, names(repository.getAllSkills()));
    }

    @Test
    @DisplayName("顺序跨目录重建稳定，不随 HashMap 抖动")
    void orderIsStableAcrossRebuilds() {
        List<String> first = ids(repository.listManifests());

        for (int round = 0; round < 5; round++) {
            clock.set(60_001L * (round + 1));
            repository.invalidate();
            assertEquals(first, ids(repository.listManifests()), "第 " + round + " 轮重建顺序变了");
            assertEquals(first, repository.getAllSkillNames());
        }
    }

    @Test
    @DisplayName("批量取正文按调用方顺序，不受目录顺序影响")
    void batchReadFollowsCallerOrder() {
        List<String> requested =
                List.of("payroll-calculator", "df-helper", "payroll-validator", "poetry-lyrics");

        assertEquals(requested, names(repository.getSkills(requested)));
    }

    private static List<String> ids(List<SkillManifest> manifests) {
        return manifests.stream().map(SkillManifest::id).toList();
    }

    private static List<String> names(List<AgentSkill> skills) {
        return skills.stream().map(AgentSkill::getName).toList();
    }

    /** 只关心顺序的桩：目录行按 {@link #STORE_ORDER} 返回，正文给一段够装配的文本。 */
    private static final class FixedOrderStore implements SkillRuntimeReader {

        private List<Row> rows() {
            return STORE_ORDER.stream()
                    .map(
                            id ->
                                    new Row(
                                            id,
                                            "说明：" + id,
                                            List.of("payroll"),
                                            "1.0.0",
                                            false,
                                            false,
                                            SkillManifests.contentHash("## 角色\n" + id),
                                            ("## 角色\n" + id).length(),
                                            0,
                                            "",
                                            1L,
                                            1L,
                                            1_000L))
                    .toList();
        }

        @Override
        public List<Row> catalog() {
            return rows();
        }

        @Override
        public Optional<Row> find(String id) {
            return rows().stream().filter(row -> row.id().equals(id)).findFirst();
        }

        @Override
        public Map<String, Runtime> runtimeData(Collection<Row> rows) {
            Map<String, Runtime> results = new LinkedHashMap<>();
            for (Row row : rows) {
                results.put(
                        row.id(),
                        new Runtime(
                                "## 角色\n" + row.id(),
                                Map.of("description", row.description()),
                                Map.of()));
            }
            return results;
        }

        @Override
        public String location() {
            return "jdbc:h2:mem:order-test";
        }

        /** 顺序契约不比对指纹；给一个只由行集决定的稳定值即可。 */
        @Override
        public CatalogFingerprint fingerprint() {
            StringBuilder raw = new StringBuilder();
            rows().forEach(
                    row -> raw.append(row.id()).append(':').append(row.revision()).append('\n'));
            return new CatalogFingerprint(SkillManifests.contentHash(raw.toString()), 1_000L);
        }
    }
}
