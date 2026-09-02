package com.nubeero.cia.partner.usage;

import com.nubeero.cia.workflow.TemporalQueues;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public class PartnerUsageFlushWorkflowImpl implements PartnerUsageFlushWorkflow {

    private final PartnerUsageFlushActivities activities = Workflow.newActivityStub(
            PartnerUsageFlushActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(TemporalQueues.WEBHOOK_QUEUE)
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .build());

    @Override
    public void flushYesterday() {
        activities.flushYesterday();
    }
}
