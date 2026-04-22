package com.nubeero.cia.setup.company;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.company.dto.CompanySettingsRequest;
import com.nubeero.cia.setup.company.dto.CompanySettingsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CompanySettingsService {

    private final CompanySettingsRepository repository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public CompanySettingsResponse get() {
        return repository.findTopByDeletedAtIsNullOrderByCreatedAtDesc()
                .map(this::toResponse)
                .orElse(null);
    }

    @Transactional
    public CompanySettingsResponse upsert(CompanySettingsRequest request) {
        CompanySettings settings = repository.findTopByDeletedAtIsNullOrderByCreatedAtDesc()
                .orElse(CompanySettings.builder().build());

        boolean isNew = settings.getId() == null;
        settings.setName(request.getName());
        settings.setRcNumber(request.getRcNumber());
        settings.setNaicomLicenseNumber(request.getNaicomLicenseNumber());
        settings.setAddress(request.getAddress());
        settings.setCity(request.getCity());
        settings.setState(request.getState());
        settings.setEmail(request.getEmail());
        settings.setPhone(request.getPhone());
        settings.setLogoPath(request.getLogoPath());
        settings.setWebsite(request.getWebsite());

        CompanySettings saved = repository.save(settings);
        AuditAction action = isNew ? AuditAction.CREATE : AuditAction.UPDATE;
        auditService.log("CompanySettings", saved.getId().toString(), action, null, saved);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public CompanySettingsResponse getById(UUID id) {
        return repository.findById(id)
                .filter(s -> s.getDeletedAt() == null)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("CompanySettings", id));
    }

    private CompanySettingsResponse toResponse(CompanySettings s) {
        return CompanySettingsResponse.builder()
                .id(s.getId()).name(s.getName()).rcNumber(s.getRcNumber())
                .naicomLicenseNumber(s.getNaicomLicenseNumber()).address(s.getAddress())
                .city(s.getCity()).state(s.getState()).email(s.getEmail())
                .phone(s.getPhone()).logoPath(s.getLogoPath()).website(s.getWebsite())
                .createdAt(s.getCreatedAt()).updatedAt(s.getUpdatedAt())
                .build();
    }
}
