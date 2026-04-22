package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.product.dto.CommissionSetupRequest;
import com.nubeero.cia.setup.product.dto.CommissionSetupResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CommissionSetupService {

    private final CommissionSetupRepository repository;
    private final ProductRepository productRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<CommissionSetupResponse> listByProduct(UUID productId) {
        return repository.findAllByProductIdAndDeletedAtIsNull(productId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public CommissionSetupResponse get(UUID productId, UUID id) {
        return toResponse(findOrThrow(productId, id));
    }

    @Transactional
    public CommissionSetupResponse create(UUID productId, CommissionSetupRequest request) {
        Product product = resolveProduct(productId);
        CommissionSetup entity = CommissionSetup.builder()
                .product(product)
                .brokerType(request.getBrokerType())
                .rate(request.getRate())
                .effectiveFrom(request.getEffectiveFrom())
                .effectiveTo(request.getEffectiveTo())
                .build();
        CommissionSetup saved = repository.save(entity);
        auditService.log("CommissionSetup", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public CommissionSetupResponse update(UUID productId, UUID id, CommissionSetupRequest request) {
        CommissionSetup entity = findOrThrow(productId, id);
        entity.setBrokerType(request.getBrokerType());
        entity.setRate(request.getRate());
        entity.setEffectiveFrom(request.getEffectiveFrom());
        entity.setEffectiveTo(request.getEffectiveTo());
        CommissionSetup saved = repository.save(entity);
        auditService.log("CommissionSetup", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID productId, UUID id) {
        CommissionSetup entity = findOrThrow(productId, id);
        entity.softDelete();
        repository.save(entity);
        auditService.log("CommissionSetup", id.toString(), AuditAction.DELETE, entity, null);
    }

    private Product resolveProduct(UUID productId) {
        return productRepository.findById(productId)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
    }

    private CommissionSetup findOrThrow(UUID productId, UUID id) {
        CommissionSetup entity = repository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("CommissionSetup", id));
        if (!entity.getProduct().getId().equals(productId)) {
            throw new ResourceNotFoundException("CommissionSetup", id);
        }
        return entity;
    }

    private CommissionSetupResponse toResponse(CommissionSetup e) {
        return CommissionSetupResponse.builder()
                .id(e.getId()).productId(e.getProduct().getId())
                .brokerType(e.getBrokerType()).rate(e.getRate())
                .effectiveFrom(e.getEffectiveFrom()).effectiveTo(e.getEffectiveTo())
                .createdAt(e.getCreatedAt()).updatedAt(e.getUpdatedAt())
                .build();
    }
}
