package com.nubeero.cia.finance.pdf;

import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.setup.org.Agent;
import com.nubeero.cia.setup.org.AgentRepository;
import com.nubeero.cia.setup.org.Broker;
import com.nubeero.cia.setup.org.BrokerRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the beneficiary profile for {@link com.nubeero.cia.finance.FinanceEntityType#COMMISSION}
 * credit notes.
 *
 * <p>{@code CreditNote.beneficiaryId} is either a Broker or an Agent UUID.
 * We try Broker first (more common), then fall back to Agent. Both
 * entities have plain (non-encrypted) {@code address} columns — no
 * decryption involved.
 *
 * <p>Returns {@code null} when neither lookup hits; dispatcher falls back
 * to the denormalised {@code creditNote.beneficiaryName}.
 *
 * @since Slice β — Task 6, F7 payment-voucher PDF generation
 */
@Component("COMMISSION-profile")
public class CommissionBeneficiaryProfileResolver implements BeneficiaryProfileResolver {

    private final BrokerRepository brokerRepository;
    private final AgentRepository  agentRepository;

    public CommissionBeneficiaryProfileResolver(BrokerRepository brokerRepository,
                                                  AgentRepository agentRepository) {
        this.brokerRepository = brokerRepository;
        this.agentRepository  = agentRepository;
    }

    @Override
    public BeneficiaryProfile resolve(CreditNote creditNote) {
        Optional<Broker> brokerOpt = brokerRepository.findById(creditNote.getBeneficiaryId());
        if (brokerOpt.isPresent()) {
            Broker b = brokerOpt.get();
            return new BeneficiaryProfile(b.getName(), b.getAddress(), null);
        }
        Optional<Agent> agentOpt = agentRepository.findById(creditNote.getBeneficiaryId());
        if (agentOpt.isPresent()) {
            Agent a = agentOpt.get();
            return new BeneficiaryProfile(a.getName(), a.getAddress(), null);
        }
        return null;
    }
}
