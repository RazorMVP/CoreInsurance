package com.nubeero.cia.finance.ifrs9;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Wire contract for {@code POST /api/v1/finance/ifrs9/fair-value/recognise}.
 *
 * <p>v1: admin provides the fair value per holding (typically from a
 * market-data spreadsheet or treasury pricing tool). v2 will hook up a
 * market-data feed (Bloomberg / Reuters / exchange) and remove the
 * admin-input step.
 *
 * <p>Holdings not present in {@code valuations} are skipped — the engine
 * only processes what's been provided. This is intentional: actuaries
 * may run FV updates in batches over the trading day rather than the
 * whole book at once.
 */
public record RecogniseFairValuesRequest(

    @NotNull UUID periodId,

    @NotEmpty @Valid List<HoldingValuation> valuations

) {

    public record HoldingValuation(
        @NotNull UUID holdingId,
        @NotNull BigDecimal fairValue
    ) {}
}
