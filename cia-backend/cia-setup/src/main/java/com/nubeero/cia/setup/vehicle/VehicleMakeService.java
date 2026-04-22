package com.nubeero.cia.setup.vehicle;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.vehicle.dto.VehicleMakeRequest;
import com.nubeero.cia.setup.vehicle.dto.VehicleMakeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VehicleMakeService {

    private final VehicleMakeRepository repository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<VehicleMakeResponse> list(Pageable pageable) {
        return repository.findAllByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public VehicleMakeResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public VehicleMakeResponse create(VehicleMakeRequest request) {
        VehicleMake entity = VehicleMake.builder().name(request.getName()).build();
        VehicleMake saved = repository.save(entity);
        auditService.log("VehicleMake", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public VehicleMakeResponse update(UUID id, VehicleMakeRequest request) {
        VehicleMake entity = findOrThrow(id);
        entity.setName(request.getName());
        VehicleMake saved = repository.save(entity);
        auditService.log("VehicleMake", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        VehicleMake entity = findOrThrow(id);
        entity.softDelete();
        repository.save(entity);
        auditService.log("VehicleMake", id.toString(), AuditAction.DELETE, entity, null);
    }

    VehicleMake findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("VehicleMake", id));
    }

    private VehicleMakeResponse toResponse(VehicleMake e) {
        return VehicleMakeResponse.builder()
                .id(e.getId()).name(e.getName())
                .createdAt(e.getCreatedAt()).updatedAt(e.getUpdatedAt())
                .build();
    }
}
