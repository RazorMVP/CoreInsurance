package com.nubeero.cia.setup.org;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.org.dto.SbuRequest;
import com.nubeero.cia.setup.org.dto.SbuResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SbuService {

    private final SbuRepository repository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<SbuResponse> list(Pageable pageable) {
        return repository.findAllByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public SbuResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public SbuResponse create(SbuRequest request) {
        Sbu entity = Sbu.builder()
                .name(request.getName())
                .code(request.getCode().toUpperCase())
                .build();
        Sbu saved = repository.save(entity);
        auditService.log("Sbu", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public SbuResponse update(UUID id, SbuRequest request) {
        Sbu entity = findOrThrow(id);
        entity.setName(request.getName());
        entity.setCode(request.getCode().toUpperCase());
        Sbu saved = repository.save(entity);
        auditService.log("Sbu", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        Sbu entity = findOrThrow(id);
        entity.softDelete();
        repository.save(entity);
        auditService.log("Sbu", id.toString(), AuditAction.DELETE, entity, null);
    }

    Sbu findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Sbu", id));
    }

    private SbuResponse toResponse(Sbu e) {
        return SbuResponse.builder()
                .id(e.getId()).name(e.getName()).code(e.getCode())
                .createdAt(e.getCreatedAt()).updatedAt(e.getUpdatedAt())
                .build();
    }
}
