package com.nubeero.cia.workflow.approval;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface ApprovalWorkflow {

    @WorkflowMethod
    void runApproval(ApprovalRequest request);

    @SignalMethod
    void approve(String approverId, String comments);

    @SignalMethod
    void reject(String approverId, String comments);

    @QueryMethod
    ApprovalStatus getStatus();
}
