package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.domain.PayrollException;

/**
 * 开发域两维权限配置（A1）：沙箱模式 × 审批策略，二者正交。
 *
 * <p>启动时校验组合合法性：{@code workspace-write + auto} 直接失败；写模式要求编码面模型
 * 已配置且有效上下文 ≥ 32k，否则降级为只读并记录原因，绝不静默用控制面模型生成补丁。
 *
 * @param mode 实际生效的沙箱模式（可能因模型不可用而降级）
 * @param approval 生效的审批策略
 * @param requestedMode 配置中声明的模式，用于审计对照
 * @param model 编码面模型配置
 * @param disabledReason 写模式被关闭或降级的原因；空串表示无
 */
public record DevTeamSettings(
        DevTeamMode mode,
        DevApprovalPolicy approval,
        DevTeamMode requestedMode,
        DevModelSettings model,
        String disabledReason) {

    public DevTeamSettings {
        requestedMode = requestedMode == null ? (mode == null ? DevTeamMode.READ_ONLY : mode) : requestedMode;
        mode = mode == null ? DevTeamMode.READ_ONLY : mode;
        if (approval == null) {
            approval = mode.writable() ? DevApprovalPolicy.BATCH : DevApprovalPolicy.AUTO;
        }
        if (mode.writable() && approval == DevApprovalPolicy.AUTO) {
            throw new PayrollException(
                    "非法的开发域权限组合：workspace-write 不允许 approval=auto；"
                            + "请改用 batch 或 per-task");
        }
        model = model == null ? new DevModelSettings("", null, 0, 0, 0D, 0) : model;
        String reason = disabledReason == null ? "" : disabledReason.trim();
        if (mode.writable()) {
            var modelReason = model.disabledReason();
            if (modelReason.isPresent()) {
                mode = DevTeamMode.READ_ONLY;
                reason = modelReason.get() + "，开发域已降级为 read-only";
            }
        }
        disabledReason = reason;
    }

    /** 按字符串配置构造并校验；mode/approval 为空时按默认值处理。 */
    public static DevTeamSettings of(
            String mode, String approval, DevModelSettings model) {
        DevTeamMode parsedMode = DevTeamMode.parse(mode);
        DevApprovalPolicy parsedApproval =
                approval == null || approval.isBlank() ? null : DevApprovalPolicy.parse(approval);
        return new DevTeamSettings(parsedMode, parsedApproval, parsedMode, model, "");
    }

    public static DevTeamSettings fromEnvironment(String mode, String approval) {
        return of(mode, approval, DevModelSettings.fromEnvironment());
    }

    /** 写模式实际生效：read-only 下恒为 false。 */
    public boolean writeEnabled() {
        return mode.writable();
    }

    public boolean degraded() {
        return requestedMode.writable() && !mode.writable();
    }
}
