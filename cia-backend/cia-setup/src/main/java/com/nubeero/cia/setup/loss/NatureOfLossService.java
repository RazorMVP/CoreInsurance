package com.nubeero.cia.setup.loss;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.loss.dto.NatureOfLossRequest;
import com.nubeero.cia.setup.loss.dto.NatureOfLossResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NatureOfLossService {

    private final NatureOfLossRepository repository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<NatureOfLossResponse> list(Pageable pageable) {
        return repository.findAllByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public NatureOfLossResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public NatureOfLossResponse create(NatureOfLossRequest request) {
        NatureOfLoss entity = NatureOfLoss.builder()
                .name(request.getName())
                .code(request.getCode().toUpperCase())
                .build();
        NatureOfLoss saved = repository.save(entity);
        auditService.log("NatureOfLoss", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public NatureOfLossResponse update(UUID id, NatureOfLossRequest request) {
        NatureOfLoss entity = findOrThrow(id);
        entity.setName(request.getName());
        entity.setCode(request.getCode().toUpperCase());
        NatureOfLoss saved = repository.save(entity);
        auditService.log("NatureOfLoss", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        NatureOfLoss entity = findOrThrow(id);
        entity.softDelete();
        repository.save(entity);
        auditService.log("NatureOfLoss", id.toString(), AuditAction.DELETE, entity, null);
    }

    NatureOfLoss findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("NatureOfLoss", id));
    }

    private NatureOfLossResponse toResponse(NatureOfLoss e) {
        return NatureOfLossResponse.builder()
                .id(e.getId()).name(e.getName()).code(e.getCode())
                .createdAt(e.getCreatedAt()).updatedAt(e.getUpdatedAt())
                .build();
    }
}
