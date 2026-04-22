package com.nubeero.cia.partner.webhook;

import com.nubeero.cia.workflow.webhook.WebhookDeliveryResult;
import com.nubeero.cia.workflow.webhook.WebhookDispatchActivity;
import com.nubeero.cia.workflow.webhook.WebhookDispatchRequest;
import com.nubeero.cia.workflow.webhook.WebhookDispatchWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

public class WebhookDispatchWorkflowImpl implements WebhookDispatchWorkflow {

    private final WebhookDispatchActivity activity = Workflow.newActivityStub(
            WebhookDispatchActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(60))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setMaximumAttempts(4)
                            .setInitialInterval(Duration.ofSeconds(30))
                            .setBackoffCoefficient(4.0)
                            .setMaximumInterval(Duration.ofMinutes(10))
                            .build())
                    .build());

    @Override
    public void dispatch(WebhookDispatchRequest request) {
        WebhookDeliveryResult result = activity.send(request);
        if (!result.isSuccess()) {
            Workflow.getLogger(WebhookDispatchWorkflowImpl.class)
                    .warn("Webhook delivery failed registrationId={} event={} error={}",
                            request.getWebhookRegistrationId(),
                            request.getEventType(),
                            result.getErrorMessage());
        }
    }
}
