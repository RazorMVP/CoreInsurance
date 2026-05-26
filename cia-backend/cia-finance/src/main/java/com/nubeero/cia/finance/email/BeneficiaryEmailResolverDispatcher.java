package com.nubeero.cia.finance.email;

import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.finance.FinanceEntityType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Routes {@link CreditNote} to the {@link BeneficiaryEmailResolver} matching
 * its {@code entityType}. Resolvers are autowired by bean name (e.g.
 * {@code @Component("CLAIM-email")}).
 *
 * <p>Returns {@code Optional.empty()} when the entity type has no resolver
 * registered (POLICY, CLAIM_EXPENSE) OR when the matched resolver itself
 * returns empty. The service layer (Task 25/26) converts that into a 422
 * RECIPIENT_UNRESOLVED — emails fail closed, never silently downgrade.
 *
 * @since Slice γ — F7 email transmission
 */
@Component
public class BeneficiaryEmailResolverDispatcher {

    private final Map<FinanceEntityType, BeneficiaryEmailResolver> resolvers;

    public BeneficiaryEmailResolverDispatcher(
            Map<String, BeneficiaryEmailResolver> beanMap) {
        this.resolvers = new EnumMap<>(FinanceEntityType.class);
        for (Map.Entry<String, BeneficiaryEmailResolver> e : beanMap.entrySet()) {
            String name = e.getKey();
            if (!name.endsWith("-email")) continue;
            String typeName = name.substring(0, name.length() - "-email".length());
            try {
                FinanceEntityType type = FinanceEntityType.valueOf(typeName);
                resolvers.put(type, e.getValue());
            } catch (IllegalArgumentException ex) {
                // Bean name doesn't match a FinanceEntityType — ignore.
            }
        }
    }

    public Optional<String> resolve(CreditNote creditNote) {
        BeneficiaryEmailResolver resolver = resolvers.get(creditNote.getEntityType());
        return resolver == null ? Optional.empty() : resolver.resolve(creditNote);
    }
}
