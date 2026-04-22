package com.nubeero.cia.workflow.niid;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface NiidUploadWorkflow {

    @WorkflowMethod
    void uploadPolicy(String policyId, String tenantId);
}
