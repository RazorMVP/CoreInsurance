package com.nubeero.cia.finance.sms;

import com.nubeero.cia.finance.CreditNote;

import java.util.Optional;

/**
 * Strategy that resolves the SMS recipient phone number for a {@link CreditNote}.
 * Implementations are bean-named {@code "<FinanceEntityType>-phone"} so the
 * {@link BeneficiaryPhoneResolverDispatcher} can route by enum value.
 *
 * <p>Returns {@code Optional.empty()} when the underlying entity is missing
 * OR when its recipient field is null/blank — the dispatcher relays that
 * to the service layer, which surfaces a 422 PAYMENT_RECIPIENT_PHONE_UNRESOLVED.
 *
 * @since R7 — SMS dispatch
 */
public interface BeneficiaryPhoneResolver {
    Optional<String> resolve(CreditNote creditNote);
}
