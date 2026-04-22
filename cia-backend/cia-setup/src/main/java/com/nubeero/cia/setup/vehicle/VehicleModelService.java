package com.nubeero.cia.setup.vehicle;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.vehicle.dto.VehicleModelRequest;
import com.nubeero.cia.setup.vehicle.dto.VehicleModelResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VehicleModelService {

    private final VehicleModelRepository repository;
    private final VehicleMakeRepository makeRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<VehicleModelResponse> listByMake(UUID makeId, Pageable pageable) {
        return repository.findAllByMakeIdAndDeletedAtIsNull(makeId, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public VehicleModelResponse get(UUID makeId, UUID id) {
        VehicleModel entity = findOrThrow(id);
        if (!entity.getMake().getId().equals(makeId)) {
            throw new ResourceNotFoundException("VehicleModel", id);
        }
        return toResponse(entity);
    }

    @Transactional
    public VehicleModelResponse create(UUID makeId, VehicleModelRequest request) {
        VehicleMake make = makeRepository.findById(makeId)
                .filter(m -> m.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("VehicleMake", makeId));
        VehicleModel entity = VehicleModel.builder()
                .make(make)
                .name(request.getName())
                .build();
        VehicleModel saved = repository.save(entity);
        auditService.log("VehicleModel", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public VehicleModelResponse update(UUID makeId, UUID id, VehicleModelRequest request) {
        VehicleModel entity = findOrThrow(id);
        if (!entity.getMake().getId().equals(makeId)) {
            throw new ResourceNotFoundException("VehicleModel", id);
        }
        entity.setName(request.getName());
        VehicleModel saved = repository.save(entity);
        auditService.log("VehicleModel", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID makeId, UUID id) {
        VehicleModel entity = findOrThrow(id);
        if (!entity.getMake().getId().equals(makeId)) {
            throw new ResourceNotFoundException("VehicleModel", id);
        }
        entity.softDelete();
        repository.save(entity);
        auditService.log("VehicleModel", id.toString(), AuditAction.DELETE, entity, null);
    }

    private VehicleModel findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("VehicleModel", id));
    }

    private VehicleModelResponse toResponse(VehicleModel e) {
        return VehicleModelResponse.builder()
                .id(e.getId()).name(e.getName())
                .makeId(e.getMake().getId()).makeName(e.getMake().getName())
                .createdAt(e.getCreatedAt()).updatedAt(e.getUpdatedAt())
                .build();
    }
}
