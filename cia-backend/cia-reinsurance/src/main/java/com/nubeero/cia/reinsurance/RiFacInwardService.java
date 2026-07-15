package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.event.RiFacInwardAcceptedEvent;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.documents.DocumentGenerationService;
import com.nubeero.cia.documents.InwardFacGuarantyContext;
import com.nubeero.cia.reinsurance.dto.CreateFacInwardRequest;
import com.nubeero.cia.reinsurance.dto.ExtendFacInwardRequest;
import com.nubeero.cia.reinsurance.dto.RenewFacInwardRequest;
import com.nubeero.cia.setup.org.InsuranceCompany;
import com.nubeero.cia.setup.org.InsuranceCompanyRepository;
import com.nubeero.cia.setup.product.ClassOfBusiness;
import com.nubeero.cia.setup.product.ClassOfBusinessRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Orchestrates inward facultative reinsurance cover lifecycle: create, renew,
 * extend, cancel. Mirrors the outward {@link FacCoverService} shape, but a
 * ceding company (not a reinsurer) is the counterparty, and inward covers
 * carry their own renew/extend semantics (outward FAC v1 has neither).
 */
@Service
@RequiredArgsConstructor
public class RiFacInwardService {

    private static final int SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final RiFacInwardRepository repository;
    private final RiNumberService numberService;
    private final InsuranceCompanyRepository insuranceCompanyRepository;
    private final ClassOfBusinessRepository classOfBusinessRepository;
    private final DocumentGenerationService documentGenerationService;
    private final ApplicationEventPublisher eventPublisher;

    /** Pure amount computation — unit-tested independently. */
    record Amounts(BigDecimal acceptedSumInsured, BigDecimal grossPremium,
                   BigDecimal commissionAmount, BigDecimal netPremium) {}

    static Amounts computeAmounts(BigDecimal sumInsured, BigDecimal sharePct,
                                  BigDecimal rate, BigDecimal commissionRate) {
        BigDecimal accepted = sumInsured.multiply(sharePct)
                .divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
        BigDecimal gross = accepted.multiply(rate)
                .divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
        BigDecimal cr = commissionRate != null ? commissionRate : BigDecimal.ZERO;
        BigDecimal commission = gross.multiply(cr)
                .divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
        BigDecimal net = gross.subtract(commission);
        return new Amounts(accepted, gross, commission, net);
    }

    @Transactional
    public RiFacInward create(CreateFacInwardRequest req) {
        InsuranceCompany ceding = insuranceCompanyRepository.findByIdAndDeletedAtIsNull(req.cedingCompanyId())
                .orElseThrow(() -> new ResourceNotFoundException("InsuranceCompany", req.cedingCompanyId()));
        ClassOfBusiness cob = classOfBusinessRepository.findByIdAndDeletedAtIsNull(req.classOfBusinessId())
                .orElseThrow(() -> new ResourceNotFoundException("ClassOfBusiness", req.classOfBusinessId()));
        if (!req.coverTo().isAfter(req.coverFrom())) {
            throw new BusinessRuleException("INVALID_COVER_PERIOD", "coverTo must be after coverFrom");
        }

        Amounts a = computeAmounts(req.sumInsured(), req.ourSharePct(), req.premiumRate(), req.commissionRate());
        BigDecimal commissionRate = req.commissionRate() != null ? req.commissionRate() : BigDecimal.ZERO;

        RiFacInward inward = RiFacInward.builder()
                .facInwardReference(numberService.nextInwardFacReference())
                .cedingCompanyId(ceding.getId())
                .cedingCompanyName(ceding.getName())
                .classOfBusinessId(cob.getId())
                .classOfBusinessName(cob.getName())
                .riskDescription(req.riskDescription())
                .sumInsured(req.sumInsured())
                .ourSharePct(req.ourSharePct())
                .acceptedSumInsured(a.acceptedSumInsured())
                .premiumRate(req.premiumRate())
                .grossPremium(a.grossPremium())
                .commissionRate(commissionRate)
                .commissionAmount(a.commissionAmount())
                .netPremium(a.netPremium())
                .currencyCode(req.currencyCode() != null ? req.currencyCode() : "NGN")
                .coverFrom(req.coverFrom())
                .coverTo(req.coverTo())
                .status(RiFacInwardStatus.ACTIVE)
                .build();

        RiFacInward saved = repository.save(inward);
        generateGuaranty(saved);
        publishAccepted(saved, saved.getGrossPremium(), saved.getCommissionAmount(), saved.getNetPremium());
        return saved;
    }

