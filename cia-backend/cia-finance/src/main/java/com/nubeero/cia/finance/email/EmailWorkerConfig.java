package com.nubeero.cia.finance.email;

import com.nubeero.cia.workflow.TemporalQueues;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Registers both email workflows + their activity beans on
 * {@link TemporalQueues#EMAIL_QUEUE}. Mirrors {@code BackfillWorkerConfig}.
 *
 * <p>The {@code @PostConstruct} hook is graceful — if Temporal is unavailable
 * at boot, it logs a warning and the app still starts. This matches the
 * behaviour established by {@code BackfillWorkerConfig} (and the webhook
 * worker before it). {@code TemporalWorkerStarter} (cia-api) calls
 * {@code workerFactory.start()} once all modules have registered.
 *
 * @since Slice γ — Task 21, F7 email transmission
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class EmailWorkerConfig {

    private final WorkerFactory                          workerFactory;
    private final SendReceiptEmailActivitiesImpl         receiptActivities;
    private final SendPaymentVoucherEmailActivitiesImpl  voucherActivities;

    @PostConstruct
    public void registerEmailWorker() {
        try {
            Worker worker = workerFactory.newWorker(TemporalQueues.EMAIL_QUEUE);
            worker.registerWorkflowImplementationTypes(
                SendReceiptEmailWorkflowImpl.class,
                SendPaymentVoucherEmailWorkflowImpl.class);
            worker.registerActivitiesImplementations(receiptActivities, voucherActivities);
            log.info("Registered Temporal worker on queue: {}", TemporalQueues.EMAIL_QUEUE);
        } catch (Exception e) {
            log.warn("Could not register email Temporal worker (Temporal unavailable?): {}", e.getMessage());
        }
    }
}
