package com.nubeero.cia.finance.pdf;

import com.nubeero.cia.customer.Customer;
import com.nubeero.cia.customer.CustomerRepository;
import com.nubeero.cia.customer.CustomerType;
import com.nubeero.cia.endorsement.EndorsementRepository;
import com.nubeero.cia.finance.CreditNote;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the beneficiary profile for {@link com.nubeero.cia.finance.FinanceEntityType#ENDORSEMENT}
 * credit notes — endorsement refunds back to the policyholder.
 *
 * <p>{@code Endorsement} carries a denormalised {@code customerId} column
 * (snapshotted at endorsement creation) so this resolver short-circuits
 * the original plan's Endorsement → Policy → Customer chain to a direct
 * Endorsement → Customer hop. {@code Customer.address} auto-decrypts via
 * JPA {@code @ColumnTransformer}.
 *
 * <p>Returns {@code null} at any missing link; dispatcher falls back to
 * denormalised name.
 *
 * @since Slice β — Task 8, F7 payment-voucher PDF generation
 */
@Component("ENDORSEMENT-profile")
public class EndorsementRefundBeneficiaryProfileResolver implements BeneficiaryProfileResolver {

    private final EndorsementRepository endorsementRepository;
    private final CustomerRepository    customerRepository;

    public EndorsementRefundBeneficiaryProfileResolver(
            EndorsementRepository endorsementRepository,
            CustomerRepository    customerRepository) {
        this.endorsementRepository = endorsementRepository;
        this.customerRepository    = customerRepository;
    }

    @Override
    public BeneficiaryProfile resolve(CreditNote creditNote) {
        // Scalar projection ONLY — never load the full Endorsement entity here.
        // Doing so inside the payment write-tx pulls its @OneToMany(cascade=ALL,
        // orphanRemoval=true) `risks` collection into the session and the
        // voucher-PDF template query's autoflush then throws "Found shared
        // references to a collection: Endorsement.risks". cia-log 2026-06-28.
        var endOpt = endorsementRepository.findBeneficiaryView(creditNote.getEntityId());
        if (endOpt.isEmpty()) return null;
        var end = endOpt.get();

        Optional<Customer> custOpt = customerRepository.findById(end.getCustomerId());
        if (custOpt.isEmpty()) {
            return BeneficiaryProfile.nameOnly(end.getCustomerName());
        }
        Customer c = custOpt.get();

        String name = customerDisplayName(c);
        return new BeneficiaryProfile(name, c.getAddress(), null);
    }

    private static String customerDisplayName(Customer c) {
        return c.getCustomerType() == CustomerType.INDIVIDUAL
                ? (c.getFirstName() + " " + c.getLastName()).trim()
                : c.getCompanyName();
    }
}
