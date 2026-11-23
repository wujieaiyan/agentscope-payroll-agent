package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DevRoleContractTest {

    private static final List<String> ROLES =
            List.of(
                    DevPathPolicy.ROLE_PRODUCT,
                    DevPathPolicy.ROLE_ARCHITECT,
                    DevPathPolicy.ROLE_MANAGER,
                    DevPathPolicy.ROLE_IMPLEMENTER,
                    DevPathPolicy.ROLE_FRONTEND,
                    DevPathPolicy.ROLE_DBA,
                    DevPathPolicy.ROLE_DEVOPS,
                    DevPathPolicy.ROLE_QA,
                    DevPathPolicy.ROLE_REVIEWER);

    @Test
    @DisplayName("九角色都有固定契约：输入、产出与禁止项均非空")
    void nineRolesHaveContracts() {
        for (String role : ROLES) {
            Optional<DevRoleContract> contract = DevRoleContract.of(role);
            assertTrue(contract.isPresent(), role);
            assertFalse(contract.get().inputs().isEmpty(), role + " 缺少输入");
            assertFalse(contract.get().outputs().isEmpty(), role + " 缺少产出");
            assertFalse(contract.get().prohibitions().isEmpty(), role + " 缺少禁止项");
        }
        assertTrue(DevRoleContract.of("project-hacker").isEmpty());
        assertTrue(DevRoleContract.of(null).isEmpty());
    }

    @Test
    @DisplayName("提示词契约段包含角色、产出、文件权限与禁止项")
    void briefingCoversContractFields() {
        String briefing = DevRoleContract.of(DevPathPolicy.ROLE_IMPLEMENTER).orElseThrow().renderBriefing();

        assertTrue(briefing.contains("## 开发域角色契约"), briefing);
        assertTrue(briefing.contains("后端开发"), briefing);
        assertTrue(briefing.contains("服务端补丁提案"), briefing);
        assertTrue(briefing.contains("payroll-*/src/**"), briefing);
        assertTrue(briefing.contains("自动 commit/push"), briefing);
    }

    @Test
    @DisplayName("产品经理契约明确不授予文件系统，只读角色标注无写入")
    void briefingReflectsFileScope() {
        String product = DevRoleContract.of(DevPathPolicy.ROLE_PRODUCT).orElseThrow().renderBriefing();
        assertTrue(product.contains("不授予文件系统访问"), product);

        String reviewer = DevRoleContract.of(DevPathPolicy.ROLE_REVIEWER).orElseThrow().renderBriefing();
        assertTrue(reviewer.contains("只读 仓库内（黑名单除外）"), reviewer);
        assertTrue(reviewer.contains("写入 无写入"), reviewer);
    }

    @Test
    @DisplayName("交接单按上游产物标注已提供与缺失")
    void handoffMarksProvidedAndMissingInputs() {
        String handoff =
                DevRoleContract.of(DevPathPolicy.ROLE_MANAGER)
                        .orElseThrow()
                        .renderHandoff(Map.of("requirements.md", "已确认 12 条需求"));

        assertTrue(handoff.contains("- [x] requirements.md：已确认 12 条需求"), handoff);
        assertTrue(handoff.contains("- [ ] architecture.md（缺失，需上游补齐）"), handoff);
        assertTrue(handoff.contains("tasks.json"), handoff);
        assertTrue(handoff.contains("禁止：修改业务代码"), handoff);
    }

    @Test
    @DisplayName("交接单在无产物时全部标缺失")
    void handoffWithoutArtifactsMarksEverythingMissing() {
        DevRoleContract contract = DevRoleContract.of(DevPathPolicy.ROLE_QA).orElseThrow();
        String handoff = contract.renderHandoff(null);

        assertEquals(
                contract.inputs().size(),
                handoff.lines().filter(line -> line.startsWith("- [ ]")).count());
        assertTrue(handoff.contains("test-report.md"), handoff);
    }
}
