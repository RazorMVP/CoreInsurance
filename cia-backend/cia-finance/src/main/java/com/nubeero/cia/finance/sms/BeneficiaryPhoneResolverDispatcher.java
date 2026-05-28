package com.nubeero.cia.finance.sms;

import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.finance.FinanceEntityType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Routes {@link CreditNote} to the {@link BeneficiaryPhoneResolver} matching
 * its {@code entityType}. Resolvers are autowired by bean name (e.g.
 * {@code @Component("CLAIM-phone")}).
 *
 * <p>Returns {@code Optional.empty()} when the entity type has no resolver
 * registered (POLICY, CLAIM_EXPENSE) OR when the matched resolver itself
 * returns empty. The service layer converts that into a 422
 * PAYMENT_RECIPIENT_PHONE_UNRESOLVED — SMS fails closed, never silently downgrade.
 *
 * @since R7 — SMS dispatch
 */
@Component
public class BeneficiaryPhoneResolverDispatcher {

    private final Map<FinanceEntityType, BeneficiaryPhoneResolver> resolvers;

    public BeneficiaryPhoneResolverDispatcher(
            Map<String, BeneficiaryPhoneResolver> beanMap) {
        this.resolvers = new EnumMap<>(FinanceEntityType.class);
        for (Map.Entry<String, BeneficiaryPhoneResolver> e : beanMap.entrySet()) {
            String name = e.getKey();
            if (!name.endsWith("-phone")) continue;
            String typeName = name.substring(0, name.length() - "-phone".length());
            try {
                FinanceEntityType type = FinanceEntityType.valueOf(typeName);
                resolvers.put(type, e.getValue());
            } catch (IllegalArgumentException ex) {
                // Bean name doesn't match a FinanceEntityType — ignore.
            }
        }
    }

    public Optional<String> resolve(CreditNote creditNote) {
        BeneficiaryPhoneResolver resolver = resolvers.get(creditNote.getEntityType());
        return resolver == null ? Optional.empty() : resolver.resolve(creditNote);
    }
}
