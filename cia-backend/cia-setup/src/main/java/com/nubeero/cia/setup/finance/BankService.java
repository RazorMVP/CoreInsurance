package com.nubeero.cia.setup.finance;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.finance.dto.BankRequest;
import com.nubeero.cia.setup.finance.dto.BankResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BankService {

    private final BankRepository repository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<BankResponse> list(Pageable pageable) {
        return repository.findAllByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public BankResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public BankResponse create(BankRequest request) {
        Bank bank = Bank.builder()
                .name(request.getName())
                .code(request.getCode().toUpperCase())
                .build();
        Bank saved = repository.save(bank);
        auditService.log("Bank", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public BankResponse update(UUID id, BankRequest request) {
        Bank bank = findOrThrow(id);
        bank.setName(request.getName());
        bank.setCode(request.getCode().toUpperCase());
        Bank saved = repository.save(bank);
        auditService.log("Bank", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        Bank bank = findOrThrow(id);
        bank.softDelete();
        repository.save(bank);
        auditService.log("Bank", id.toString(), AuditAction.DELETE, bank, null);
    }

    private Bank findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(b -> b.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Bank", id));
    }

    private BankResponse toResponse(Bank b) {
        return BankResponse.builder()
                .id(b.getId()).name(b.getName()).code(b.getCode())
                .createdAt(b.getCreatedAt()).updatedAt(b.getUpdatedAt())
                .build();
    }
}
