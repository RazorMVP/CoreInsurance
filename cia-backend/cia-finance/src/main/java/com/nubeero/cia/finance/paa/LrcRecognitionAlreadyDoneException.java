package com.nubeero.cia.finance.paa;

import java.util.UUID;

/**
 * Thrown when {@link LrcEngine#recognise(UUID)} encounters a
 * {@code paa_lrc} row that already exists for one of the (group, period)
 * pairs it would create. The engine deliberately refuses to overwrite — a
 * re-run is an explicit operation requiring prior reversal.
 *
 * <p>The DB-level guarantee is {@code uq_paa_lrc_group_period} in V36; this
 * exception is the service-layer fast-fail that surfaces a clean 409
 * CONFLICT before any partial-write side-effect.
 */
public class LrcRecognitionAlreadyDoneException extends RuntimeException {

    private final UUID periodId;
    private final UUID groupId;

    public LrcRecognitionAlreadyDoneException(UUID periodId, UUID groupId) {
        super("LRC recognition already done for period " + periodId + " group " + groupId);
        this.periodId = periodId;
        this.groupId = groupId;
    }

    public UUID getPeriodId() { return periodId; }

    public UUID getGroupId() { return groupId; }
}
