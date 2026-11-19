package io.agentscope.examples.payroll.devteam;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 研发团队九角色的固定交接契约（P0-0）：输入、产物、禁止项与文件权限。
 *
 * <p>角色技能负责「怎么做」，本契约负责「拿什么、交什么、不许做什么」，两者一起拼进
 * 系统提示词；下游交接与产物中心按同一份字段读取，避免各角色自行发明格式。
 */
public record DevRoleContract(
        String roleId,
        String displayName,
        List<String> inputs,
        List<String> outputs,
        List<String> prohibitions) {

    private static final Map<String, DevRoleContract> CONTRACTS =
            Map.ofEntries(
                    Map.entry(
                            DevPathPolicy.ROLE_PRODUCT,
                            new DevRoleContract(
                                    DevPathPolicy.ROLE_PRODUCT,
                                    "产品经理",
                                    List.of("用户原话", "历史对话", "产品文档", "知识库"),
                                    List.of(
                                            "requirements.md（需求分析：目标/用户/场景/范围/非目标）",
                                            "prd.md（用户故事、边界、验收标准、歧义点）"),
                                    List.of("技术选型或架构决策", "拆分开发任务", "修改代码"))),
                    Map.entry(
                            DevPathPolicy.ROLE_ARCHITECT,
                            new DevRoleContract(
                                    DevPathPolicy.ROLE_ARCHITECT,
                                    "技术总监",
                                    List.of(
                                            "requirements.md / prd.md",
                                            "仓库结构与现有约定（AGENTS/README/测试基线）"),
                                    List.of(
                                            "architecture.md（路线图、备选方案、风险与回滚点）",
                                            "interfaces.md（接口契约、数据模型与部署拓扑）"),
                                    List.of("拆分到人或任务颗粒度", "直接生成代码补丁"))),
                    Map.entry(
                            DevPathPolicy.ROLE_MANAGER,
                            new DevRoleContract(
                                    DevPathPolicy.ROLE_MANAGER,
                                    "项目经理",
                                    List.of("requirements.md", "architecture.md", "团队能力清单"),
                                    List.of(
                                            "tasks.json（任务、负责人、依赖、输入、输出、验收）",
                                            "delivery-plan.md（排期与里程碑）"),
                                    List.of("修改业务代码", "替开发做实现决策", "跳过产品/总监确认"))),
                    Map.entry(
                            DevPathPolicy.ROLE_IMPLEMENTER,
                            new DevRoleContract(
                                    DevPathPolicy.ROLE_IMPLEMENTER,
                                    "后端开发",
                                    List.of("任务项", "架构约束", "接口契约", "相关代码与测试"),
                                    List.of(
                                            "服务端补丁提案（unified diff）",
                                            "变更说明",
                                            "自测结果"),
                                    List.of(
                                            "触碰 .git/、data/、密钥文件",
                                            "自动 commit/push",
                                            "擅自改动接口契约"))),
                    Map.entry(
                            DevPathPolicy.ROLE_FRONTEND,
                            new DevRoleContract(
                                    DevPathPolicy.ROLE_FRONTEND,
                                    "前端工程师",
                                    List.of("任务项", "interfaces.md", "设计/交互约束", "现有组件"),
                                    List.of(
                                            "前端补丁提案（unified diff）",
                                            "变更说明",
                                            "npm run build 结果"),
                                    List.of(
                                            "修改 frontend/node_modules/、frontend/dist/",
                                            "改动后端代码或接口契约来迁就实现",
                                            "硬编码密钥或调试后门"))),
                    Map.entry(
                            DevPathPolicy.ROLE_DBA,
                            new DevRoleContract(
                                    DevPathPolicy.ROLE_DBA,
                                    "数据库工程师",
                                    List.of(
                                            "任务项",
                                            "interfaces.md 的数据契约",
                                            "现有 db/migration 与表结构"),
                                    List.of(
                                            "迁移脚本补丁（Flyway + H2 内联 DDL）",
                                            "影响评估（锁表/耗时/回滚）",
                                            "dry-run 结果"),
                                    List.of(
                                            "连接生产库或共享库",
                                            "修改业务代码与 data/ 文件",
                                            "在脚本或产物中写死账号密码"))),
                    Map.entry(
                            DevPathPolicy.ROLE_DEVOPS,
                            new DevRoleContract(
                                    DevPathPolicy.ROLE_DEVOPS,
                                    "运维工程师",
                                    List.of("任务项", "部署拓扑", "现有 docker/deploy 配置"),
                                    List.of(
                                            "部署/配置补丁（docker、deploy、scripts、CI）",
                                            "镜像构建与启动校验结果",
                                            "rollback.md"),
                                    List.of(
                                            "执行真实发布",
                                            "改主机网络/防火墙",
                                            "静默改变端口与启动方式",
                                            "在配置或手册中写明文口令"))),
                    Map.entry(
                            DevPathPolicy.ROLE_QA,
                            new DevRoleContract(
                                    DevPathPolicy.ROLE_QA,
                                    "测试工程师",
                                    List.of("requirements.md", "任务项", "全部补丁", "测试基线"),
                                    List.of("test-plan.md", "test-report.md（通过/失败、日志、复现步骤）"),
                                    List.of("修改业务代码来让测试通过"))),
                    Map.entry(
                            DevPathPolicy.ROLE_REVIEWER,
                            new DevRoleContract(
                                    DevPathPolicy.ROLE_REVIEWER,
                                    "代码审查工程师",
                                    List.of(
                                            "interfaces.md",
                                            "tasks.json",
                                            "四类补丁 diff（后端/前端/数据库/运维）",
                                            "test-report.md"),
                                    List.of(
                                            "review-report.md（逐文件：通过/建议/阻断 + 文件:行号）"),
                                    List.of(
                                            "直接修改代码或补丁",
                                            "代替测试执行",
                                            "放行构建失败或含越权路径/明文凭据的补丁"))));

    public DevRoleContract {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        prohibitions = prohibitions == null ? List.of() : List.copyOf(prohibitions);
    }

    public static Optional<DevRoleContract> of(String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(CONTRACTS.get(roleId.trim().toLowerCase(java.util.Locale.ROOT)));
    }

    /** 拼进系统提示词的固定段：角色、输入、产出、文件权限与禁止项。 */
    public String renderBriefing() {
        StringBuilder text = new StringBuilder("## 开发域角色契约（必须遵守）\n");
        text.append("- 角色：")
                .append(displayName)
                .append("（")
                .append(roleId)
                .append("）\n");
        text.append("- 输入：").append(String.join("、", inputs)).append('\n');
        text.append("- 产出：").append(String.join("、", outputs)).append('\n');
        text.append("- 文件权限：").append(DevPathPolicy.describeScope(roleId)).append('\n');
        text.append("- 禁止：").append(String.join("、", prohibitions)).append('\n');
        text.append("- 交接：产出按固定文件名落产物库，并在结尾单列「假设/待确认」与「下游输入」。");
        return text.toString();
    }

    /**
     * 交接单模板：按上游产物生成「已提供/缺失」清单，供项目经理分派与产物中心展示。
     *
     * @param artifacts 键为输入文件名，值为内容摘要；缺失的输入不出现在 map 中
     */
    public String renderHandoff(Map<String, String> artifacts) {
        Map<String, String> provided = artifacts == null ? Map.of() : artifacts;
        StringBuilder text = new StringBuilder("## 交接单：").append(displayName).append('\n');
        text.append("### 输入\n");
        for (String input : inputs) {
            String summary = lookup(provided, input);
            if (summary == null || summary.isBlank()) {
                text.append("- [ ] ").append(input).append("（缺失，需上游补齐）\n");
            } else {
                text.append("- [x] ").append(input).append("：").append(summary).append('\n');
            }
        }
        text.append("### 需要产出\n");
        outputs.forEach(output -> text.append("- ").append(output).append('\n'));
        text.append("### 约束\n- ").append(DevPathPolicy.describeScope(roleId)).append('\n');
        prohibitions.forEach(item -> text.append("- 禁止：").append(item).append('\n'));
        return text.toString();
    }

    /** 输入项文本里出现某个产物名即视为已提供，例如「interfaces.md 的数据契约」。 */
    private static String lookup(Map<String, String> provided, String input) {
        for (Map.Entry<String, String> entry : provided.entrySet()) {
            String key = entry.getKey();
            if (key != null && !key.isBlank() && input.contains(key)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
