package com.nubeero.cia.setup.finance;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.finance.dto.CurrencyRequest;
import com.nubeero.cia.setup.finance.dto.CurrencyResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CurrencyService {

    private final CurrencyRepository repository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<CurrencyResponse> list(Pageable pageable) {
        return repository.findAllByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public CurrencyResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public CurrencyResponse create(CurrencyRequest request) {
        Currency currency = Currency.builder()
                .code(request.getCode().toUpperCase())
                .name(request.getName())
                .symbol(request.getSymbol())
                .isDefault(request.isDefault())
                .build();
        Currency saved = repository.save(currency);
        auditService.log("Currency", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public CurrencyResponse update(UUID id, CurrencyRequest request) {
        Currency currency = findOrThrow(id);
        currency.setCode(request.getCode().toUpperCase());
        currency.setName(request.getName());
        currency.setSymbol(request.getSymbol());
        currency.setDefault(request.isDefault());
        Currency saved = repository.save(currency);
        auditService.log("Currency", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        Currency currency = findOrThrow(id);
        currency.softDelete();
        repository.save(currency);
        auditService.log("Currency", id.toString(), AuditAction.DELETE, currency, null);
    }

    private Currency findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Currency", id));
    }

    private CurrencyResponse toResponse(Currency c) {
        return CurrencyResponse.builder()
                .id(c.getId()).code(c.getCode()).name(c.getName())
                .symbol(c.getSymbol()).isDefault(c.isDefault())
                .createdAt(c.getCreatedAt()).updatedAt(c.getUpdatedAt())
                .build();
    }
}
