package com.nubeero.cia.workflow;

public final class TemporalQueues {

    public static final String APPROVAL_QUEUE = "approval-queue";
    public static final String NAICOM_QUEUE = "naicom-upload-queue";
    public static final String NIID_QUEUE = "niid-upload-queue";
    public static final String NOTIFICATION_QUEUE = "notification-queue";
    public static final String WEBHOOK_QUEUE = "webhook-dispatch-queue";

    /**
     * Slice 1.8a — Module 12 period-end closures retroactive JE backfill.
     * One workflow execution per tenant; chunked activities replay
     * historical {@code SubledgerPostingService} events into the GL.
     */
    public static final String BACKFILL_QUEUE = "backfill-queue";

    /**
     * Transactional notification dispatch queue.
     * Hosts email workflows ({@code SendReceiptEmailWorkflow},
     * {@code SendPaymentVoucherEmailWorkflow}), SMS workflows (F7-δ / R7),
     * and the {@code PdfDownloadLogRetentionWorkflow} cron.
     * Retries with exponential backoff (5 min → 1 hr, no cap) on
     * SMTP/SendGrid/SMS-provider failure.
     */
    public static final String NOTIFICATIONS_QUEUE = "notifications-queue";

    private TemporalQueues() {}
}
