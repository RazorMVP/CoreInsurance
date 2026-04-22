package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.product.dto.PolicySpecificationRequest;
import com.nubeero.cia.setup.product.dto.PolicySpecificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PolicySpecificationService {

    private final PolicySpecificationRepository repository;
    private final ProductRepository productRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PolicySpecificationResponse get(UUID productId) {
        return repository.findByProductIdAndDeletedAtIsNull(productId)
                .map(this::toResponse)
                .orElse(null);
    }

    @Transactional
    public PolicySpecificationResponse upsert(UUID productId, PolicySpecificationRequest request) {
        PolicySpecification entity = repository.findByProductIdAndDeletedAtIsNull(productId)
                .orElse(PolicySpecification.builder()
                        .product(resolveProduct(productId))
                        .build());
        boolean isNew = entity.getId() == null;
        entity.setContent(request.getContent());
        PolicySpecification saved = repository.save(entity);
        AuditAction action = isNew ? AuditAction.CREATE : AuditAction.UPDATE;
        auditService.log("PolicySpecification", saved.getId().toString(), action, null, saved);
        return toResponse(saved);
    }

    private Product resolveProduct(UUID productId) {
        return productRepository.findById(productId)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
    }

    private PolicySpecificationResponse toResponse(PolicySpecification e) {
        return PolicySpecificationResponse.builder()
                .id(e.getId()).productId(e.getProduct().getId()).content(e.getContent())
                .createdAt(e.getCreatedAt()).updatedAt(e.getUpdatedAt())
                .build();
    }
}
