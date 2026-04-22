package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.product.dto.SurveyThresholdRequest;
import com.nubeero.cia.setup.product.dto.SurveyThresholdResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SurveyThresholdService {

    private final SurveyThresholdRepository repository;
    private final ProductRepository productRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<SurveyThresholdResponse> listByProduct(UUID productId) {
        return repository.findAllByProductIdAndDeletedAtIsNull(productId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public SurveyThresholdResponse get(UUID productId, UUID id) {
        return toResponse(findOrThrow(productId, id));
    }

    @Transactional
    public SurveyThresholdResponse create(UUID productId, SurveyThresholdRequest request) {
        Product product = resolveProduct(productId);
        SurveyThreshold entity = SurveyThreshold.builder()
                .product(product)
                .thresholdType(request.getThresholdType())
                .minSumInsured(request.getMinSumInsured())
                .build();
        SurveyThreshold saved = repository.save(entity);
        auditService.log("SurveyThreshold", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public SurveyThresholdResponse update(UUID productId, UUID id, SurveyThresholdRequest request) {
        SurveyThreshold entity = findOrThrow(productId, id);
        entity.setThresholdType(request.getThresholdType());
        entity.setMinSumInsured(request.getMinSumInsured());
        SurveyThreshold saved = repository.save(entity);
        auditService.log("SurveyThreshold", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID productId, UUID id) {
        SurveyThreshold entity = findOrThrow(productId, id);
        entity.softDelete();
        repository.save(entity);
        auditService.log("SurveyThreshold", id.toString(), AuditAction.DELETE, entity, null);
    }

    private Product resolveProduct(UUID productId) {
        return productRepository.findById(productId)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
    }

    private SurveyThreshold findOrThrow(UUID productId, UUID id) {
        SurveyThreshold entity = repository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("SurveyThreshold", id));
        if (!entity.getProduct().getId().equals(productId)) {
            throw new ResourceNotFoundException("SurveyThreshold", id);
        }
        return entity;
    }

    private SurveyThresholdResponse toResponse(SurveyThreshold e) {
        return SurveyThresholdResponse.builder()
                .id(e.getId()).productId(e.getProduct().getId())
                .thresholdType(e.getThresholdType()).minSumInsured(e.getMinSumInsured())
                .createdAt(e.getCreatedAt()).updatedAt(e.getUpdatedAt())
                .build();
    }
}
