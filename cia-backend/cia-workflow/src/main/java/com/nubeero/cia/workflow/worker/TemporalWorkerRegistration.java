package com.nubeero.cia.workflow.worker;

public interface TemporalWorkerRegistration {

    void registerWorkflowImplementationTypes(Class<?>... workflowImplementationTypes);

    void registerActivitiesImplementations(Object... activities);
}
