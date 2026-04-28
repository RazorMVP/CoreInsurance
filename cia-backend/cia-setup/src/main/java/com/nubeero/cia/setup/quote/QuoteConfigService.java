package com.nubeero.cia.setup.quote;

import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.quote.dto.AdjustmentTypeRequest;
import com.nubeero.cia.setup.quote.dto.AdjustmentTypeResponse;
import com.nubeero.cia.setup.quote.dto.QuoteConfigRequest;
import com.nubeero.cia.setup.quote.dto.QuoteConfigResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QuoteConfigService {

    private final QuoteDiscountTypeRepository discountTypeRepo;
    private final QuoteLoadingTypeRepository  loadingTypeRepo;
    private final QuoteConfigRepository       configRepo;

    // ── Quote Config (singleton) ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public QuoteConfigResponse getConfig() {
        return toConfigResponse(findOrCreateConfig());
    }

    @Transactional
    public QuoteConfigResponse updateConfig(QuoteConfigRequest request) {
        QuoteConfig config = findOrCreateConfig();
        config.setValidityDays(request.getValidityDays());
        config.setCalcSequence(request.getCalcSequence());
        return toConfigResponse(configRepo.save(config));
    }

    /** Exposed for use by QuoteService when computing expiry. */
    @Transactional(readOnly = true)
    public QuoteConfig fetchConfig() {
        return findOrCreateConfig();
    }

    private QuoteConfig findOrCreateConfig() {
        return configRepo.findFirstByDeletedAtIsNull()
                .orElseGet(() -> configRepo.save(
                        QuoteConfig.builder()
                                .validityDays(30)
                                .calcSequence(CalcSequence.LOADING_FIRST)
                                .build()));
    }

    // ── Discount types ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AdjustmentTypeResponse> listDiscountTypes() {
        return discountTypeRepo.findAllByDeletedAtIsNullOrderByNameAsc()
                .stream().map(this::toTypeResponse).toList();
    }

    @Transactional
    public AdjustmentTypeResponse createDiscountType(AdjustmentTypeRequest request) {
        if (discountTypeRepo.existsByNameIgnoreCaseAndDeletedAtIsNull(request.getName())) {
            throw new BusinessRuleException("DUPLICATE_DISCOUNT_TYPE",
                    "Discount type already exists: " + request.getName());
        }
        return toTypeResponse(discountTypeRepo.save(
                QuoteDiscountType.builder().name(request.getName().trim()).build()));
    }

    @Transactional
    public AdjustmentTypeResponse updateDiscountType(UUID id, AdjustmentTypeRequest request) {
        QuoteDiscountType type = discountTypeRepo.findById(id)
                .filter(t -> t.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("QuoteDiscountType", id));
        type.setName(request.getName().trim());
        return toTypeResponse(discountTypeRepo.save(type));
    }

    @Transactional
    public void deleteDiscountType(UUID id) {
        QuoteDiscountType type = discountTypeRepo.findById(id)
                .filter(t -> t.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("QuoteDiscountType", id));
        type.setDeletedAt(Instant.now());
        discountTypeRepo.save(type);
    }

    // ── Loading types ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AdjustmentTypeResponse> listLoadingTypes() {
        return loadingTypeRepo.findAllByDeletedAtIsNullOrderByNameAsc()
                .stream().map(this::toTypeResponse).toList();
    }

    @Transactional
    public AdjustmentTypeResponse createLoadingType(AdjustmentTypeRequest request) {
        if (loadingTypeRepo.existsByNameIgnoreCaseAndDeletedAtIsNull(request.getName())) {
            throw new BusinessRuleException("DUPLICATE_LOADING_TYPE",
                    "Loading type already exists: " + request.getName());
        }
        return toTypeResponse(loadingTypeRepo.save(
                QuoteLoadingType.builder().name(request.getName().trim()).build()));
    }

    @Transactional
    public AdjustmentTypeResponse updateLoadingType(UUID id, AdjustmentTypeRequest request) {
        QuoteLoadingType type = loadingTypeRepo.findById(id)
                .filter(t -> t.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("QuoteLoadingType", id));
        type.setName(request.getName().trim());
        return toTypeResponse(loadingTypeRepo.save(type));
    }

    @Transactional
    public void deleteLoadingType(UUID id) {
        QuoteLoadingType type = loadingTypeRepo.findById(id)
                .filter(t -> t.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("QuoteLoadingType", id));
        type.setDeletedAt(Instant.now());
        loadingTypeRepo.save(type);
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private AdjustmentTypeResponse toTypeResponse(QuoteDiscountType t) {
        return AdjustmentTypeResponse.builder()
                .id(t.getId()).name(t.getName()).createdAt(t.getCreatedAt()).build();
    }

    private AdjustmentTypeResponse toTypeResponse(QuoteLoadingType t) {
        return AdjustmentTypeResponse.builder()
                .id(t.getId()).name(t.getName()).createdAt(t.getCreatedAt()).build();
    }

    private QuoteConfigResponse toConfigResponse(QuoteConfig c) {
        return QuoteConfigResponse.builder()
                .id(c.getId())
                .validityDays(c.getValidityDays())
                .calcSequence(c.getCalcSequence())
                .build();
    }
}
