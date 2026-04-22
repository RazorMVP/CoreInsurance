package com.nubeero.cia.setup.loss;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.loss.dto.ClaimReserveCategoryRequest;
import com.nubeero.cia.setup.loss.dto.ClaimReserveCategoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ClaimReserveCategoryService {

    private final ClaimReserveCategoryRepository repository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<ClaimReserveCategoryResponse> list(Pageable pageable) {
        return repository.findAllByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ClaimReserveCategoryResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public ClaimReserveCategoryResponse create(ClaimReserveCategoryRequest request) {
        ClaimReserveCategory entity = ClaimReserveCategory.builder()
                .name(request.getName())
                .code(request.getCode().toUpperCase())
                .build();
        ClaimReserveCategory saved = repository.save(entity);
        auditService.log("ClaimReserveCategory", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public ClaimReserveCategoryResponse update(UUID id, ClaimReserveCategoryRequest request) {
        ClaimReserveCategory entity = findOrThrow(id);
        entity.setName(request.getName());
        entity.setCode(request.getCode().toUpperCase());
        ClaimReserveCategory saved = repository.save(entity);
        auditService.log("ClaimReserveCategory", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        ClaimReserveCategory entity = findOrThrow(id);
        entity.softDelete();
        repository.save(entity);
        auditService.log("ClaimReserveCategory", id.toString(), AuditAction.DELETE, entity, null);
    }

    private ClaimReserveCategory findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("ClaimReserveCategory", id));
    }

    private ClaimReserveCategoryResponse toResponse(ClaimReserveCategory e) {
        return ClaimReserveCategoryResponse.builder()
                .id(e.getId()).name(e.getName()).code(e.getCode())
                .createdAt(e.getCreatedAt()).updatedAt(e.getUpdatedAt())
                .build();
    }
}
