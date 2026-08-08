package com.nubeero.cia.finance.paa;

/**
 * Discriminator for {@link ContractGroupAssignment#getContractType()} —
 * which kind of contract a group-of-contracts assignment row refers to.
 *
 * <p>{@code POLICY} is a direct policy ({@code cia-policy Policy.id}),
 * written by {@link ContractGroupingService} on every {@code PolicyApprovedEvent}.
 * {@code FAC_INWARD} / {@code FAC_OUTWARD} are reserved for facultative
 * reinsurance contracts — a later slice of the FAC / IFRS-17 PAA workstream
 * wires their writer; no code path produces them yet.
 *
 * <p>Deliberately distinct from {@link ContractNature} (the {@link Portfolio}
 * dimension): a direct policy is {@code ContractType.POLICY} inside a
 * {@code ContractNature.DIRECT} portfolio.
 */
public enum ContractType {
    POLICY,
    FAC_INWARD,
    FAC_OUTWARD
}
