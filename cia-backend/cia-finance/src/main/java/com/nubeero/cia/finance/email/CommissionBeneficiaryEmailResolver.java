package com.nubeero.cia.finance.email;

import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.setup.org.Agent;
import com.nubeero.cia.setup.org.AgentRepository;
import com.nubeero.cia.setup.org.Broker;
import com.nubeero.cia.setup.org.BrokerRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the email recipient for {@link com.nubeero.cia.finance.FinanceEntityType#COMMISSION}
 * credit notes — tries Broker first (more common), falls back to Agent.
 *
 * @since Slice γ — Task 16, F7 email transmission
 */
@Component("COMMISSION-email")
public class CommissionBeneficiaryEmailResolver implements BeneficiaryEmailResolver {

    private final BrokerRepository brokerRepository;
    private final AgentRepository  agentRepository;

    public CommissionBeneficiaryEmailResolver(BrokerRepository brokerRepository,
                                                AgentRepository agentRepository) {
        this.brokerRepository = brokerRepository;
        this.agentRepository  = agentRepository;
    }

    @Override
    public Optional<String> resolve(CreditNote creditNote) {
        Optional<Broker> brokerOpt = brokerRepository.findById(creditNote.getBeneficiaryId());
        if (brokerOpt.isPresent()) {
            return Optional.ofNullable(brokerOpt.get().getEmail()).filter(s -> !s.isBlank());
        }
        Optional<Agent> agentOpt = agentRepository.findById(creditNote.getBeneficiaryId());
        if (agentOpt.isPresent()) {
            return Optional.ofNullable(agentOpt.get().getEmail()).filter(s -> !s.isBlank());
        }
        return Optional.empty();
    }
}
