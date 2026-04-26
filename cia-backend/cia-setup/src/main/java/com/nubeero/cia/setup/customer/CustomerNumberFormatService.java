package com.nubeero.cia.setup.customer;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.setup.customer.dto.CustomerNumberFormatRequest;
import com.nubeero.cia.setup.customer.dto.CustomerNumberFormatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;

@Service
@RequiredArgsConstructor
public class CustomerNumberFormatService {

    private final CustomerNumberFormatRepository repository;
    private final AuditService auditService;

    /**
     * Atomically generates the next customer number for the given type.
     * Uses PESSIMISTIC_WRITE to prevent duplicates under concurrent onboardings.
     *
     * @param customerType "INDIVIDUAL" or "CORPORATE"
     */
    @Transactional
    public String generateNext(String customerType) {
        CustomerNumberFormat fmt = repository.findForUpdate()
                .orElseThrow(() -> new BusinessRuleException(
                        "CUSTOMER_NUMBER_FORMAT_NOT_CONFIGURED",
                        "No customer number format has been configured. Please configure it in Setup → Customer Number Format."));

        long next;
        if (fmt.isIncludeType()) {
            if ("INDIVIDUAL".equalsIgnoreCase(customerType)) {
                next = fmt.getLastSequenceIndividual() + 1;
                fmt.setLastSequenceIndividual(next);
            } else {
                next = fmt.getLastSequenceCorporate() + 1;
                fmt.setLastSequenceCorporate(next);
            }
        } else {
            next = fmt.getLastSequence() + 1;
            fmt.setLastSequence(next);
        }
        repository.save(fmt);

        StringBuilder sb = new StringBuilder(fmt.getPrefix());
        if (fmt.isIncludeYear()) {
            sb.append("/").append(Year.now().getValue());
        }
        if (fmt.isIncludeType()) {
            sb.append("/").append("INDIVIDUAL".equalsIgnoreCase(customerType) ? "IND" : "CORP");
        }
        sb.append("/").append(String.format("%0" + fmt.getSequenceLength() + "d", next));

        return sb.toString();
    }

    @Transactional(readOnly = true)
    public CustomerNumberFormatResponse get() {
        return repository.findFirstByDeletedAtIsNull()
                .map(this::toResponse)
                .orElse(null);
    }

    @Transactional
    public CustomerNumberFormatResponse upsert(CustomerNumberFormatRequest request) {
        CustomerNumberFormat entity = repository.findFirstByDeletedAtIsNull().orElse(null);
        boolean isNew = entity == null;
        if (isNew) {
            entity = CustomerNumberFormat.builder()
                    .lastSequence(0).lastSequenceIndividual(0).lastSequenceCorporate(0)
                    .build();
        }
        entity.setPrefix(request.getPrefix());
        entity.setIncludeYear(request.isIncludeYear());
        entity.setIncludeType(request.isIncludeType());
        entity.setSequenceLength(request.getSequenceLength());
        CustomerNumberFormat saved = repository.save(entity);
        auditService.log("CustomerNumberFormat", saved.getId().toString(),
                isNew ? AuditAction.CREATE : AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    private CustomerNumberFormatResponse toResponse(CustomerNumberFormat e) {
        return CustomerNumberFormatResponse.builder()
                .id(e.getId())
                .prefix(e.getPrefix())
                .includeYear(e.isIncludeYear())
                .includeType(e.isIncludeType())
                .sequenceLength(e.getSequenceLength())
                .lastSequence(e.getLastSequence())
                .lastSequenceIndividual(e.getLastSequenceIndividual())
                .lastSequenceCorporate(e.getLastSequenceCorporate())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
