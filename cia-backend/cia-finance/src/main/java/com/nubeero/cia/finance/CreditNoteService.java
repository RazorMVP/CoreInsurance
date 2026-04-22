package com.nubeero.cia.finance;

import com.nubeero.cia.common.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class CreditNoteService {

    private final CreditNoteRepository creditNoteRepository;
    private final FinanceNumberService numberService;

    public CreditNoteService(CreditNoteRepository creditNoteRepository,
                              FinanceNumberService numberService) {
        this.creditNoteRepository = creditNoteRepository;
        this.numberService = numberService;
    }

    public Page<CreditNote> findAll(Pageable pageable) {
        return creditNoteRepository.findAllByDeletedAtIsNull(pageable);
    }

    public Page<CreditNote> findByStatus(CreditNoteStatus status, Pageable pageable) {
        return creditNoteRepository.findAllByStatusAndDeletedAtIsNull(status, pageable);
    }

    public Page<CreditNote> findByEntity(UUID entityId, Pageable pageable) {
        return creditNoteRepository.findAllByEntityIdAndDeletedAtIsNull(entityId, pageable);
    }

    public CreditNote findOrThrow(UUID id) {
        return creditNoteRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("CreditNote", id));
    }

    @Transactional
    public CreditNote create(FinanceEntityType entityType, UUID entityId, String entityReference,
                              UUID beneficiaryId, String beneficiaryName,
                              String description, BigDecimal amount, BigDecimal taxAmount,
                              String currencyCode) {
        CreditNote cn = new CreditNote();
        cn.setCreditNoteNumber(numberService.nextCreditNoteNumber());
        cn.setEntityType(entityType);
        cn.setEntityId(entityId);
        cn.setEntityReference(entityReference);
        cn.setBeneficiaryId(beneficiaryId);
        cn.setBeneficiaryName(beneficiaryName);
        cn.setDescription(description);
        cn.setAmount(amount);
        cn.setTaxAmount(taxAmount != null ? taxAmount : BigDecimal.ZERO);
        cn.setTotalAmount(amount.add(cn.getTaxAmount()));
        cn.setCurrencyCode(currencyCode != null ? currencyCode : "NGN");
        cn.setStatus(CreditNoteStatus.OUTSTANDING);
        cn.setCreatedBy(currentUser());
        return creditNoteRepository.save(cn);
    }

    @Transactional
    public void cancel(UUID id) {
        CreditNote cn = findOrThrow(id);
        if (cn.getStatus() == CreditNoteStatus.SETTLED) {
            throw new IllegalStateException("Cannot cancel a settled credit note");
        }
        cn.setStatus(CreditNoteStatus.CANCELLED);
        creditNoteRepository.save(cn);
    }

    // Called by PaymentService after posting or reversing a payment
    @Transactional
    public void recalculateStatus(UUID creditNoteId, BigDecimal newPaidAmount) {
        CreditNote cn = findOrThrow(creditNoteId);
        cn.setPaidAmount(newPaidAmount);
        if (newPaidAmount.compareTo(BigDecimal.ZERO) == 0) {
            cn.setStatus(CreditNoteStatus.OUTSTANDING);
        } else if (newPaidAmount.compareTo(cn.getTotalAmount()) >= 0) {
            cn.setStatus(CreditNoteStatus.SETTLED);
        } else {
            cn.setStatus(CreditNoteStatus.PARTIAL);
        }
        creditNoteRepository.save(cn);
    }

    private String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }
}
