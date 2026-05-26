package com.nubeero.cia.finance.email;

import com.nubeero.cia.finance.CreditNote;

import java.util.Optional;

/**
 * Strategy that resolves the email recipient for a {@link CreditNote}.
 * Implementations are bean-named {@code "<FinanceEntityType>-email"} so the
 * {@link BeneficiaryEmailResolverDispatcher} can route by enum value.
 *
 * <p>Returns {@code Optional.empty()} when the underlying entity is missing
 * OR when its recipient field is null/blank — the dispatcher relays that
 * to the service layer, which surfaces a 422 RECIPIENT_UNRESOLVED.
 *
 * @since Slice γ — F7 email transmission
 */
public interface BeneficiaryEmailResolver {
    Optional<String> resolve(CreditNote creditNote);
}
