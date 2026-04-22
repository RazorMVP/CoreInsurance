package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.product.dto.ClaimDocumentRequirementRequest;
import com.nubeero.cia.setup.product.dto.ClaimDocumentRequirementResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ClaimDocumentRequirementService {

    private final ClaimDocumentRequirementRepository repository;
    private final ProductRepository productRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<ClaimDocumentRequirementResponse> listByProduct(UUID productId) {
        return repository.findAllByProductIdAndDeletedAtIsNull(productId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ClaimDocumentRequirementResponse get(UUID productId, UUID id) {
        return toResponse(findOrThrow(productId, id));
    }

    @Transactional
    public ClaimDocumentRequirementResponse create(UUID productId, ClaimDocumentRequirementRequest request) {
        Product product = resolveProduct(productId);
        ClaimDocumentRequirement entity = ClaimDocumentRequirement.builder()
                .product(product)
                .documentName(request.getDocumentName())
                .isMandatory(request.isMandatory())
                .build();
        ClaimDocumentRequirement saved = repository.save(entity);
        auditService.log("ClaimDocumentRequirement", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public ClaimDocumentRequirementResponse update(UUID productId, UUID id, ClaimDocumentRequirementRequest request) {
        ClaimDocumentRequirement entity = findOrThrow(productId, id);
        entity.setDocumentName(request.getDocumentName());
        entity.setMandatory(request.isMandatory());
        ClaimDocumentRequirement saved = repository.save(entity);
        auditService.log("ClaimDocumentRequirement", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID productId, UUID id) {
        ClaimDocumentRequirement entity = findOrThrow(productId, id);
        entity.softDelete();
        repository.save(entity);
        auditService.log("ClaimDocumentRequirement", id.toString(), AuditAction.DELETE, entity, null);
    }

    private Product resolveProduct(UUID productId) {
        return productRepository.findById(productId)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
    }

    private ClaimDocumentRequirement findOrThrow(UUID productId, UUID id) {
        ClaimDocumentRequirement entity = repository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("ClaimDocumentRequirement", id));
        if (!entity.getProduct().getId().equals(productId)) {
            throw new ResourceNotFoundException("ClaimDocumentRequirement", id);
        }
        return entity;
    }

    private ClaimDocumentRequirementResponse toResponse(ClaimDocumentRequirement e) {
        return ClaimDocumentRequirementResponse.builder()
                .id(e.getId()).productId(e.getProduct().getId())
                .documentName(e.getDocumentName()).mandatory(e.isMandatory())
                .createdAt(e.getCreatedAt()).updatedAt(e.getUpdatedAt())
                .build();
    }
}
