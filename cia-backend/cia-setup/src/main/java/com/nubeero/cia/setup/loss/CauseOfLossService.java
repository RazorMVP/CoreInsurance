package com.nubeero.cia.setup.loss;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.loss.dto.CauseOfLossRequest;
import com.nubeero.cia.setup.loss.dto.CauseOfLossResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CauseOfLossService {

    private final CauseOfLossRepository repository;
    private final NatureOfLossRepository natureOfLossRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<CauseOfLossResponse> list(Pageable pageable) {
        return repository.findAllByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public List<CauseOfLossResponse> listByNatureOfLoss(UUID natureOfLossId) {
        return repository.findAllByNatureOfLossIdAndDeletedAtIsNull(natureOfLossId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public CauseOfLossResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public CauseOfLossResponse create(CauseOfLossRequest request) {
        NatureOfLoss natureOfLoss = resolveNatureOfLoss(request.getNatureOfLossId());
        CauseOfLoss entity = CauseOfLoss.builder()
                .name(request.getName())
                .code(request.getCode().toUpperCase())
                .natureOfLoss(natureOfLoss)
                .build();
        CauseOfLoss saved = repository.save(entity);
        auditService.log("CauseOfLoss", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public CauseOfLossResponse update(UUID id, CauseOfLossRequest request) {
        CauseOfLoss entity = findOrThrow(id);
        entity.setName(request.getName());
        entity.setCode(request.getCode().toUpperCase());
        entity.setNatureOfLoss(resolveNatureOfLoss(request.getNatureOfLossId()));
        CauseOfLoss saved = repository.save(entity);
        auditService.log("CauseOfLoss", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        CauseOfLoss entity = findOrThrow(id);
        entity.softDelete();
        repository.save(entity);
        auditService.log("CauseOfLoss", id.toString(), AuditAction.DELETE, entity, null);
    }

    private NatureOfLoss resolveNatureOfLoss(UUID id) {
        if (id == null) return null;
        return natureOfLossRepository.findById(id)
                .filter(n -> n.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("NatureOfLoss", id));
    }

    private CauseOfLoss findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("CauseOfLoss", id));
    }

    private CauseOfLossResponse toResponse(CauseOfLoss e) {
        return CauseOfLossResponse.builder()
                .id(e.getId()).name(e.getName()).code(e.getCode())
                .natureOfLossId(e.getNatureOfLoss() != null ? e.getNatureOfLoss().getId() : null)
                .natureOfLossName(e.getNatureOfLoss() != null ? e.getNatureOfLoss().getName() : null)
                .createdAt(e.getCreatedAt()).updatedAt(e.getUpdatedAt())
                .build();
    }
}
