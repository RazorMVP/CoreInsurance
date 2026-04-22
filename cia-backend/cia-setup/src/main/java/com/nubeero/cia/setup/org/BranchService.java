package com.nubeero.cia.setup.org;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.org.dto.BranchRequest;
import com.nubeero.cia.setup.org.dto.BranchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BranchService {

    private final BranchRepository repository;
    private final SbuRepository sbuRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<BranchResponse> list(Pageable pageable) {
        return repository.findAllByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public BranchResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public BranchResponse create(BranchRequest request) {
        Branch entity = Branch.builder()
                .name(request.getName())
                .code(request.getCode().toUpperCase())
                .address(request.getAddress())
                .sbu(resolveSbu(request.getSbuId()))
                .build();
        Branch saved = repository.save(entity);
        auditService.log("Branch", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public BranchResponse update(UUID id, BranchRequest request) {
        Branch entity = findOrThrow(id);
        entity.setName(request.getName());
        entity.setCode(request.getCode().toUpperCase());
        entity.setAddress(request.getAddress());
        entity.setSbu(resolveSbu(request.getSbuId()));
        Branch saved = repository.save(entity);
        auditService.log("Branch", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        Branch entity = findOrThrow(id);
        entity.softDelete();
        repository.save(entity);
        auditService.log("Branch", id.toString(), AuditAction.DELETE, entity, null);
    }

    private Sbu resolveSbu(UUID sbuId) {
        if (sbuId == null) return null;
        return sbuRepository.findById(sbuId)
                .filter(s -> s.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Sbu", sbuId));
    }

    Branch findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Branch", id));
    }

    private BranchResponse toResponse(Branch e) {
        return BranchResponse.builder()
                .id(e.getId()).name(e.getName()).code(e.getCode())
                .sbuId(e.getSbu() != null ? e.getSbu().getId() : null)
                .sbuName(e.getSbu() != null ? e.getSbu().getName() : null)
                .address(e.getAddress())
                .createdAt(e.getCreatedAt()).updatedAt(e.getUpdatedAt())
                .build();
    }
}
