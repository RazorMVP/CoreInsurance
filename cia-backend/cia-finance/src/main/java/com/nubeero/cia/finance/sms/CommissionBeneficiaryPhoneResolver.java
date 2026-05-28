package com.nubeero.cia.finance.sms;

import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.setup.org.Agent;
import com.nubeero.cia.setup.org.AgentRepository;
import com.nubeero.cia.setup.org.Broker;
import com.nubeero.cia.setup.org.BrokerRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the SMS recipient phone for {@link com.nubeero.cia.finance.FinanceEntityType#COMMISSION}
 * credit notes — tries Broker first (more common), falls back to Agent.
 *
 * @since R7 — SMS dispatch
 */
@Component("COMMISSION-phone")
public class CommissionBeneficiaryPhoneResolver implements BeneficiaryPhoneResolver {

    private final BrokerRepository brokerRepository;
    private final AgentRepository  agentRepository;

    public CommissionBeneficiaryPhoneResolver(BrokerRepository brokerRepository,
                                               AgentRepository agentRepository) {
        this.brokerRepository = brokerRepository;
        this.agentRepository  = agentRepository;
    }

    @Override
    public Optional<String> resolve(CreditNote creditNote) {
        Optional<Broker> brokerOpt = brokerRepository.findById(creditNote.getBeneficiaryId());
        if (brokerOpt.isPresent()) {
            return Optional.ofNullable(brokerOpt.get().getPhone()).filter(s -> !s.isBlank());
        }
        Optional<Agent> agentOpt = agentRepository.findById(creditNote.getBeneficiaryId());
        if (agentOpt.isPresent()) {
            return Optional.ofNullable(agentOpt.get().getPhone()).filter(s -> !s.isBlank());
        }
        return Optional.empty();
    }
}
