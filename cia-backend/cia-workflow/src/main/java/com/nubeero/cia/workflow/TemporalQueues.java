package com.nubeero.cia.workflow;

public final class TemporalQueues {

    public static final String APPROVAL_QUEUE = "approval-queue";
    public static final String NAICOM_QUEUE = "naicom-upload-queue";
    public static final String NIID_QUEUE = "niid-upload-queue";
    public static final String NOTIFICATION_QUEUE = "notification-queue";
    public static final String WEBHOOK_QUEUE = "webhook-dispatch-queue";

    private TemporalQueues() {}
}
