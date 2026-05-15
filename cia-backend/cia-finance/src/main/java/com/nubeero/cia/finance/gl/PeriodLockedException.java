package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.exception.CiaException;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Thrown by {@link PeriodLockInterceptor} when a write is rejected because
 * its {@link com.nubeero.cia.common.entity.LockableByPeriod#getLockDate()}
 * falls inside a period that is hard-closed, or soft-closed past the grace
 * window without an override role.
 *
 * <p>The structured {@link LockDecision} payload is preserved through the
 * exception chain so the {@code PeriodLockExceptionHandler} (a dedicated
 * RestControllerAdvice) renders it as a typed JSON error body the
 * frontend can act on without a second lookup.
 *
 * <p>HTTP status is {@code 423 LOCKED} — semantically accurate, distinguishes
 * lock-conflict from generic 422 business-rule failures and 403 permission
 * denials in the frontend's switch statement.
 *
 * @since Module 12, Slice 1.7
 */
@Getter
public class PeriodLockedException extends CiaException {

    private final transient LockDecision decision;

    public PeriodLockedException(LockDecision decision) {
        super("PERIOD_LOCKED", buildMessage(decision), HttpStatus.LOCKED);
        this.decision = decision;
    }

    private static String buildMessage(LockDecision decision) {
        if (decision == null) {
            return "Period is locked";
        }
        return "Period %s is %s — %s".formatted(
            decision.periodLabel() != null ? decision.periodLabel() : "(unknown)",
            decision.status() != null ? decision.status() : "(no status)",
            decision.reason() != null ? decision.reason() : "write rejected by lock check"
        );
    }
}
