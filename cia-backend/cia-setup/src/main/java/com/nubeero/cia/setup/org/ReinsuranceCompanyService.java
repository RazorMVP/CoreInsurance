package com.nubeero.cia.setup.org;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.org.dto.ReinsuranceCompanyRequest;
import com.nubeero.cia.setup.org.dto.ReinsuranceCompanyResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReinsuranceCompanyService {

    private final ReinsuranceCompanyRepository repository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<ReinsuranceCompanyResponse> list(Pageable pageable) {
        return repository.findAllByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ReinsuranceCompanyResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public ReinsuranceCompanyResponse create(ReinsuranceCompanyRequest request) {
        ReinsuranceCompany entity = ReinsuranceCompany.builder()
                .name(request.getName()).rcNumber(request.getRcNumber())
                .address(request.getAddress()).email(request.getEmail())
                .phone(request.getPhone()).country(request.getCountry())
                .build();
        ReinsuranceCompany saved = repository.save(entity);
        auditService.log("ReinsuranceCompany", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public ReinsuranceCompanyResponse update(UUID id, ReinsuranceCompanyRequest request) {
        ReinsuranceCompany entity = findOrThrow(id);
        entity.setName(request.getName());
        entity.setRcNumber(request.getRcNumber());
        entity.setAddress(request.getAddress());
        entity.setEmail(request.getEmail());
        entity.setPhone(request.getPhone());
        entity.setCountry(request.getCountry());
        ReinsuranceCompany saved = repository.save(entity);
        auditService.log("ReinsuranceCompany", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        ReinsuranceCompany entity = findOrThrow(id);
        entity.softDelete();
        repository.save(entity);
        auditService.log("ReinsuranceCompany", id.toString(), AuditAction.DELETE, entity, null);
    }

    ReinsuranceCompany findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("ReinsuranceCompany", id));
    }

    private ReinsuranceCompanyResponse toResponse(ReinsuranceCompany e) {
        return ReinsuranceCompanyResponse.builder()
                .id(e.getId()).name(e.getName()).rcNumber(e.getRcNumber())
                .address(e.getAddress()).email(e.getEmail()).phone(e.getPhone())
                .country(e.getCountry())
                .createdAt(e.getCreatedAt()).updatedAt(e.getUpdatedAt())
                .build();
    }
}
