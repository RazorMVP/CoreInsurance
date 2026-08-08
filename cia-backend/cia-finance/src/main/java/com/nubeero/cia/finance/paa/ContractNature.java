package com.nubeero.cia.finance.paa;

/**
 * Dimension on {@link Portfolio#getContractNature()} distinguishing what
 * kind of business a portfolio groups contracts for: direct policies vs.
 * facultative reinsurance accepted (inward) or ceded (outward).
 *
 * <p>Deliberately distinct from {@link ContractType} (the
 * {@link ContractGroupAssignment} discriminator): a direct policy is
 * {@code ContractType.POLICY} inside a {@code ContractNature.DIRECT}
 * portfolio. Every portfolio {@link ContractGroupingService} creates today
 * is {@code DIRECT}; {@code FAC_INWARD} / {@code FAC_OUTWARD} are reserved
 * for a later slice of the FAC / IFRS-17 PAA workstream.
 */
public enum ContractNature {
    DIRECT,
    FAC_INWARD,
    FAC_OUTWARD
}
