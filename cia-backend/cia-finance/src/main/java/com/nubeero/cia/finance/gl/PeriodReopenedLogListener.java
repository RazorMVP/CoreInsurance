package com.nubeero.cia.finance.gl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Minimal in-module listener for {@link PeriodReopenedEvent}. Records the
 * reopen at INFO level so a tenant's reopen history is searchable in logs
 * even when the email-dispatch listener in {@code cia-api} is disabled or
 * its provider unconfigured.
 *
 * <p>The real CFO + compliance email notification lives in the
 * {@code cia-api} assembly module — that's where the {@code cia-finance} and
 * {@code cia-notifications} modules co-exist, so the wiring can call
 * {@code NotificationService.send(...)} without introducing a
 * {@code cia-finance → cia-notifications} dependency.
 *
 * @since Module 12, Slice 1.7
 */
@Slf4j
@Component
public class PeriodReopenedLogListener {

    @EventListener
    public void onReopen(PeriodReopenedEvent event) {
        log.warn("PERIOD REOPENED — period={} reopenedBy={} reason={}",
            event.getPeriodLabel(), event.getReopenedBy(), event.getReason());
    }
}
