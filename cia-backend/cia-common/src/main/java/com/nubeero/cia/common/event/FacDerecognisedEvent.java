package com.nubeero.cia.common.event;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Published when a facultative reinsurance contract — inward or outward —
 * is cancelled. FAC / IFRS-17 PAA workstream Task 5 (lifecycle &amp;
 * modified-prospective transition).
 *
 * <p>Fired by {@code RiFacInwardService.cancel} (inward) and
 * {@code FacCoverService.cancel} (outward) <em>after</em> the contract's
 * status row is persisted as {@code CANCELLED}. cia-finance's derecognition
 * listener releases the cancelled contract's group's remaining unearned LRC
 * liability (inward, {@code Dr 2210 / Cr 4330}) or unamortised
 * reinsurance-held asset (outward, {@code Dr 5210 / Cr 1410}) to
 * income/expense in the currently OPEN fiscal period.
 *
 * <p>{@link ContractType} is a small, cia-common-local discriminator
 * (only the two FAC directions — this event is never fired for a direct
 * policy) — deliberately distinct from
 * {@code com.nubeero.cia.finance.paa.ContractType}, which cia-common
 * cannot import (cia-finance depends on cia-common, never the reverse; see
 * {@code PolicyApprovedEvent.commissionSourceType}'s javadoc for the same
 * cross-module-enum constraint, resolved there with a String instead — an
 * enum is preferable here since exactly two module-owning services publish
 * this event). The listener maps this value to its own module-local
 * {@code ContractType} when querying {@code ContractGroupAssignmentRepository}.
 */
public record FacDerecognisedEvent(
        ContractType contractType,
        UUID contractId,
        LocalDate effectiveDate
) {

    public enum ContractType {
        FAC_INWARD,
        FAC_OUTWARD
    }
}
