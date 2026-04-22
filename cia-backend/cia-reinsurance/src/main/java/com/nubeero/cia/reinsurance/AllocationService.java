package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.event.PolicyApprovedEvent;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AllocationService {

    private final RiAllocationRepository allocationRepository;
    private final RiTreatyRepository treatyRepository;
    private final RiNumberService numberService;

    // ─── Auto-allocate on policy approval ────────────────────────────────

    @Transactional
    public void autoAllocate(PolicyApprovedEvent event) {
        int treatyYear = event.policyStartDate().getYear();

        // Try SURPLUS first, then QUOTA_SHARE, then XOL
        for (TreatyType type : List.of(TreatyType.SURPLUS, TreatyType.QUOTA_SHARE, TreatyType.XOL)) {
            List<RiTreaty> candidates = treatyRepository.findActiveTreaties(
                    treatyYear, event.classOfBusinessId(), event.productId(), type);

            if (!candidates.isEmpty()) {
                RiTreaty treaty = candidates.get(0); // most specific match first (ordered by query)
                RiAllocation allocation = buildAllocation(event.policyId(), event.policyNumber(),
                        null, treaty, event.totalSumInsured(), event.netPremium(), event.currencyCode());
                allocationRepository.save(allocation);
                log.info("Auto-allocated policy {} to {} treaty {}", event.policyNumber(), type, treaty.getId());
                return;
            }
        }

        log.info("No active RI treaty found for policy {} (year={}, cob={})",
                event.policyNumber(), treatyYear, event.classOfBusinessId());
    }

    // ─── Manual allocation (or reallocation after endorsement) ───────────

    @Transactional
    public RiAllocation allocate(UUID policyId, String policyNumber, UUID treatyId,
                                 BigDecimal sumInsured, BigDecimal premium, String currencyCode,
                                 UUID endorsementId) {
        RiTreaty treaty = treatyRepository.findByIdAndDeletedAtIsNull(treatyId)
                .orElseThrow(() -> new ResourceNotFoundException("RiTreaty", treatyId));

        if (treaty.getStatus() != TreatyStatus.ACTIVE) {
            throw new BusinessRuleException("TREATY_NOT_ACTIVE",
                    "Treaty must be ACTIVE to allocate risks");
        }

        RiAllocation allocation = buildAllocation(policyId, policyNumber, endorsementId,
                treaty, sumInsured, premium, currencyCode);
        return allocationRepository.save(allocation);
    }

    @Transactional
    public RiAllocation confirm(UUID id) {
        RiAllocation allocation = findOrThrow(id);
        if (allocation.getStatus() != AllocationStatus.DRAFT) {
            throw new BusinessRuleException("INVALID_ALLOCATION_STATUS",
                    "Only DRAFT allocations can be confirmed");
        }
        allocation.setStatus(AllocationStatus.CONFIRMED);
        return allocationRepository.save(allocation);
    }

    @Transactional
    public RiAllocation cancel(UUID id) {
        RiAllocation allocation = findOrThrow(id);
        if (allocation.getStatus() == AllocationStatus.CANCELLED) {
            throw new BusinessRuleException("INVALID_ALLOCATION_STATUS",
                    "Allocation is already cancelled");
        }
        allocation.setStatus(AllocationStatus.CANCELLED);
        return allocationRepository.save(allocation);
    }

    public RiAllocation findOrThrow(UUID id) {
        return allocationRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("RiAllocation", id));
    }

    public Page<RiAllocation> list(UUID policyId, AllocationStatus status, Pageable pageable) {
        return allocationRepository.findAll(policyId, status, pageable);
    }

    // ─── Allocation calculation ───────────────────────────────────────────

    private RiAllocation buildAllocation(UUID policyId, String policyNumber, UUID endorsementId,
                                          RiTreaty treaty, BigDecimal sumInsured,
                                          BigDecimal premium, String currencyCode) {
        RiAllocation allocation = RiAllocation.builder()
                .allocationNumber(numberService.nextAllocationNumber())
                .policyId(policyId)
                .policyNumber(policyNumber)
                .endorsementId(endorsementId)
                .treaty(treaty)
                .treatyType(treaty.getTreatyType())
                .ourShareSumInsured(sumInsured)
                .ourSharePremium(premium)
                .currencyCode(currencyCode)
                .build();

        switch (treaty.getTreatyType()) {
            case SURPLUS -> applySurplus(allocation, treaty, sumInsured, premium);
            case QUOTA_SHARE -> applyQuotaShare(allocation, treaty, sumInsured, premium);
            case XOL -> applyXol(allocation, treaty, sumInsured, premium);
        }

        return allocation;
    }

    private void applySurplus(RiAllocation allocation, RiTreaty treaty,
                               BigDecimal sumInsured, BigDecimal premium) {
        BigDecimal retention = treaty.getRetentionLimit();
        BigDecimal retainedSI = sumInsured.min(retention);
        BigDecimal toAllocate = sumInsured.subtract(retainedSI);
        BigDecimal capacity = Optional.ofNullable(treaty.getSurplusCapacity()).orElse(BigDecimal.ZERO);
        BigDecimal cededSI = toAllocate.min(capacity);
        BigDecimal excessSI = toAllocate.subtract(cededSI);

        BigDecimal retainedPrem = sumInsured.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : premium.multiply(retainedSI).divide(sumInsured, 2, RoundingMode.HALF_UP);
        BigDecimal cededPrem = premium.subtract(retainedPrem);

        allocation.setRetainedAmount(retainedSI);
        allocation.setCededAmount(cededSI);
        allocation.setExcessAmount(excessSI);
        allocation.setRetainedPremium(retainedPrem);
        allocation.setCededPremium(cededPrem);

        if (cededSI.compareTo(BigDecimal.ZERO) > 0) {
            distributeToLines(allocation, treaty, cededSI, cededPrem);
        }
    }

    private void applyQuotaShare(RiAllocation allocation, RiTreaty treaty,
                                  BigDecimal sumInsured, BigDecimal premium) {
        List<RiTreatyParticipant> participants = treaty.getParticipants().stream()
                .filter(p -> p.getDeletedAt() == null)
                .toList();

        BigDecimal totalCededPct = participants.stream()
                .map(RiTreatyParticipant::getSharePercentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal cededSI = sumInsured.multiply(totalCededPct)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal retainedSI = sumInsured.subtract(cededSI);
        BigDecimal cededPrem = premium.multiply(totalCededPct)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal retainedPrem = premium.subtract(cededPrem);

        allocation.setRetainedAmount(retainedSI);
        allocation.setCededAmount(cededSI);
        allocation.setExcessAmount(BigDecimal.ZERO);
        allocation.setRetainedPremium(retainedPrem);
        allocation.setCededPremium(cededPrem);

        distributeToLines(allocation, treaty, cededSI, cededPrem);
    }

    private void applyXol(RiAllocation allocation, RiTreaty treaty,
                           BigDecimal sumInsured, BigDecimal premium) {
        // XOL is a blanket loss cover, not a per-policy premium split.
        // We retain 100% of the premium basis; XOL protection is recorded as a treaty reference.
        allocation.setRetainedAmount(sumInsured);
        allocation.setCededAmount(BigDecimal.ZERO);
        allocation.setExcessAmount(BigDecimal.ZERO);
        allocation.setRetainedPremium(premium);
        allocation.setCededPremium(BigDecimal.ZERO);
        // No allocation lines for XOL
    }

    private void distributeToLines(RiAllocation allocation, RiTreaty treaty,
                                    BigDecimal cededSI, BigDecimal cededPrem) {
        List<RiTreatyParticipant> participants = treaty.getParticipants().stream()
                .filter(p -> p.getDeletedAt() == null)
                .toList();

        BigDecimal totalPct = participants.stream()
                .map(RiTreatyParticipant::getSharePercentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalPct.compareTo(BigDecimal.ZERO) == 0) return;

        for (RiTreatyParticipant p : participants) {
            BigDecimal pctOfCeded = p.getSharePercentage().divide(totalPct, 10, RoundingMode.HALF_UP);
            BigDecimal lineSI = cededSI.multiply(pctOfCeded).setScale(2, RoundingMode.HALF_UP);
            BigDecimal linePrem = cededPrem.multiply(pctOfCeded).setScale(2, RoundingMode.HALF_UP);
            BigDecimal commission = linePrem.multiply(p.getCommissionRate())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            RiAllocationLine line = RiAllocationLine.builder()
                    .allocation(allocation)
                    .participant(p)
                    .reinsuranceCompanyId(p.getReinsuranceCompanyId())
                    .reinsuranceCompanyName(p.getReinsuranceCompanyName())
                    .sharePercentage(p.getSharePercentage())
                    .cededAmount(lineSI)
                    .cededPremium(linePrem)
                    .commissionRate(p.getCommissionRate())
                    .commissionAmount(commission)
                    .build();

            allocation.getLines().add(line);
        }
    }
}
