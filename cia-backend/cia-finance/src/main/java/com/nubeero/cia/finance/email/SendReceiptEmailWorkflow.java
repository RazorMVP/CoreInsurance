package com.nubeero.cia.finance.email;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.UUID;

/**
 * Temporal workflow for delivering a receipt PDF as an email attachment.
 *
 * <p>The workflow is intentionally thin — it delegates all I/O to
 * {@link SendReceiptEmailActivities#deliver} so that retry semantics are
 * managed by the Temporal retry policy on the activity stub rather than
 * in workflow code.
 *
 * @since Slice γ — Task 19, F7 email transmission
 */
@WorkflowInterface
public interface SendReceiptEmailWorkflow {
    @WorkflowMethod
    void send(String tenantId, UUID receiptId, String requestedBy);
}
