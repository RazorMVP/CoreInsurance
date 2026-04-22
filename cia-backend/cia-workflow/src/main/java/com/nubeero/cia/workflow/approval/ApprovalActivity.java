package com.nubeero.cia.workflow.approval;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface ApprovalActivity {

    void notifyApprovers(ApprovalRequest request);

    void finaliseApproval(String entityType, String entityId, String tenantId,
                          boolean approved, String approverId, String comments);
}
