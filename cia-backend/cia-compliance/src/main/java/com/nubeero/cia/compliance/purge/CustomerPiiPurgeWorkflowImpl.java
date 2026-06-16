package com.nubeero.cia.compliance.purge;

import com.nubeero.cia.workflow.TemporalQueues;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;

/** Thin deterministic sweep: list active tenants → purge each. All IO + clock is in the activities. */
public class CustomerPiiPurgeWorkflowImpl implements CustomerPiiPurgeWorkflow {

    private static final Logger log = Workflow.getLogger(CustomerPiiPurgeWorkflowImpl.class);

    private final CompliancePurgeActivities activities = Workflow.newActivityStub(
        CompliancePurgeActivities.class,
        ActivityOptions.newBuilder()
            .setTaskQueue(TemporalQueues.COMPLIANCE_QUEUE)
            .setStartToCloseTimeout(Duration.ofMinutes(30))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
            .build());

    @Override
    public void purge() {
        List<String> schemas = activities.listActiveTenants();
        for (String schema : schemas) {
            try {
                PurgeTenantResult result = activities.purgeTenant(schema);
                if (result.ran()) {
                    log.info("PII purge tenant {} → {} customers", schema, result.customersPurged());
                }
            } catch (Exception ex) {
                log.warn("PII purge tenant {} failed (continuing sweep): {}", schema, ex.getMessage());
            }
        }
    }
}
