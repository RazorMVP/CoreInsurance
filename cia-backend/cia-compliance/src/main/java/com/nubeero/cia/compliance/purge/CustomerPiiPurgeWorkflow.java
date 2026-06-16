package com.nubeero.cia.compliance.purge;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface CustomerPiiPurgeWorkflow {
    @WorkflowMethod
    void purge();
}
