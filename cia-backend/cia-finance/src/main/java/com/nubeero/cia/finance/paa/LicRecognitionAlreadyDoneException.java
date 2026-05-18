package com.nubeero.cia.finance.paa;

import java.util.UUID;

/**
 * Thrown when {@link LicEngine#recognise(UUID)} encounters a {@code paa_lic}
 * row that already exists for one of the (group, period) pairs it would
 * create. Mirrors {@link LrcRecognitionAlreadyDoneException} — the engine
 * refuses to overwrite; a re-run is an explicit operation requiring prior
 * reversal.
 *
 * <p>The DB-level guarantee is {@code uq_paa_lic_group_period} in V36.
 */
public class LicRecognitionAlreadyDoneException extends RuntimeException {

    private final UUID periodId;
    private final UUID groupId;

    public LicRecognitionAlreadyDoneException(UUID periodId, UUID groupId) {
        super("LIC recognition already done for period " + periodId + " group " + groupId);
        this.periodId = periodId;
        this.groupId = groupId;
    }

    public UUID getPeriodId() { return periodId; }

    public UUID getGroupId() { return groupId; }
}
