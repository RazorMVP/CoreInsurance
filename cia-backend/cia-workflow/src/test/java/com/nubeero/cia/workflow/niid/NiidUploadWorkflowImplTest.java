package com.nubeero.cia.workflow.niid;

import com.nubeero.cia.integrations.niid.NiidUploadResult;
import com.nubeero.cia.workflow.TemporalQueues;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NiidUploadWorkflowImplTest {

    private TestWorkflowEnvironment testEnv;

    @AfterEach
    void tearDown() {
        if (testEnv != null) {
            testEnv.close();
        }
    }

    @Test
    void niidWorkflowFetchesUploadsAndAppliesNiidReference() {
        testEnv = TestWorkflowEnvironment.newInstance();
        RecordingNiidActivity activity = new RecordingNiidActivity();
        Worker worker = testEnv.newWorker(TemporalQueues.NIID_QUEUE);
        worker.registerWorkflowImplementationTypes(NiidUploadWorkflowImpl.class);
        worker.registerActivitiesImplementations(activity);
        testEnv.start();

        NiidUploadWorkflow workflow = testEnv.getWorkflowClient().newWorkflowStub(
                NiidUploadWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(TemporalQueues.NIID_QUEUE)
                        .setWorkflowId("niid-workflow-test")
                        .build());

        workflow.uploadPolicy("policy-1", "tenant-alpha");

        assertThat(activity.fetched).isTrue();
        assertThat(activity.uploadedPolicyJson).isEqualTo("{\"policy\":\"policy-1\"}");
        assertThat(activity.updatedRef).isEqualTo("NIID-REF-001");
    }

    private static class RecordingNiidActivity implements NiidUploadActivity {
        private boolean fetched;
        private String uploadedPolicyJson;
        private String updatedRef;

        @Override
        public String fetchPolicyPayload(String policyId, String tenantId) {
            fetched = true;
            return "{\"policy\":\"" + policyId + "\"}";
        }

        @Override
        public NiidUploadResult uploadToNiid(String policyId, String tenantId, String policyJson) {
            uploadedPolicyJson = policyJson;
            return NiidUploadResult.builder()
                    .success(true)
                    .niidRef("NIID-REF-001")
                    .build();
        }

        @Override
        public void updatePolicyNiidRef(String policyId, String tenantId, String niidRef) {
            updatedRef = niidRef;
        }
    }
}
