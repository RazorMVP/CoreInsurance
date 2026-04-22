package com.nubeero.cia.workflow.webhook;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface WebhookDispatchWorkflow {

    @WorkflowMethod
    void dispatch(WebhookDispatchRequest request);
}
