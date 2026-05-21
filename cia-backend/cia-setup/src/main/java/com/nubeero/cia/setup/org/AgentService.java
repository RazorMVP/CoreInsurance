package com.nubeero.cia.setup.org;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.org.dto.AgentRequest;
import com.nubeero.cia.setup.org.dto.AgentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentRepository repository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<AgentResponse> list(Pageable pageable) {
        return repository.findAllByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public AgentResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public AgentResponse create(AgentRequest request) {
        Agent entity = Agent.builder()
                .name(request.getName())
                .code(request.getCode())
                .type(request.getType())
                .licenseNumber(request.getLicenseNumber())
                .email(request.getEmail())
                .phone(request.getPhone())
                .address(request.getAddress())
                .build();
        Agent saved = repository.save(entity);
        auditService.log("Agent", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public AgentResponse update(UUID id, AgentRequest request) {
        Agent entity = findOrThrow(id);
        entity.setName(request.getName());
        entity.setCode(request.getCode());
        entity.setType(request.getType());
        entity.setLicenseNumber(request.getLicenseNumber());
        entity.setEmail(request.getEmail());
        entity.setPhone(request.getPhone());
        entity.setAddress(request.getAddress());
        Agent saved = repository.save(entity);
        auditService.log("Agent", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id, String reason) {
        Agent entity = findOrThrow(id);
        entity.softDelete();
        repository.save(entity);
        auditService.logWithReason("Agent", id.toString(), AuditAction.DELETE, entity, null, reason);
    }

    private Agent findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Agent", id));
    }

    private AgentResponse toResponse(Agent e) {
        return AgentResponse.builder()
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
