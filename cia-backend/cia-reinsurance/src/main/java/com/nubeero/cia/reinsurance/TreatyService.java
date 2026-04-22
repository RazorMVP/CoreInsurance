package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.reinsurance.dto.AddParticipantRequest;
import com.nubeero.cia.reinsurance.dto.CreateTreatyRequest;
import com.nubeero.cia.setup.org.ReinsuranceCompany;
import com.nubeero.cia.setup.org.ReinsuranceCompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TreatyService {

    private final RiTreatyRepository treatyRepository;
    private final RiTreatyParticipantRepository participantRepository;
    private final ReinsuranceCompanyRepository reinsuranceCompanyRepository;

    @Transactional
    public RiTreaty create(CreateTreatyRequest req) {
        RiTreaty treaty = RiTreaty.builder()
                .treatyType(req.treatyType())
                .treatyYear(req.treatyYear())
                .productId(req.productId())
                .classOfBusinessId(req.classOfBusinessId())
                .retentionLimit(req.retentionLimit())
                .surplusCapacity(req.surplusCapacity())
                .xolPerRiskRetention(req.xolPerRiskRetention())
                .xolPerRiskLimit(req.xolPerRiskLimit())
                .currencyCode(req.currencyCode() != null ? req.currencyCode() : "NGN")
                .effectiveDate(req.effectiveDate())
                .expiryDate(req.expiryDate())
                .description(req.description())
                .build();
        return treatyRepository.save(treaty);
    }

    @Transactional
    public RiTreaty activate(UUID id) {
        RiTreaty treaty = findOrThrow(id);
        if (treaty.getStatus() != TreatyStatus.DRAFT) {
            throw new BusinessRuleException("INVALID_TREATY_STATUS",
                    "Only DRAFT treaties can be activated");
        }
        if (treaty.getParticipants().stream().noneMatch(p -> p.getDeletedAt() == null)) {
            throw new BusinessRuleException("NO_PARTICIPANTS",
                    "A treaty must have at least one participant before activation");
        }
        treaty.setStatus(TreatyStatus.ACTIVE);
        return treatyRepository.save(treaty);
    }

    @Transactional
    public RiTreaty expire(UUID id) {
        RiTreaty treaty = findOrThrow(id);
        if (treaty.getStatus() != TreatyStatus.ACTIVE) {
            throw new BusinessRuleException("INVALID_TREATY_STATUS",
                    "Only ACTIVE treaties can be expired");
        }
        treaty.setStatus(TreatyStatus.EXPIRED);
        return treatyRepository.save(treaty);
    }

    @Transactional
    public RiTreaty cancel(UUID id) {
        RiTreaty treaty = findOrThrow(id);
        if (treaty.getStatus() == TreatyStatus.CANCELLED) {
            throw new BusinessRuleException("INVALID_TREATY_STATUS", "Treaty is already cancelled");
        }
        treaty.setStatus(TreatyStatus.CANCELLED);
        return treatyRepository.save(treaty);
    }

    @Transactional
    public RiTreatyParticipant addParticipant(UUID treatyId, AddParticipantRequest req) {
        RiTreaty treaty = findOrThrow(treatyId);
        if (treaty.getStatus() == TreatyStatus.CANCELLED || treaty.getStatus() == TreatyStatus.EXPIRED) {
            throw new BusinessRuleException("INVALID_TREATY_STATUS",
                    "Cannot add participants to a " + treaty.getStatus() + " treaty");
        }

        ReinsuranceCompany riCo = reinsuranceCompanyRepository.findByIdAndDeletedAtIsNull(
                req.reinsuranceCompanyId())
                .orElseThrow(() -> new ResourceNotFoundException("ReinsuranceCompany",
                        req.reinsuranceCompanyId()));

        RiTreatyParticipant participant = RiTreatyParticipant.builder()
                .treaty(treaty)
                .reinsuranceCompanyId(riCo.getId())
                .reinsuranceCompanyName(riCo.getName())
                .sharePercentage(req.sharePercentage())
                .surplusLine(req.surplusLine())
                .lead(req.lead() != null && req.lead())
                .commissionRate(req.commissionRate() != null ? req.commissionRate() : java.math.BigDecimal.ZERO)
                .build();

        return participantRepository.save(participant);
    }

    @Transactional
    public void removeParticipant(UUID treatyId, UUID participantId) {
        RiTreatyParticipant participant = participantRepository.findByIdAndDeletedAtIsNull(participantId)
                .orElseThrow(() -> new ResourceNotFoundException("RiTreatyParticipant", participantId));
        if (!participant.getTreaty().getId().equals(treatyId)) {
            throw new BusinessRuleException("PARTICIPANT_TREATY_MISMATCH",
                    "Participant does not belong to this treaty");
        }
        participant.softDelete();
        participantRepository.save(participant);
    }

    public RiTreaty findOrThrow(UUID id) {
        return treatyRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("RiTreaty", id));
    }

    public Page<RiTreaty> list(TreatyType type, TreatyStatus status, Integer year, Pageable pageable) {
        return treatyRepository.findAll(type, status, year, pageable);
    }
}
