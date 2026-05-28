package com.nubeero.cia.finance.sms;

import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.setup.org.ReinsuranceCompany;
import com.nubeero.cia.setup.org.ReinsuranceCompanyRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the SMS recipient phone for {@link com.nubeero.cia.finance.FinanceEntityType#REINSURANCE}
 * credit notes — outward FAC premium settlements. Loads
 * {@link ReinsuranceCompany} by {@code creditNote.beneficiaryId}.
 *
 * @since R7 — SMS dispatch
 */
@Component("REINSURANCE-phone")
public class FacOutwardBeneficiaryPhoneResolver implements BeneficiaryPhoneResolver {

    private final ReinsuranceCompanyRepository reinsurerRepository;

    public FacOutwardBeneficiaryPhoneResolver(
            ReinsuranceCompanyRepository reinsurerRepository) {
        this.reinsurerRepository = reinsurerRepository;
    }

    @Override
    public Optional<String> resolve(CreditNote creditNote) {
        return reinsurerRepository.findById(creditNote.getBeneficiaryId())
                .map(ReinsuranceCompany::getPhone)
                .filter(s -> s != null && !s.isBlank());
    }
}
