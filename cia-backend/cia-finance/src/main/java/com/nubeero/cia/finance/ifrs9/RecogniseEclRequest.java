package com.nubeero.cia.finance.ifrs9;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Wire contract for {@code POST /api/v1/finance/ifrs9/ecl/recognise}.
 *
 * <p>v1 admin-provided ECL — actuarial PD × LGD × EAD computation is a v2
 * effort. Each entry carries the <em>target total</em> ECL allowance for
 * the holding at this period end; the engine computes the delta versus
 * the cumulative prior ECL recognised across earlier periods.
 *
 * <p>{@code eclStage} is optional. If supplied and different from the
 * holding's current stage, the engine updates both
 * {@code investment_holding.ecl_stage} and the carrying-value row.
 * Admin manages SICR (§5.5.9) detection manually in v1.
 */
public record RecogniseEclRequest(

    @NotNull UUID periodId,

    @NotEmpty @Valid List<HoldingEcl> ecls

) {

    public record HoldingEcl(
        @NotNull UUID holdingId,
        @NotNull @DecimalMin("0.00") BigDecimal eclAmount,
        @Min(1) @Max(3) Integer eclStage
    ) {}
}
