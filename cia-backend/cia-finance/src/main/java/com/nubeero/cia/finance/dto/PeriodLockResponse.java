package com.nubeero.cia.finance.dto;

import com.nubeero.cia.finance.gl.LockType;
import com.nubeero.cia.finance.gl.PeriodLock;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape for {@link PeriodLock} rows. Carries every column an auditor
 * or admin UI needs to render the lock history of a period — both active
 * and released rows are returned through the same DTO.
 *
 * @since Module 12, Slice 1.7
 */
public record PeriodLockResponse(
    UUID id,
    UUID fiscalPeriodId,
    LockType lockType,
    Instant lockedAt,
    String lockedBy,
    Instant graceWindowUntil,
    Instant releasedAt,
    String releasedBy,
    String releaseReason
) {
    public static PeriodLockResponse from(PeriodLock lock) {
        return new PeriodLockResponse(
            lock.getId(), lock.getFiscalPeriodId(), lock.getLockType(),
            lock.getLockedAt(), lock.getLockedBy(), lock.getGraceWindowUntil(),
            lock.getReleasedAt(), lock.getReleasedBy(), lock.getReleaseReason()
        );
    }
}
