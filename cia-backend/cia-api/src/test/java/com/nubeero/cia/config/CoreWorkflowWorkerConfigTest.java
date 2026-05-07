package com.nubeero.cia.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.policy.PolicyNaicomUploadActivityImpl;
import com.nubeero.cia.policy.PolicyNiidUploadActivityImpl;
import com.nubeero.cia.workflow.TemporalQueues;
import com.nubeero.cia.workflow.approval.ApprovalActivityImpl;
import com.nubeero.cia.workflow.approval.ApprovalWorkflowImpl;
import com.nubeero.cia.workflow.naicom.NaicomUploadWorkflowImpl;
import com.nubeero.cia.workflow.niid.NiidUploadWorkflowImpl;
import com.nubeero.cia.workflow.worker.TemporalWorkerManager;
import com.nubeero.cia.workflow.worker.TemporalWorkerRegistration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CoreWorkflowWorkerConfigTest {

    @Test
    void registersCoreWorkflowWorkersAndActivities() {
        RecordingWorkerManager workerManager = new RecordingWorkerManager();
        ApprovalActivityImpl approvalActivity = new ApprovalActivityImpl();
        PolicyNaicomUploadActivityImpl naicomActivity =
                new PolicyNaicomUploadActivityImpl(null, null, new ObjectMapper());
        PolicyNiidUploadActivityImpl niidActivity =
                new PolicyNiidUploadActivityImpl(null, null, new ObjectMapper());

        new CoreWorkflowWorkerConfig(
                workerManager,
                approvalActivity,
                naicomActivity,
                niidActivity).registerCoreWorkers();

        assertThat(workerManager.registrations).containsExactly(
                new Registration(
                        TemporalQueues.APPROVAL_QUEUE,
                        List.of(ApprovalWorkflowImpl.class),
                        List.of(approvalActivity)),
                new Registration(
                        TemporalQueues.NAICOM_QUEUE,
                        List.of(NaicomUploadWorkflowImpl.class),
                        List.of(naicomActivity)),
                new Registration(
                        TemporalQueues.NIID_QUEUE,
                        List.of(NiidUploadWorkflowImpl.class),
                        List.of(niidActivity)));
    }

    private static final class RecordingWorkerManager implements TemporalWorkerManager {

        private final List<Registration> registrations = new ArrayList<>();

        @Override
        public TemporalWorkerRegistration newWorker(String taskQueue) {
            Registration registration = new Registration(taskQueue, new ArrayList<>(), new ArrayList<>());
            registrations.add(registration);
            return registration;
        }

        @Override
        public void start() {
        }

        @Override
        public boolean isStarted() {
            return false;
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }
    }

    private record Registration(
            String taskQueue,
            List<Class<?>> workflowImplementationTypes,
            List<Object> activities) implements TemporalWorkerRegistration {

        @Override
        public void registerWorkflowImplementationTypes(Class<?>... workflowImplementationTypes) {
            this.workflowImplementationTypes.addAll(Arrays.asList(workflowImplementationTypes));
        }

        @Override
        public void registerActivitiesImplementations(Object... activities) {
            this.activities.addAll(Arrays.asList(activities));
        }
    }
}
