package com.nubeero.cia.finance.pdf;

import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.setup.org.ReinsuranceCompany;
import com.nubeero.cia.setup.org.ReinsuranceCompanyRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the beneficiary profile for {@link com.nubeero.cia.finance.FinanceEntityType#REINSURANCE}
 * credit notes — outward FAC premium settlements to reinsurers.
 *
 * <p>Loads {@link ReinsuranceCompany} by {@code creditNote.beneficiaryId}.
 * Plain address column; no decryption involved.
 *
 * @since Slice β — Task 7, F7 payment-voucher PDF generation
 */
@Component("REINSURANCE-profile")
public class FacOutwardBeneficiaryProfileResolver implements BeneficiaryProfileResolver {

    private final ReinsuranceCompanyRepository reinsurerRepository;

    public FacOutwardBeneficiaryProfileResolver(
            ReinsuranceCompanyRepository reinsurerRepository) {
        this.reinsurerRepository = reinsurerRepository;
    }

    @Override
    public BeneficiaryProfile resolve(CreditNote creditNote) {
        Optional<ReinsuranceCompany> opt =
                reinsurerRepository.findById(creditNote.getBeneficiaryId());
        if (opt.isEmpty()) return null;
        ReinsuranceCompany r = opt.get();
        return new BeneficiaryProfile(r.getName(), r.getAddress(), null);
    }
}
