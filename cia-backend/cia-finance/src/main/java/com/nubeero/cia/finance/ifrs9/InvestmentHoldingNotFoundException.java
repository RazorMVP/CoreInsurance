package com.nubeero.cia.finance.ifrs9;

import com.nubeero.cia.common.exception.CiaException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Thrown when a holding lookup by id returns nothing. Maps to HTTP 404.
 */
public class InvestmentHoldingNotFoundException extends CiaException {

    public InvestmentHoldingNotFoundException(UUID holdingId) {
        super(
            "INVESTMENT_HOLDING_NOT_FOUND",
            "Investment holding " + holdingId + " not found.",
            HttpStatus.NOT_FOUND);
    }
}
