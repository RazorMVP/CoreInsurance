package com.nubeero.cia.finance.pdf;

import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.finance.FinanceEntityType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Routes {@link CreditNote} to the {@link BeneficiaryProfileResolver} for its
 * {@code entityType}. Resolvers are autowired by bean name (e.g.
 * {@code @Component("CLAIM-profile")}).
 *
 * <p>Falls back to {@code BeneficiaryProfile.nameOnly(creditNote.beneficiaryName)}
 * when no resolver exists for the entity type (POLICY, CLAIM_EXPENSE) or when
 * the matched resolver returns null (referenced entity missing).
 *
 * @since Slice β — F7 payment-voucher PDF generation
 */
@Component
public class BeneficiaryProfileResolverDispatcher {

    private final Map<FinanceEntityType, BeneficiaryProfileResolver> resolvers;

    public BeneficiaryProfileResolverDispatcher(
            Map<String, BeneficiaryProfileResolver> beanMap) {
        // Spring injects all BeneficiaryProfileResolver beans keyed by bean name.
        // Bean names follow the convention "<ENTITY_TYPE>-profile" (see resolver
        // impls); we strip the suffix and map to the enum.
        this.resolvers = new EnumMap<>(FinanceEntityType.class);
        for (Map.Entry<String, BeneficiaryProfileResolver> e : beanMap.entrySet()) {
            String name = e.getKey();
            if (!name.endsWith("-profile")) continue;
            String typeName = name.substring(0, name.length() - "-profile".length());
            try {
                FinanceEntityType type = FinanceEntityType.valueOf(typeName);
                resolvers.put(type, e.getValue());
            } catch (IllegalArgumentException ex) {
                // Bean name doesn't match a FinanceEntityType — ignore.
            }
        }
    }

    public BeneficiaryProfile resolve(CreditNote creditNote) {
        BeneficiaryProfileResolver resolver = resolvers.get(creditNote.getEntityType());
        if (resolver == null) {
            return BeneficiaryProfile.nameOnly(creditNote.getBeneficiaryName());
        }
        BeneficiaryProfile profile = resolver.resolve(creditNote);
        return profile != null ? profile : BeneficiaryProfile.nameOnly(creditNote.getBeneficiaryName());
    }
}
