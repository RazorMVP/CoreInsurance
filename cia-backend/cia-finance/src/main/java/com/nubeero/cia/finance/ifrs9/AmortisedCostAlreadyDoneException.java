package com.nubeero.cia.finance.ifrs9;

import com.nubeero.cia.common.exception.CiaException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Thrown when {@link AmortisedCostEngine#recognise(UUID)} encounters an
 * {@code investment_carrying_value} row that already exists for one of
 * the (holding, period) pairs it would create. Mirrors the Phase 2
 * {@code Lrc/LicRecognitionAlreadyDoneException} pattern.
 *
 * <p>The DB-level guarantee is {@code uq_investment_carrying_holding_period}
 * in V39; this exception is the service-layer fast-fail that surfaces a
 * clean 409 CONFLICT before any partial-write side-effect.
 */
public class AmortisedCostAlreadyDoneException extends CiaException {

    private final UUID periodId;
    private final UUID holdingId;

    public AmortisedCostAlreadyDoneException(UUID periodId, UUID holdingId) {
        super(
            "AMORTISED_COST_RECOGNITION_ALREADY_DONE",
            "Amortised-cost interest recognition already done for period " + periodId + " holding " + holdingId + ".",
            HttpStatus.CONFLICT);
        this.periodId = periodId;
        this.holdingId = holdingId;
    }

    public UUID getPeriodId() { return periodId; }

    public UUID getHoldingId() { return holdingId; }
}
