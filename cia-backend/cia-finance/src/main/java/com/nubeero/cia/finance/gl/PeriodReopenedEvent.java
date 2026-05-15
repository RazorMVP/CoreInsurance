package com.nubeero.cia.finance.gl;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Published by {@link PeriodLockService#reopen} when a HARD-closed period is
 * released. The notification listener (separate from this slice's primary
 * write path) fires the configured CFO + compliance email — NAICOM precedent
 * is that reopens of hard-closed periods are accompanied by an out-of-band
 * notification trail.
 *
 * <p>The event is published <strong>after</strong> the DB transaction
 * commits in the same request — Spring's default sync semantics suit this
 * fine, since the email send itself runs inside a Temporal workflow
 * spawned by the listener (per the existing {@code NotificationService}
 * pattern) and won't block the request thread.
 *
 * @since Module 12, Slice 1.7
 */
@Getter
public class PeriodReopenedEvent extends ApplicationEvent {

    private final UUID periodId;
    private final String periodLabel;
    private final String reopenedBy;
    private final String reason;

    public PeriodReopenedEvent(Object source, UUID periodId, String periodLabel, String reopenedBy, String reason) {
        super(source);
        this.periodId = periodId;
        this.periodLabel = periodLabel;
        this.reopenedBy = reopenedBy;
        this.reason = reason;
    }
}
