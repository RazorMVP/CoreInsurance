package com.nubeero.cia.common.event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Fired by {@code PolicyService.approve()} after a policy moves to ACTIVE.
 * Multiple listeners consume the same event:
 * <ul>
 *   <li>{@code SubledgerPostingService} — posts the premium GL entry (Dr 1310 / Cr 2110).
 *       When {@code commissionAmount > 0} and {@code commissionSourceType} is set, also
 *       posts a commission JE on the same business date (Slice 84c).</li>
 *   <li>{@code ContractGroupingService} — assigns the policy to its IFRS 17 §22 cohort.</li>
 *   <li>RI allocation — runs automatic treaty allocation against the {@code totalSumInsured}.</li>
 *   <li>{@code PolicyCommissionCreditNoteListener} — generates a payables credit note for
 *       broker commission (Slice 84c). Skips when commission fields are null.</li>
 * </ul>
 *
 * <p><b>Commission fields semantics (Slice 84c):</b>
 * <br>{@code commissionSourceType} is the V50 enum value as a String — one of
 * {@code AGENT}, {@code BROKER}, {@code RELATIONSHIP_MANAGER}, or {@code null} when
 * the snapshot is absent (V51 / Open Q#11). Carried as a String rather than the enum
 * type to avoid a cia-common → cia-setup dependency.
 * <br>{@code commissionAmount} = {@code netPremium × commissionRate / 100} computed at
 * publish time. Null when {@code commissionSourceType} is null (paired-CHECK semantics
 * matching V51 {@code ck_policies_commission_pair}).
 */
public record PolicyApprovedEvent(
        UUID policyId,
        String policyNumber,
        UUID customerId,
        String customerName,
        UUID brokerId,
        String brokerName,
        String productName,
        BigDecimal netPremium,
        String currencyCode,
        LocalDate policyEndDate,
        // RI allocation fields
        UUID productId,
        UUID classOfBusinessId,
        BigDecimal totalSumInsured,
        LocalDate policyStartDate,
        // Commission snapshot (Slice 84c). Both fields null when V51 snapshot is absent.
        String commissionSourceType,
        BigDecimal commissionAmount
) {}
