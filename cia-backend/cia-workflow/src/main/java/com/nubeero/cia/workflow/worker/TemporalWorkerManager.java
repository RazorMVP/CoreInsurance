package com.nubeero.cia.workflow.worker;

public interface TemporalWorkerManager {

    TemporalWorkerRegistration newWorker(String taskQueue);

    void start();

    boolean isStarted();

    boolean isShutdown();

    boolean isTerminated();
}
