package com.nubeero.cia.workflow.naicom;

import com.nubeero.cia.integrations.naicom.NaicomUploadResult;
import com.nubeero.cia.workflow.TemporalQueues;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NaicomUploadWorkflowImplTest {

    private TestWorkflowEnvironment testEnv;

    @AfterEach
    void tearDown() {
        if (testEnv != null) {
            testEnv.close();
        }
    }

    @Test
    void naicomWorkflowFetchesUploadsAndAppliesCertificateUid() {
        testEnv = TestWorkflowEnvironment.newInstance();
        RecordingNaicomActivity activity = new RecordingNaicomActivity();
        Worker worker = testEnv.newWorker(TemporalQueues.NAICOM_QUEUE);
        worker.registerWorkflowImplementationTypes(NaicomUploadWorkflowImpl.class);
        worker.registerActivitiesImplementations(activity);
        testEnv.start();

        NaicomUploadWorkflow workflow = testEnv.getWorkflowClient().newWorkflowStub(
                NaicomUploadWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(TemporalQueues.NAICOM_QUEUE)
                        .setWorkflowId("naicom-workflow-test")
                        .build());

        workflow.uploadPolicy("policy-1", "tenant-alpha");

        assertThat(activity.fetched).isTrue();
        assertThat(activity.uploadedPolicyJson).isEqualTo("{\"policy\":\"policy-1\"}");
        assertThat(activity.updatedUid).isEqualTo("NAICOM-UID-001");
    }

    private static class RecordingNaicomActivity implements NaicomUploadActivity {
        private boolean fetched;
        private String uploadedPolicyJson;
        private String updatedUid;

        @Override
        public String fetchPolicyPayload(String policyId, String tenantId) {
            fetched = true;
            return "{\"policy\":\"" + policyId + "\"}";
        }

        @Override
        public NaicomUploadResult uploadToNaicom(String policyId, String tenantId, String policyJson) {
            uploadedPolicyJson = policyJson;
            return NaicomUploadResult.builder()
                    .success(true)
                    .naicomUid("NAICOM-UID-001")
                    .build();
        }

        @Override
        public void updatePolicyCertificate(String policyId, String tenantId, String naicomUid) {
            updatedUid = naicomUid;
        }
    }
}
