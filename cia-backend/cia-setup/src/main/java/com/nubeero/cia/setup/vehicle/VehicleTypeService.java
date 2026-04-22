package com.nubeero.cia.setup.vehicle;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.vehicle.dto.VehicleTypeRequest;
import com.nubeero.cia.setup.vehicle.dto.VehicleTypeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VehicleTypeService {

    private final VehicleTypeRepository repository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<VehicleTypeResponse> list(Pageable pageable) {
        return repository.findAllByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public VehicleTypeResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public VehicleTypeResponse create(VehicleTypeRequest request) {
        VehicleType entity = VehicleType.builder().name(request.getName()).build();
        VehicleType saved = repository.save(entity);
        auditService.log("VehicleType", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public VehicleTypeResponse update(UUID id, VehicleTypeRequest request) {
        VehicleType entity = findOrThrow(id);
        entity.setName(request.getName());
        VehicleType saved = repository.save(entity);
        auditService.log("VehicleType", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        VehicleType entity = findOrThrow(id);
        entity.softDelete();
        repository.save(entity);
        auditService.log("VehicleType", id.toString(), AuditAction.DELETE, entity, null);
    }

    private VehicleType findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("VehicleType", id));
    }

    private VehicleTypeResponse toResponse(VehicleType e) {
        return VehicleTypeResponse.builder()
                .id(e.getId()).name(e.getName())
                .createdAt(e.getCreatedAt()).updatedAt(e.getUpdatedAt())
                .build();
    }
}
