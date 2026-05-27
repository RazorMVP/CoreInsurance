package com.nubeero.cia.finance.audit;

import com.nubeero.cia.workflow.TemporalQueues;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

public class PdfDownloadLogRetentionWorkflowImpl implements PdfDownloadLogRetentionWorkflow {

    private final PdfDownloadLogRetentionActivities activities = Workflow.newActivityStub(
            PdfDownloadLogRetentionActivities.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(TemporalQueues.EMAIL_QUEUE)
                .setStartToCloseTimeout(Duration.ofMinutes(5))
                .build());

    @Override
    public void purge() {
        activities.purgeOlderThan30Days();
    }
}
