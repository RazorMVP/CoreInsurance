package com.nubeero.cia.finance.pdf;

import com.nubeero.cia.finance.CreditNote;

/**
 * Strategy interface for resolving a {@link BeneficiaryProfile} from a
 * {@link CreditNote}. Each implementation handles one
 * {@link com.nubeero.cia.finance.FinanceEntityType}; the
 * {@link BeneficiaryProfileResolverDispatcher} routes credit notes to the
 * right impl.
 *
 * <p>Implementations may load JPA entities (e.g. {@code Customer}) which
 * triggers {@code @ColumnTransformer}-based decryption of encrypted columns
 * like {@code Customer.address}. Implementations MUST be resilient to missing
 * referenced entities — the dispatcher's fallback (denormalised name + null
 * address) covers the case where resolution returns null.
 *
 * @since Slice β — F7 payment-voucher PDF generation
 */
public interface BeneficiaryProfileResolver {
    /**
     * @return the resolved profile, or {@code null} when the referenced
     *         entity cannot be loaded. Dispatcher falls back to
     *         {@code BeneficiaryProfile.nameOnly(creditNote.beneficiaryName)}.
     */
    BeneficiaryProfile resolve(CreditNote creditNote);
}
