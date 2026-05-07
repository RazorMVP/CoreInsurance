package com.nubeero.cia.workflow.niid;

import com.nubeero.cia.integrations.niid.NiidUploadResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;

import java.time.Duration;

public class NiidUploadWorkflowImpl implements NiidUploadWorkflow {

    private final NiidUploadActivity activity = Workflow.newActivityStub(
            NiidUploadActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setMaximumAttempts(4)
                            .setInitialInterval(Duration.ofSeconds(30))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofMinutes(5))
                            .build())
                    .build());

    @Override
    public void uploadPolicy(String policyId, String tenantId) {
        String policyJson = activity.fetchPolicyPayload(policyId, tenantId);
        NiidUploadResult result = activity.uploadToNiid(policyId, tenantId, policyJson);
        if (result == null || !result.isSuccess() || result.getNiidRef() == null
                || result.getNiidRef().isBlank()) {
            String message = result != null && result.getErrorMessage() != null
                    ? result.getErrorMessage()
                    : "NIID upload failed without a reference";
            throw ApplicationFailure.newFailure(message, "NIID_UPLOAD_FAILED");
        }
        activity.updatePolicyNiidRef(policyId, tenantId, result.getNiidRef());
    }
}
