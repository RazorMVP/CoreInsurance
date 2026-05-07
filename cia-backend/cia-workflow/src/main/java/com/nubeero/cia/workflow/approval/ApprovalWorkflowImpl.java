package com.nubeero.cia.workflow.approval;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

public class ApprovalWorkflowImpl implements ApprovalWorkflow {

    private final ApprovalActivity activity = Workflow.newActivityStub(
            ApprovalActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setMaximumAttempts(3)
                            .setInitialInterval(Duration.ofSeconds(5))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofMinutes(1))
                            .build())
                    .build());

    private ApprovalStatus status = ApprovalStatus.PENDING;
    private String approverId;
    private String comments;

    @Override
    public void runApproval(ApprovalRequest request) {
        activity.notifyApprovers(request);
        Workflow.await(() -> status != ApprovalStatus.PENDING);
        activity.finaliseApproval(
                request.getEntityType(),
                request.getEntityId(),
                request.getTenantId(),
                status == ApprovalStatus.APPROVED,
                approverId,
                comments);
    }

    @Override
    public void approve(String approverId, String comments) {
        if (status == ApprovalStatus.PENDING) {
            this.status = ApprovalStatus.APPROVED;
            this.approverId = approverId;
            this.comments = comments;
        }
    }

    @Override
    public void reject(String approverId, String comments) {
        if (status == ApprovalStatus.PENDING) {
            this.status = ApprovalStatus.REJECTED;
            this.approverId = approverId;
            this.comments = comments;
        }
    }

    @Override
    public ApprovalStatus getStatus() {
        return status;
    }
}
