package io.agentscope.examples.payroll.devteam;

/**
 * 交付补丁引用：G3 打开时锚定产物版本与 diff hash，执行前再次核对，防止在漂移内容上建分支。
 */
public record DevDeliveryPatch(String taskId, String artifactType, int version, String diffHash) {

    public DevDeliveryPatch {
        taskId = taskId == null ? "" : taskId.trim();
        artifactType = artifactType == null ? "" : artifactType.trim();
        diffHash = diffHash == null ? "" : diffHash.trim();
        if (taskId.isEmpty() || artifactType.isEmpty()) {
            throw new IllegalArgumentException("交付补丁需要任务与产物类型");
        }
        if (version < 1) {
            throw new IllegalArgumentException("交付补丁版本必须大于 0");
        }
    }

    public String key() {
        return taskId + "|" + artifactType;
    }
}
