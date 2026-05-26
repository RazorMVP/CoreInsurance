package com.nubeero.cia.finance.email;

import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.setup.org.ReinsuranceCompany;
import com.nubeero.cia.setup.org.ReinsuranceCompanyRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the email recipient for {@link com.nubeero.cia.finance.FinanceEntityType#REINSURANCE}
 * credit notes — outward FAC premium settlements. Loads
 * {@link ReinsuranceCompany} by {@code creditNote.beneficiaryId}.
 *
 * @since Slice γ — Task 17, F7 email transmission
 */
@Component("REINSURANCE-email")
public class FacOutwardBeneficiaryEmailResolver implements BeneficiaryEmailResolver {

    private final ReinsuranceCompanyRepository reinsurerRepository;

    public FacOutwardBeneficiaryEmailResolver(
            ReinsuranceCompanyRepository reinsurerRepository) {
        this.reinsurerRepository = reinsurerRepository;
    }

    @Override
    public Optional<String> resolve(CreditNote creditNote) {
        return reinsurerRepository.findById(creditNote.getBeneficiaryId())
                .map(ReinsuranceCompany::getEmail)
                .filter(s -> s != null && !s.isBlank());
    }
}
