package io.agentscope.examples.payroll.devteam;

import java.time.LocalDateTime;
import java.util.List;

/**
 * G3 交付申请（P2-1）：冻结 G2 已放行的补丁版本、基线 commit 与 PR 描述，
 * 由人工确认后在本地建分支并提交；不推送、不持有远端凭证。
 */
public record DevDelivery(
        String deliveryId,
        String planId,
        String branch,
        String baseCommit,
        String prTitle,
        String prBody,
        DevDeliveryStatus status,
        List<DevDeliveryPatch> patches,
        String commitId,
        String decidedBy,
        String note,
        LocalDateTime createdAt,
        LocalDateTime decidedAt) {

    public DevDelivery {
        deliveryId = deliveryId == null ? "" : deliveryId.trim();
        planId = planId == null ? "" : planId.trim();
        branch = branch == null ? "" : branch.trim();
        baseCommit = baseCommit == null ? "" : baseCommit.trim();
        prTitle = prTitle == null ? "" : prTitle.trim();
        prBody = prBody == null ? "" : prBody;
        status = status == null ? DevDeliveryStatus.PENDING : status;
        patches = patches == null ? List.of() : List.copyOf(patches);
        commitId = commitId == null ? "" : commitId.trim();
        decidedBy = decidedBy == null ? "" : decidedBy.trim();
        note = note == null ? "" : note.trim();
    }

    public boolean pending() {
        return status == DevDeliveryStatus.PENDING;
    }
}
