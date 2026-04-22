package com.nubeero.cia.setup.org;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.org.dto.SurveyorRequest;
import com.nubeero.cia.setup.org.dto.SurveyorResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SurveyorService {

    private final SurveyorRepository repository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<SurveyorResponse> list(Pageable pageable) {
        return repository.findAllByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public SurveyorResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public SurveyorResponse create(SurveyorRequest request) {
        Surveyor entity = Surveyor.builder()
                .name(request.getName())
                .type(request.getType())
                .licenseNumber(request.getLicenseNumber())
                .email(request.getEmail())
                .phone(request.getPhone())
                .build();
        Surveyor saved = repository.save(entity);
        auditService.log("Surveyor", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public SurveyorResponse update(UUID id, SurveyorRequest request) {
        Surveyor entity = findOrThrow(id);
        entity.setName(request.getName());
        entity.setType(request.getType());
        entity.setLicenseNumber(request.getLicenseNumber());
        entity.setEmail(request.getEmail());
        entity.setPhone(request.getPhone());
        Surveyor saved = repository.save(entity);
        auditService.log("Surveyor", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        Surveyor entity = findOrThrow(id);
        entity.softDelete();
        repository.save(entity);
        auditService.log("Surveyor", id.toString(), AuditAction.DELETE, entity, null);
    }

    private Surveyor findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Surveyor", id));
    }

    private SurveyorResponse toResponse(Surveyor e) {
        return SurveyorResponse.builder()
                .id(e.getId()).name(e.getName()).type(e.getType())
                .licenseNumber(e.getLicenseNumber()).email(e.getEmail()).phone(e.getPhone())
                .createdAt(e.getCreatedAt()).updatedAt(e.getUpdatedAt())
                .build();
    }
}
