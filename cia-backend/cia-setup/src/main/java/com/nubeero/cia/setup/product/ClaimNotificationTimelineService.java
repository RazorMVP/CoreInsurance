package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.product.dto.ClaimNotificationTimelineRequest;
import com.nubeero.cia.setup.product.dto.ClaimNotificationTimelineResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ClaimNotificationTimelineService {

    private final ClaimNotificationTimelineRepository repository;
    private final ProductRepository productRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public ClaimNotificationTimelineResponse get(UUID productId) {
        return repository.findByProductIdAndDeletedAtIsNull(productId)
                .map(this::toResponse)
                .orElse(null);
    }

    @Transactional
    public ClaimNotificationTimelineResponse upsert(UUID productId, ClaimNotificationTimelineRequest request) {
        ClaimNotificationTimeline entity = repository.findByProductIdAndDeletedAtIsNull(productId)
                .orElse(ClaimNotificationTimeline.builder()
                        .product(resolveProduct(productId))
                        .build());
        boolean isNew = entity.getId() == null;
        entity.setNotificationDays(request.getNotificationDays());
        ClaimNotificationTimeline saved = repository.save(entity);
        AuditAction action = isNew ? AuditAction.CREATE : AuditAction.UPDATE;
        auditService.log("ClaimNotificationTimeline", saved.getId().toString(), action, null, saved);
        return toResponse(saved);
    }

    private Product resolveProduct(UUID productId) {
        return productRepository.findById(productId)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
    }

    private ClaimNotificationTimelineResponse toResponse(ClaimNotificationTimeline e) {
        return ClaimNotificationTimelineResponse.builder()
                .id(e.getId()).productId(e.getProduct().getId())
                .notificationDays(e.getNotificationDays())
                .createdAt(e.getCreatedAt()).updatedAt(e.getUpdatedAt())
                .build();
    }
}
