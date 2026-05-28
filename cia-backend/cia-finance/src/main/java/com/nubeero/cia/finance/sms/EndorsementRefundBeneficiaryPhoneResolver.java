package com.nubeero.cia.finance.sms;

import com.nubeero.cia.customer.Customer;
import com.nubeero.cia.customer.CustomerRepository;
import com.nubeero.cia.endorsement.Endorsement;
import com.nubeero.cia.endorsement.EndorsementRepository;
import com.nubeero.cia.finance.CreditNote;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the SMS recipient phone for {@link com.nubeero.cia.finance.FinanceEntityType#ENDORSEMENT}
 * credit notes — endorsement refunds to the policyholder. Uses the
 * denormalised {@code endorsement.customerId} short-circuit (same hop
 * shape as slice β's BeneficiaryProfile resolver).
 *
 * @since R7 — SMS dispatch
 */
@Component("ENDORSEMENT-phone")
public class EndorsementRefundBeneficiaryPhoneResolver implements BeneficiaryPhoneResolver {

    private final EndorsementRepository endorsementRepository;
    private final CustomerRepository    customerRepository;

    public EndorsementRefundBeneficiaryPhoneResolver(
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
        return Optional.ofNullable(custOpt.get().getPhone()).filter(s -> !s.isBlank());
    }
}
