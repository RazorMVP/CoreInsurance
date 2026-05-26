package com.nubeero.cia.finance.pdf;

/**
 * Resolved beneficiary identity for a {@link com.nubeero.cia.finance.CreditNote}.
 *
 * <p>Used by {@link com.nubeero.cia.finance.pdf.PaymentVoucherPdfGenerator} to
 * fill the "Paid to" block on the voucher PDF. The dispatcher routes credit
 * notes to the resolver implementation matching {@code creditNote.entityType}.
 *
 * @param name          beneficiary display name (never blank — falls back to
 *                      {@code creditNote.beneficiaryName} when resolution fails)
 * @param addressLine1  first address line; may be {@code null} when the
 *                      beneficiary entity has no recorded address
 * @param addressLine2  optional second address line (city / postcode etc.)
 *
 * @since Slice β — F7 payment-voucher PDF generation
 */
public record BeneficiaryProfile(
        String name,
        String addressLine1,
        String addressLine2
) {
    public static BeneficiaryProfile nameOnly(String name) {
        return new BeneficiaryProfile(name, null, null);
    }
}
