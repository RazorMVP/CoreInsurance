package com.nubeero.cia.workflow.webhook;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface WebhookDispatchActivity {

    WebhookDeliveryResult send(WebhookDispatchRequest request);
}
