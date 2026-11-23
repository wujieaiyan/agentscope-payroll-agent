package io.agentscope.examples.payroll.config;

/**
 * 部署模式：single 保留单机降级体验，cluster 下 Redis 相关能力按硬依赖快速失败。
 *
 * <p>cluster 模式不再回退到进程内租约/事件/配额，避免两个节点各跑一份导致重复执行与
 * 重复计费；Redis 故障由 readiness 摘流，而不是悄悄降级。
 */
public enum DeploymentMode {
    SINGLE("single"),
    CLUSTER("cluster");

    private final String value;

    DeploymentMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean cluster() {
        return this == CLUSTER;
    }

    public static DeploymentMode parse(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase();
        if (value.isEmpty() || "single".equals(value)) {
            return SINGLE;
        }
        if ("cluster".equals(value)) {
            return CLUSTER;
        }
        throw new IllegalArgumentException(
                "不支持的部署模式：" + raw + "（可选 single / cluster）");
    }
}
