package com.nubeero.cia.setup.org;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.org.dto.InsuranceCompanyRequest;
import com.nubeero.cia.setup.org.dto.InsuranceCompanyResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InsuranceCompanyService {

    private final InsuranceCompanyRepository repository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<InsuranceCompanyResponse> list(Pageable pageable) {
        return repository.findAllByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public InsuranceCompanyResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public InsuranceCompanyResponse create(InsuranceCompanyRequest request) {
        InsuranceCompany entity = InsuranceCompany.builder()
                .name(request.getName()).rcNumber(request.getRcNumber())
                .naicomLicense(request.getNaicomLicense()).address(request.getAddress())
                .email(request.getEmail()).phone(request.getPhone())
                .build();
        InsuranceCompany saved = repository.save(entity);
        auditService.log("InsuranceCompany", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public InsuranceCompanyResponse update(UUID id, InsuranceCompanyRequest request) {
        InsuranceCompany entity = findOrThrow(id);
        entity.setName(request.getName());
        entity.setRcNumber(request.getRcNumber());
        entity.setNaicomLicense(request.getNaicomLicense());
        entity.setAddress(request.getAddress());
        entity.setEmail(request.getEmail());
        entity.setPhone(request.getPhone());
        InsuranceCompany saved = repository.save(entity);
        auditService.log("InsuranceCompany", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        InsuranceCompany entity = findOrThrow(id);
        entity.softDelete();
        repository.save(entity);
        auditService.log("InsuranceCompany", id.toString(), AuditAction.DELETE, entity, null);
    }

    InsuranceCompany findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("InsuranceCompany", id));
    }

    private InsuranceCompanyResponse toResponse(InsuranceCompany e) {
        return InsuranceCompanyResponse.builder()
                .id(e.getId()).name(e.getName()).rcNumber(e.getRcNumber())
                .naicomLicense(e.getNaicomLicense()).address(e.getAddress())
                .email(e.getEmail()).phone(e.getPhone())
                .createdAt(e.getCreatedAt()).updatedAt(e.getUpdatedAt())
                .build();
    }
}
