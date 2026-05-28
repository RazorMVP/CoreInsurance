package com.nubeero.cia.finance.sms;

import com.nubeero.cia.claims.Claim;
import com.nubeero.cia.claims.ClaimRepository;
import com.nubeero.cia.customer.Customer;
import com.nubeero.cia.customer.CustomerRepository;
import com.nubeero.cia.finance.CreditNote;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the SMS recipient phone for {@link com.nubeero.cia.finance.FinanceEntityType#CLAIM}
 * credit notes (DV payouts). Loads Claim → Customer by claim.customerId,
 * returns customer.phone when present + non-blank.
 *
 * <p>Returns {@code Optional.empty()} when any link is missing or the phone
 * is blank — dispatcher relays to the 422 path.
 *
 * @since R7 — SMS dispatch
 */
@Component("CLAIM-phone")
public class ClaimDvBeneficiaryPhoneResolver implements BeneficiaryPhoneResolver {

    private final ClaimRepository    claimRepository;
    private final CustomerRepository customerRepository;

    public ClaimDvBeneficiaryPhoneResolver(ClaimRepository claimRepository,
                                            CustomerRepository customerRepository) {
        this.claimRepository    = claimRepository;
        this.customerRepository = customerRepository;
    }

    @Override
    public Optional<String> resolve(CreditNote creditNote) {
        Optional<Claim> claimOpt = claimRepository.findById(creditNote.getEntityId());
        if (claimOpt.isEmpty()) return Optional.empty();
        Optional<Customer> customerOpt = customerRepository.findById(claimOpt.get().getCustomerId());
        if (customerOpt.isEmpty()) return Optional.empty();
        return Optional.ofNullable(customerOpt.get().getPhone()).filter(s -> !s.isBlank());
    }
}
