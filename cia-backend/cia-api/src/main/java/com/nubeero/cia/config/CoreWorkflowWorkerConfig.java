package com.nubeero.cia.config;

import com.nubeero.cia.policy.PolicyNaicomUploadActivityImpl;
import com.nubeero.cia.policy.PolicyNiidUploadActivityImpl;
import com.nubeero.cia.workflow.TemporalQueues;
import com.nubeero.cia.workflow.approval.ApprovalActivity;
import com.nubeero.cia.workflow.approval.ApprovalWorkflowImpl;
import com.nubeero.cia.workflow.naicom.NaicomUploadWorkflowImpl;
import com.nubeero.cia.workflow.niid.NiidUploadWorkflowImpl;
import com.nubeero.cia.workflow.worker.TemporalWorkerManager;
import com.nubeero.cia.workflow.worker.TemporalWorkerRegistration;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class CoreWorkflowWorkerConfig {

    private final TemporalWorkerManager workerManager;
    private final ApprovalActivity approvalActivity;
    private final PolicyNaicomUploadActivityImpl naicomUploadActivity;
    private final PolicyNiidUploadActivityImpl niidUploadActivity;

    @PostConstruct
    public void registerCoreWorkers() {
        TemporalWorkerRegistration approvalWorker = workerManager.newWorker(TemporalQueues.APPROVAL_QUEUE);
        approvalWorker.registerWorkflowImplementationTypes(ApprovalWorkflowImpl.class);
        approvalWorker.registerActivitiesImplementations(approvalActivity);

        TemporalWorkerRegistration naicomWorker = workerManager.newWorker(TemporalQueues.NAICOM_QUEUE);
        naicomWorker.registerWorkflowImplementationTypes(NaicomUploadWorkflowImpl.class);
        naicomWorker.registerActivitiesImplementations(naicomUploadActivity);

        TemporalWorkerRegistration niidWorker = workerManager.newWorker(TemporalQueues.NIID_QUEUE);
        niidWorker.registerWorkflowImplementationTypes(NiidUploadWorkflowImpl.class);
        niidWorker.registerActivitiesImplementations(niidUploadActivity);

        log.info("Registered Temporal workers on queues: {}, {}, {}",
                TemporalQueues.APPROVAL_QUEUE,
                TemporalQueues.NAICOM_QUEUE,
                TemporalQueues.NIID_QUEUE);
    }
}
