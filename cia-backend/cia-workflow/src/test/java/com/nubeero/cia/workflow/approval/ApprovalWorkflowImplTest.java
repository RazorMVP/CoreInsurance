package com.nubeero.cia.workflow.approval;

import com.nubeero.cia.workflow.TemporalQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalWorkflowImplTest {

    private TestWorkflowEnvironment testEnv;

    @AfterEach
    void tearDown() {
        if (testEnv != null) {
            testEnv.close();
        }
    }

    @Test
    void approvalWorkflowWaitsForApprovalSignalAndFinalises() {
        testEnv = TestWorkflowEnvironment.newInstance();
        RecordingApprovalActivity activity = new RecordingApprovalActivity();
        Worker worker = testEnv.newWorker(TemporalQueues.APPROVAL_QUEUE);
        worker.registerWorkflowImplementationTypes(ApprovalWorkflowImpl.class);
        worker.registerActivitiesImplementations(activity);
        testEnv.start();

        ApprovalWorkflow workflow = testEnv.getWorkflowClient().newWorkflowStub(
                ApprovalWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(TemporalQueues.APPROVAL_QUEUE)
                        .setWorkflowId("approval-workflow-test")
                        .build());

        WorkflowClient.start(workflow::runApproval, ApprovalRequest.builder()
                .entityType("POLICY")
                .entityId("policy-1")
                .tenantId("tenant-alpha")
                .initiatedBy("underwriter")
                .amount(new BigDecimal("1200.00"))
                .currency("NGN")
                .build());

        assertThat(workflow.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        workflow.approve("approver", "approved");
        WorkflowStub.fromTyped(workflow).getResult(Void.class);

        assertThat(workflow.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(activity.notifiedEntityId).isEqualTo("policy-1");
        assertThat(activity.finalised).isTrue();
        assertThat(activity.approved).isTrue();
        assertThat(activity.approverId).isEqualTo("approver");
        assertThat(activity.comments).isEqualTo("approved");
    }

    private static class RecordingApprovalActivity implements ApprovalActivity {
        private String notifiedEntityId;
        private boolean finalised;
        private boolean approved;
        private String approverId;
        private String comments;

        @Override
        public void notifyApprovers(ApprovalRequest request) {
            notifiedEntityId = request.getEntityId();
        }

        @Override
        public void finaliseApproval(String entityType, String entityId, String tenantId,
                boolean approved, String approverId, String comments) {
            this.finalised = true;
            this.approved = approved;
            this.approverId = approverId;
            this.comments = comments;
        }
    }
}
