package com.nubeero.cia.finance.email;

import com.nubeero.cia.customer.Customer;
import com.nubeero.cia.customer.CustomerRepository;
import com.nubeero.cia.endorsement.Endorsement;
import com.nubeero.cia.endorsement.EndorsementRepository;
import com.nubeero.cia.finance.CreditNote;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the email recipient for {@link com.nubeero.cia.finance.FinanceEntityType#ENDORSEMENT}
 * credit notes — endorsement refunds to the policyholder. Uses the
 * denormalised {@code endorsement.customerId} short-circuit (same hop
 * shape as slice β's BeneficiaryProfile resolver).
 *
 * @since Slice γ — Task 18, F7 email transmission
 */
@Component("ENDORSEMENT-email")
public class EndorsementRefundBeneficiaryEmailResolver implements BeneficiaryEmailResolver {

    private final EndorsementRepository endorsementRepository;
    private final CustomerRepository    customerRepository;

    public EndorsementRefundBeneficiaryEmailResolver(
            EndorsementRepository endorsementRepository,
            CustomerRepository    customerRepository) {
        this.endorsementRepository = endorsementRepository;
        this.customerRepository    = customerRepository;
    }

    @Override
    public Optional<String> resolve(CreditNote creditNote) {
        Optional<Endorsement> endOpt = endorsementRepository.findById(creditNote.getEntityId());
        if (endOpt.isEmpty()) return Optional.empty();
        Optional<Customer> custOpt = customerRepository.findById(endOpt.get().getCustomerId());
        if (custOpt.isEmpty()) return Optional.empty();
        return Optional.ofNullable(custOpt.get().getEmail()).filter(s -> !s.isBlank());
    }
}