    @Transactional
    public RiFacInward renew(UUID sourceId, RenewFacInwardRequest req) {
        RiFacInward source = findOrThrow(sourceId);
        if (source.getStatus() != RiFacInwardStatus.ACTIVE) {
            throw new BusinessRuleException("INVALID_FAC_INWARD_STATUS", "Only ACTIVE covers can be renewed");
        }
        if (!req.coverTo().isAfter(req.coverFrom())) {
            throw new BusinessRuleException("INVALID_COVER_PERIOD", "coverTo must be after coverFrom");
        }

        // Premium terms carry over from source; recompute for the new term.
        Amounts a = computeAmounts(source.getSumInsured(), source.getOurSharePct(),
                source.getPremiumRate(), source.getCommissionRate());

        RiFacInward renewed = RiFacInward.builder()
                .facInwardReference(numberService.nextInwardFacReference())
                .cedingCompanyId(source.getCedingCompanyId())
                .cedingCompanyName(source.getCedingCompanyName())
                .classOfBusinessId(source.getClassOfBusinessId())
                .classOfBusinessName(source.getClassOfBusinessName())
                .riskDescription(source.getRiskDescription())
                .sumInsured(source.getSumInsured())
                .ourSharePct(source.getOurSharePct())
                .acceptedSumInsured(a.acceptedSumInsured())
                .premiumRate(source.getPremiumRate())
                .grossPremium(a.grossPremium())
                .commissionRate(source.getCommissionRate())
                .commissionAmount(a.commissionAmount())
                .netPremium(a.netPremium())
                .currencyCode(source.getCurrencyCode())
                .coverFrom(req.coverFrom())
                .coverTo(req.coverTo())
                .status(RiFacInwardStatus.ACTIVE)
                .renewedFromId(source.getId())
                .build();

        RiFacInward saved = repository.save(renewed);
        source.setStatus(RiFacInwardStatus.RENEWED);
        repository.save(source);

        generateGuaranty(saved);
        publishAccepted(saved, saved.getGrossPremium(), saved.getCommissionAmount(), saved.getNetPremium());
        return saved;
    }

    @Transactional
    public RiFacInward extend(UUID id, ExtendFacInwardRequest req) {
        RiFacInward cover = findOrThrow(id);
        if (cover.getStatus() != RiFacInwardStatus.ACTIVE) {
            throw new BusinessRuleException("INVALID_FAC_INWARD_STATUS", "Only ACTIVE covers can be extended");
        }
        if (!req.newCoverTo().isAfter(cover.getCoverTo())) {
            throw new BusinessRuleException("INVALID_COVER_PERIOD", "newCoverTo must be after the current coverTo");
        }

        // Incremental pro-rata premium for the extra days (endorsement idiom):
        // delta gross = gross_premium / originalDays × extraDays.
        long originalDays = ChronoUnit.DAYS.between(cover.getCoverFrom(), cover.getCoverTo()) + 1L;
        long extraDays = ChronoUnit.DAYS.between(cover.getCoverTo(), req.newCoverTo()); // exclusive of the old end day
        BigDecimal deltaGross = cover.getGrossPremium()
                .multiply(BigDecimal.valueOf(extraDays))
                .divide(BigDecimal.valueOf(originalDays), SCALE, RoundingMode.HALF_UP);
        BigDecimal deltaCommission = deltaGross.multiply(cover.getCommissionRate())
                .divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
        BigDecimal deltaNet = deltaGross.subtract(deltaCommission);

        cover.setCoverTo(req.newCoverTo());
        RiFacInward saved = repository.save(cover);

        // The extension is an incremental transaction: a separate receivable for
        // the delta (the original premium fields represent the original term).
        publishAccepted(saved, deltaGross, deltaCommission, deltaNet);
        return saved;
    }

    @Transactional
    public RiFacInward cancel(UUID id, String reason) {
        RiFacInward cover = findOrThrow(id);
        if (cover.getStatus() == RiFacInwardStatus.CANCELLED) {
            throw new BusinessRuleException("INVALID_FAC_INWARD_STATUS", "Inward FAC cover is already cancelled");
        }
        cover.setStatus(RiFacInwardStatus.CANCELLED);
        cover.setCancelledBy(currentUsername());
        cover.setCancelledAt(Instant.now());
        cover.setCancellationReason(reason);
        return repository.save(cover);
    }

    public RiFacInward findOrThrow(UUID id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("RiFacInward", id));
    }

    public Page<RiFacInward> list(UUID cedingCompanyId, UUID classId,
                                  RiFacInwardStatus status, Pageable pageable) {
        return repository.findAll(cedingCompanyId, classId, status, pageable);
    }

    private void generateGuaranty(RiFacInward c) {
        String path = documentGenerationService.generateInwardFacGuaranty(new InwardFacGuarantyContext(
                c.getId(), c.getFacInwardReference(), c.getClassOfBusinessId(),
                c.getCedingCompanyName(), c.getClassOfBusinessName(), c.getRiskDescription(),
                c.getSumInsured(), c.getOurSharePct(), c.getAcceptedSumInsured(),
                c.getGrossPremium(), c.getCommissionAmount(), c.getNetPremium(),
                c.getCurrencyCode(), c.getCoverFrom(), c.getCoverTo()));
        if (path != null) {
            c.setGuarantyDocumentPath(path);
            repository.save(c);
        }
    }

    private void publishAccepted(RiFacInward c, BigDecimal gross, BigDecimal commission, BigDecimal net) {
        eventPublisher.publishEvent(new RiFacInwardAcceptedEvent(
                c.getId(), c.getFacInwardReference(),
                c.getCedingCompanyId(), c.getCedingCompanyName(), c.getClassOfBusinessId(),
                gross, commission, net, c.getCurrencyCode()));
    }

    private String currentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getClaimAsString("preferred_username");
        }
        return "system";
    }
}
