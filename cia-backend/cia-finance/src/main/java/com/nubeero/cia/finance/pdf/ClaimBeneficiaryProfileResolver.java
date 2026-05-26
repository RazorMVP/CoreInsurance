package com.nubeero.cia.finance.pdf;

import com.nubeero.cia.claims.Claim;
import com.nubeero.cia.claims.ClaimRepository;
import com.nubeero.cia.customer.Customer;
import com.nubeero.cia.customer.CustomerRepository;
import com.nubeero.cia.customer.CustomerType;
import com.nubeero.cia.finance.CreditNote;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the beneficiary profile for {@link com.nubeero.cia.finance.FinanceEntityType#CLAIM}
 * credit notes (Discharge Voucher payouts).
 *
 * <p>Loads {@code Claim} by {@code creditNote.entityId}, then loads
 * {@code Customer} by {@code claim.customerId}. {@code Customer.address}
 * is encrypted at rest (NDPR) and auto-decrypts via JPA
 * {@code @ColumnTransformer} on read.
 *
 * <p>Returns {@code null} when the claim is missing (dispatcher falls back
 * to denormalised {@code creditNote.beneficiaryName}). If the customer is
 * missing, returns the claim's denormalised {@code customerName} with no
 * address.
 *
 * @since Slice β — Task 5, F7 payment-voucher PDF generation
 */
@Component("CLAIM-profile")
public class ClaimBeneficiaryProfileResolver implements BeneficiaryProfileResolver {

    private final ClaimRepository    claimRepository;
    private final CustomerRepository customerRepository;

    public ClaimBeneficiaryProfileResolver(ClaimRepository claimRepository,
                                            CustomerRepository customerRepository) {
        this.claimRepository    = claimRepository;
        this.customerRepository = customerRepository;
    }

    @Override
    public BeneficiaryProfile resolve(CreditNote creditNote) {
        Optional<Claim> claimOpt = claimRepository.findById(creditNote.getEntityId());
        if (claimOpt.isEmpty()) return null;
        Claim claim = claimOpt.get();

        Optional<Customer> customerOpt = customerRepository.findById(claim.getCustomerId());
        if (customerOpt.isEmpty()) {
            return BeneficiaryProfile.nameOnly(claim.getCustomerName());
        }
        Customer customer = customerOpt.get();

        String name = customerDisplayName(customer);
        String address = customer.getAddress(); // decrypted via @ColumnTransformer
        return new BeneficiaryProfile(name, address, null);
    }

    private static String customerDisplayName(Customer c) {
        return c.getCustomerType() == CustomerType.INDIVIDUAL
                ? (c.getFirstName() + " " + c.getLastName()).trim()
                : c.getCompanyName();
    }
}
