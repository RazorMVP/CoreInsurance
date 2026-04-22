package com.nubeero.cia.setup.org;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.org.dto.BrokerRequest;
import com.nubeero.cia.setup.org.dto.BrokerResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BrokerService {

    private final BrokerRepository repository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<BrokerResponse> list(Pageable pageable) {
        return repository.findAllByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public BrokerResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public BrokerResponse create(BrokerRequest request) {
        String code = request.getCode().toUpperCase();
        if (repository.findByCodeAndDeletedAtIsNull(code).isPresent()) {
            throw new BusinessRuleException("BROKER_CODE_EXISTS", "Broker code already exists: " + code);
        }
        Broker saved = repository.save(Broker.builder()
                .name(request.getName()).code(code)
                .rcNumber(request.getRcNumber()).address(request.getAddress())
                .email(request.getEmail()).phone(request.getPhone())
                .build());
        auditService.log("Broker", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public BrokerResponse update(UUID id, BrokerRequest request) {
        Broker broker = findOrThrow(id);
        String newCode = request.getCode().toUpperCase();
        if (!broker.getCode().equals(newCode) && repository.findByCodeAndDeletedAtIsNull(newCode).isPresent()) {
            throw new BusinessRuleException("BROKER_CODE_EXISTS", "Broker code already exists: " + newCode);
        }
        broker.setName(request.getName()); broker.setCode(newCode);
        broker.setRcNumber(request.getRcNumber()); broker.setAddress(request.getAddress());
        broker.setEmail(request.getEmail()); broker.setPhone(request.getPhone());
        Broker saved = repository.save(broker);
        auditService.log("Broker", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        Broker broker = findOrThrow(id);
        broker.softDelete();
        repository.save(broker);
        auditService.log("Broker", id.toString(), AuditAction.DELETE, broker, null);
    }

    private Broker findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(b -> b.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Broker", id));
    }

    private BrokerResponse toResponse(Broker b) {
        return BrokerResponse.builder()
                .id(b.getId()).name(b.getName()).code(b.getCode())
                .rcNumber(b.getRcNumber()).address(b.getAddress())
                .email(b.getEmail()).phone(b.getPhone())
                .createdAt(b.getCreatedAt()).updatedAt(b.getUpdatedAt())
                .build();
    }
}
