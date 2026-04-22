package com.nubeero.cia.workflow.naicom;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface NaicomUploadWorkflow {

    @WorkflowMethod
    void uploadPolicy(String policyId, String tenantId);
}
