package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.examples.payroll.skill.SkillManifest;
import io.agentscope.examples.payroll.skill.SkillManifests;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 技能规模基准（规格 4.7）：10 / 100 / 1000 技能 × 绑定 3 / 50 / 200。
 *
 * <p>只做功能性断言（覆盖数、提示词上界、目录上限），耗时与内存仅打印供对比；不断言
 * 绝对毫秒数，避免 CI 抖动。绑定数超过总数时跳过该组合。
 */
class SkillScaleBenchmarkTest {

    private static final int WINDOW = 32_768;

    private static final int PREDICT = 2_048;

    private static final String FOCUS = "薪酬核算 个税申报 工资条";

    private static final String RULE_BODY =
            "规则说明：本条技能描述如何按公司制度处理薪酬场景，包含计算口径、校验步骤与常见错误提示。"
                    + "执行时先核对员工参保城市与生效月份，再按工资项目逐项计算，最后核对合计与个税申报口径。";

    @Test
    @DisplayName("规模基准：目录构建、单轮技能耗时、内存增量与提示词字符数")
    void reportsScaleTable() {
        int[] totals = {10, 100, 1_000};
        int[] bindings = {3, 50, 200};

        System.out.println("| 技能总数 | 绑定数 | 目录构建ms | 单轮技能ms | 内存增量KB | 提示词字符 | 注入 | 目录 |");
        for (int total : totals) {
            long heapBefore = usedHeapBytes();
            List<AgentSkill> skills = buildSkills(total);
            ManifestTiming catalog = timeManifests(skills);
            long heapAfter = usedHeapBytes();
            assertEquals(total, catalog.manifests().size());

            for (int binding : bindings) {
                if (binding > total) {
                    continue;
                }
                List<AgentSkill> bound = skills.subList(0, binding);
                TurnTiming turn = timeTurn(bound);

                assertEquals(
                        binding,
                        turn.result().injectedIds().size() + turn.result().skippedIds().size(),
                        "注入+目录应覆盖全部绑定技能");
                assertTrue(
                        turn.result().block().length() <= turn.result().budgetTokens() * 3 + 8_000,
                        "提示词字符数应受预算约束：" + turn.result().block().length());
                long renderedCatalog =
                        turn.result().block().lines()
                                .filter(line -> line.startsWith("- ") && !line.contains("另有"))
                                .count();
                assertTrue(renderedCatalog <= 100, "混合目录最多渲染 100 条，实际 " + renderedCatalog);
                if (turn.result().catalogIds().size() > 100) {
                    assertTrue(
                            turn.result().block().contains("另有"),
                            "超过目录上限时应提示剩余技能数量");
                }

                System.out.printf(
                        "| %d | %d | %.2f | %.2f | %d | %d | %d | %d |%n",
                        total,
                        binding,
                        catalog.nanos() / 1_000_000.0,
                        turn.nanos() / 1_000_000.0,
                        (heapAfter - heapBefore) / 1024,
                        turn.result().block().length(),
                        turn.result().injectedIds().size(),
                        turn.result().catalogIds().size());
            }
        }
    }

    private record ManifestTiming(List<SkillManifest> manifests, long nanos) {}

    private record TurnTiming(SkillPromptBudget.Result result, long nanos) {}

    private static ManifestTiming timeManifests(List<AgentSkill> skills) {
        skills.stream().map(SkillManifests::fromSkill).toList();
        long started = System.nanoTime();
        List<SkillManifest> manifests = skills.stream().map(SkillManifests::fromSkill).toList();
        return new ManifestTiming(manifests, System.nanoTime() - started);
    }

    private static TurnTiming timeTurn(List<AgentSkill> bound) {
        SkillPromptBudget.build(bound, WINDOW, PREDICT, FOCUS);
        long started = System.nanoTime();
        SkillPromptBudget.Result result = SkillPromptBudget.build(bound, WINDOW, PREDICT, FOCUS);
        return new TurnTiming(result, System.nanoTime() - started);
    }

    private static List<AgentSkill> buildSkills(int total) {
        List<AgentSkill> skills = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            String name = "skill-" + i;
            String description = (i % 10 == 0 ? "个税申报与工资条核算规则 " : "通用技能说明 ") + "第" + i + "号";
            skills.add(
                    AgentSkill.builder()
                            .name(name)
                            .description(description)
                            .skillContent("## " + name + "\n" + RULE_BODY.repeat(16))
                            .source("bench")
                            .build());
        }
        return skills;
    }

    private static long usedHeapBytes() {
        System.gc();
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
