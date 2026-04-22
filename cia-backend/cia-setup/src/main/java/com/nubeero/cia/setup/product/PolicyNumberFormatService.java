package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.product.dto.PolicyNumberFormatRequest;
import com.nubeero.cia.setup.product.dto.PolicyNumberFormatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PolicyNumberFormatService {

    private final PolicyNumberFormatRepository repository;
    private final ProductRepository productRepository;
    private final AuditService auditService;

    /**
     * Atomically generates the next sequential policy number.
     * Uses PESSIMISTIC_WRITE to prevent duplicate numbers under concurrent approvals.
     */
    @Transactional
    public String generateNext(UUID productId) {
        PolicyNumberFormat fmt = repository.findByProductIdForUpdate(productId)
                .orElseThrow(() -> new BusinessRuleException("POLICY_NUMBER_FORMAT_NOT_CONFIGURED",
                        "No policy number format configured for product: " + productId));

        long next = fmt.getLastSequence() + 1;
        fmt.setLastSequence(next);
        repository.save(fmt);

        StringBuilder sb = new StringBuilder(fmt.getPrefix());
        if (fmt.isIncludeYear()) {
            sb.append("/").append(Year.now().getValue());
        }
        if (fmt.isIncludeClassCode()) {
            sb.append("/").append(
                    fmt.getProduct().getClassOfBusiness().getCode());
        }
        String seq = String.format("%0" + fmt.getSequenceLength() + "d", next);
        sb.append("/").append(seq);

        return sb.toString();
    }

    @Transactional
    public PolicyNumberFormat save(PolicyNumberFormat format) {
        return repository.save(format);
    }

    @Transactional(readOnly = true)
    public PolicyNumberFormat getByProductId(UUID productId) {
        return repository.findByProductIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new ResourceNotFoundException("PolicyNumberFormat", productId));
    }

    @Transactional(readOnly = true)
    public PolicyNumberFormatResponse getByProduct(UUID productId) {
        return repository.findByProductIdAndDeletedAtIsNull(productId)
                .map(this::toResponse)
                .orElse(null);
    }

    @Transactional
    public PolicyNumberFormatResponse upsert(UUID productId, PolicyNumberFormatRequest request) {
        PolicyNumberFormat entity = repository.findByProductIdAndDeletedAtIsNull(productId)
                .orElse(null);
        boolean isNew = entity == null;
        if (isNew) {
            Product product = productRepository.findById(productId)
                    .filter(p -> p.getDeletedAt() == null)
                    .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
            entity = PolicyNumberFormat.builder().product(product).lastSequence(0).build();
        }
        entity.setPrefix(request.getPrefix());
        entity.setIncludeYear(request.isIncludeYear());
        entity.setIncludeClassCode(request.isIncludeClassCode());
        entity.setSequenceLength(request.getSequenceLength());
        PolicyNumberFormat saved = repository.save(entity);
        auditService.log("PolicyNumberFormat", saved.getId().toString(),
                isNew ? AuditAction.CREATE : AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    private PolicyNumberFormatResponse toResponse(PolicyNumberFormat e) {
        return PolicyNumberFormatResponse.builder()
                .id(e.getId()).productId(e.getProduct().getId())
                .prefix(e.getPrefix()).includeYear(e.isIncludeYear())
                .includeClassCode(e.isIncludeClassCode()).sequenceLength(e.getSequenceLength())
                .lastSequence(e.getLastSequence())
                .createdAt(e.getCreatedAt()).updatedAt(e.getUpdatedAt())
                .build();
    }
}
