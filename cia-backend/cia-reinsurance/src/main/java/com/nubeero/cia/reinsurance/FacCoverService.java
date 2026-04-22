package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.event.FacPremiumCededEvent;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.reinsurance.dto.CreateFacCoverRequest;
import com.nubeero.cia.setup.org.ReinsuranceCompany;
import com.nubeero.cia.setup.org.ReinsuranceCompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FacCoverService {

    private final RiFacCoverRepository facCoverRepository;
    private final ReinsuranceCompanyRepository reinsuranceCompanyRepository;
    private final RiNumberService numberService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public RiFacCover create(CreateFacCoverRequest req) {
        ReinsuranceCompany riCo = reinsuranceCompanyRepository.findByIdAndDeletedAtIsNull(
                req.reinsuranceCompanyId())
                .orElseThrow(() -> new ResourceNotFoundException("ReinsuranceCompany",
                        req.reinsuranceCompanyId()));

        BigDecimal premiumCeded = req.sumInsuredCeded()
                .multiply(req.premiumRate())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        BigDecimal commissionRate = req.commissionRate() != null ? req.commissionRate() : BigDecimal.ZERO;
        BigDecimal commissionAmount = premiumCeded.multiply(commissionRate)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        BigDecimal netPremium = premiumCeded.subtract(commissionAmount);

        RiFacCover cover = RiFacCover.builder()
                .facReference(numberService.nextFacReference())
                .policyId(req.policyId())
                .policyNumber(req.policyNumber())
                .reinsuranceCompanyId(riCo.getId())
                .reinsuranceCompanyName(riCo.getName())
                .sumInsuredCeded(req.sumInsuredCeded())
                .premiumRate(req.premiumRate())
                .premiumCeded(premiumCeded)
                .commissionRate(commissionRate)
                .commissionAmount(commissionAmount)
                .netPremium(netPremium)
                .currencyCode(req.currencyCode() != null ? req.currencyCode() : "NGN")
                .coverFrom(req.coverFrom())
                .coverTo(req.coverTo())
                .offerSlipReference(req.offerSlipReference())
                .terms(req.terms())
                .build();

        return facCoverRepository.save(cover);
    }

    @Transactional
    public RiFacCover confirm(UUID id) {
        RiFacCover cover = findOrThrow(id);
        if (cover.getStatus() != FacCoverStatus.PENDING) {
            throw new BusinessRuleException("INVALID_FAC_STATUS",
                    "Only PENDING FAC covers can be confirmed");
        }

        String username = currentUsername();
        cover.setStatus(FacCoverStatus.CONFIRMED);
        cover.setApprovedBy(username);
        cover.setApprovedAt(Instant.now());
        RiFacCover saved = facCoverRepository.save(cover);

        eventPublisher.publishEvent(new FacPremiumCededEvent(
                saved.getId(), saved.getFacReference(),
                saved.getPolicyId(), saved.getPolicyNumber(),
                saved.getReinsuranceCompanyId(), saved.getReinsuranceCompanyName(),
                saved.getPremiumCeded(), saved.getCommissionAmount(), saved.getNetPremium(),
                saved.getCurrencyCode()));

        return saved;
    }

    @Transactional
    public RiFacCover cancel(UUID id, String reason) {
        RiFacCover cover = findOrThrow(id);
        if (cover.getStatus() == FacCoverStatus.CANCELLED) {
            throw new BusinessRuleException("INVALID_FAC_STATUS", "FAC cover is already cancelled");
        }
        String username = currentUsername();
        cover.setStatus(FacCoverStatus.CANCELLED);
        cover.setCancelledBy(username);
        cover.setCancelledAt(Instant.now());
        cover.setCancellationReason(reason);
        return facCoverRepository.save(cover);
    }

    public RiFacCover findOrThrow(UUID id) {
        return facCoverRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("RiFacCover", id));
    }

    public Page<RiFacCover> list(UUID policyId, FacCoverStatus status,
                                  UUID reinsuranceCompanyId, Pageable pageable) {
        return facCoverRepository.findAll(policyId, status, reinsuranceCompanyId, pageable);
    }

    private String currentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getClaimAsString("preferred_username");
        }
        return "system";
    }
}
