package com.nubeero.cia.setup.org;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.org.dto.AdjusterRequest;
import com.nubeero.cia.setup.org.dto.AdjusterResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdjusterService {

    private final AdjusterRepository repository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<AdjusterResponse> list(Pageable pageable) {
        return repository.findAllByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public AdjusterResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public AdjusterResponse create(AdjusterRequest request) {
        Adjuster entity = Adjuster.builder()
                .name(request.getName())
                .code(request.getCode())
                .type(request.getType())
                .licenseNumber(request.getLicenseNumber())
                .email(request.getEmail())
                .phone(request.getPhone())
                .address(request.getAddress())
                .build();
        Adjuster saved = repository.save(entity);
        auditService.log("Adjuster", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public AdjusterResponse update(UUID id, AdjusterRequest request) {
        Adjuster entity = findOrThrow(id);
        entity.setName(request.getName());
        entity.setCode(request.getCode());
        entity.setType(request.getType());
        entity.setLicenseNumber(request.getLicenseNumber());
        entity.setEmail(request.getEmail());
        entity.setPhone(request.getPhone());
        entity.setAddress(request.getAddress());
        Adjuster saved = repository.save(entity);
        auditService.log("Adjuster", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        Adjuster entity = findOrThrow(id);
        entity.softDelete();
        repository.save(entity);
        auditService.log("Adjuster", id.toString(), AuditAction.DELETE, entity, null);
    }

    private Adjuster findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Adjuster", id));
    }

    private AdjusterResponse toResponse(Adjuster e) {
        return AdjusterResponse.builder()
                .id(e.getId())
                .name(e.getName())
                .code(e.getCode())
                .type(e.getType())
                .licenseNumber(e.getLicenseNumber())
                .email(e.getEmail())
                .phone(e.getPhone())
                .address(e.getAddress())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
