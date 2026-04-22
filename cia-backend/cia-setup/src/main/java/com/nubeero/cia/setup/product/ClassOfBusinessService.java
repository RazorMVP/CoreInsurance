package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.product.dto.ClassOfBusinessRequest;
import com.nubeero.cia.setup.product.dto.ClassOfBusinessResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ClassOfBusinessService {

    private final ClassOfBusinessRepository repository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<ClassOfBusinessResponse> list(Pageable pageable) {
        return repository.findAllByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ClassOfBusinessResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public ClassOfBusinessResponse create(ClassOfBusinessRequest request) {
        if (repository.existsByCodeAndDeletedAtIsNull(request.getCode().toUpperCase())) {
            throw new BusinessRuleException("COB_CODE_EXISTS",
                    "Class of business code already exists: " + request.getCode());
        }
        ClassOfBusiness entity = repository.save(ClassOfBusiness.builder()
                .name(request.getName())
                .code(request.getCode().toUpperCase())
                .description(request.getDescription())
                .build());
        auditService.log("ClassOfBusiness", entity.getId().toString(), AuditAction.CREATE, null, entity);
        return toResponse(entity);
    }

    @Transactional
    public ClassOfBusinessResponse update(UUID id, ClassOfBusinessRequest request) {
        ClassOfBusiness entity = findOrThrow(id);
        String newCode = request.getCode().toUpperCase();
        if (!entity.getCode().equals(newCode) && repository.existsByCodeAndDeletedAtIsNull(newCode)) {
            throw new BusinessRuleException("COB_CODE_EXISTS",
                    "Class of business code already exists: " + newCode);
        }
        ClassOfBusiness before = ClassOfBusiness.builder()
                .name(entity.getName()).code(entity.getCode()).description(entity.getDescription()).build();
        entity.setName(request.getName());
        entity.setCode(newCode);
        entity.setDescription(request.getDescription());
        ClassOfBusiness saved = repository.save(entity);
        auditService.log("ClassOfBusiness", id.toString(), AuditAction.UPDATE, before, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        ClassOfBusiness entity = findOrThrow(id);
        entity.softDelete();
        repository.save(entity);
        auditService.log("ClassOfBusiness", id.toString(), AuditAction.DELETE, entity, null);
    }

    private ClassOfBusiness findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("ClassOfBusiness", id));
    }

    private ClassOfBusinessResponse toResponse(ClassOfBusiness e) {
        return ClassOfBusinessResponse.builder()
                .id(e.getId())
                .name(e.getName())
                .code(e.getCode())
                .description(e.getDescription())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
