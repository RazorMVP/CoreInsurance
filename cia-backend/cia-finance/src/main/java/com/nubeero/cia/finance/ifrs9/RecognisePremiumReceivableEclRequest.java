package com.nubeero.cia.finance.ifrs9;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Wire contract for {@code POST /api/v1/finance/ifrs9/premium-receivable-ecl/recognise}.
 *
 * <p>IFRS 9 §5.5.15 simplified approach — the admin (typically the
 * actuarial team) supplies the period-end provision matrix as a list of
 * aging buckets, each with an outstanding amount and a default-rate
 * coefficient (per §B5.5.35 the rate must reflect both historical default
 * experience AND forward-looking adjustments).
 *
 * <p>Lifetime ECL = Σ (outstandingAmount × defaultRate) across all buckets.
 * The engine sums, computes delta versus cumulative prior ECL, and posts
 * the JE for the movement.
 *
 * <p>v1 takes the matrix as input. v2 will compute aging buckets
 * automatically from {@code debit_notes} − {@code receipts} aging analysis
 * and look up rates from a per-tenant {@code premium_provision_matrix} table.
 */
public record RecognisePremiumReceivableEclRequest(

    @NotNull UUID periodId,

    @NotEmpty @Valid List<AgingBucket> agingBuckets

) {

    /**
     * One row in the provision matrix. Default rate is a fraction
     * (e.g. {@code 0.005} for 0.5%), bounded to [0, 1].
     */
    public record AgingBucket(
        @NotBlank @Size(max = 50) String label,
        @NotNull @DecimalMin("0.00") BigDecimal outstandingAmount,
        @NotNull @DecimalMin("0.00") @DecimalMax("1.00") BigDecimal defaultRate
    ) {}
}
