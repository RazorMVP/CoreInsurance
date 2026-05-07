package com.nubeero.cia.workflow.naicom;

import com.nubeero.cia.integrations.naicom.NaicomUploadResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;

import java.time.Duration;

public class NaicomUploadWorkflowImpl implements NaicomUploadWorkflow {

    private final NaicomUploadActivity activity = Workflow.newActivityStub(
            NaicomUploadActivity.class,
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
        NaicomUploadResult result = activity.uploadToNaicom(policyId, tenantId, policyJson);
        if (result == null || !result.isSuccess() || result.getNaicomUid() == null
                || result.getNaicomUid().isBlank()) {
            String message = result != null && result.getErrorMessage() != null
                    ? result.getErrorMessage()
                    : "NAICOM upload failed without a certificate UID";
            throw ApplicationFailure.newFailure(message, "NAICOM_UPLOAD_FAILED");
        }
        activity.updatePolicyCertificate(policyId, tenantId, result.getNaicomUid());
    }
}
