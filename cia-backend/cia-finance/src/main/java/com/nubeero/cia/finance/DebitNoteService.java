package com.nubeero.cia.finance;

import com.nubeero.cia.common.event.EndorsementApprovedEvent;
import com.nubeero.cia.common.event.PolicyApprovedEvent;
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
public class DebitNoteService {

    private final DebitNoteRepository debitNoteRepository;
    private final FinanceNumberService numberService;

    public DebitNoteService(DebitNoteRepository debitNoteRepository,
                            FinanceNumberService numberService) {
        this.debitNoteRepository = debitNoteRepository;
        this.numberService = numberService;
    }

    public Page<DebitNote> findAll(Pageable pageable) {
        return debitNoteRepository.findAllByDeletedAtIsNull(pageable);
    }

    public Page<DebitNote> findByStatus(DebitNoteStatus status, Pageable pageable) {
        return debitNoteRepository.findAllByStatusAndDeletedAtIsNull(status, pageable);
    }

    public Page<DebitNote> findByCustomer(UUID customerId, Pageable pageable) {
        return debitNoteRepository.findAllByCustomerIdAndDeletedAtIsNull(customerId, pageable);
    }

    public Page<DebitNote> findByEntity(UUID entityId, Pageable pageable) {
        return debitNoteRepository.findAllByEntityIdAndDeletedAtIsNull(entityId, pageable);
    }

    public DebitNote findOrThrow(UUID id) {
        return debitNoteRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("DebitNote", id));
    }

    @Transactional
    public DebitNote createForPolicy(PolicyApprovedEvent event) {
        DebitNote dn = new DebitNote();
        dn.setDebitNoteNumber(numberService.nextDebitNoteNumber());
        dn.setEntityType(FinanceEntityType.POLICY);
        dn.setEntityId(event.policyId());
        dn.setEntityReference(event.policyNumber());
        dn.setCustomerId(event.customerId());
        dn.setCustomerName(event.customerName());
        dn.setBrokerId(event.brokerId());
        dn.setBrokerName(event.brokerName());
        dn.setProductName(event.productName());
        dn.setDescription("Premium for policy " + event.policyNumber());
        dn.setAmount(event.netPremium());
        dn.setTaxAmount(BigDecimal.ZERO);
        dn.setTotalAmount(event.netPremium());
        dn.setCurrencyCode(event.currencyCode());
        dn.setDueDate(event.policyEndDate());
        dn.setStatus(DebitNoteStatus.OUTSTANDING);
        dn.setCreatedBy(currentUser());
        return debitNoteRepository.save(dn);
    }

    @Transactional
    public DebitNote createForEndorsement(EndorsementApprovedEvent event) {
        DebitNote dn = new DebitNote();
        dn.setDebitNoteNumber(numberService.nextDebitNoteNumber());
        dn.setEntityType(FinanceEntityType.ENDORSEMENT);
        dn.setEntityId(event.endorsementId());
        dn.setEntityReference(event.endorsementNumber());
        dn.setCustomerId(event.customerId());
        dn.setCustomerName(event.customerName());
        dn.setBrokerId(event.brokerId());
        dn.setBrokerName(event.brokerName());
        dn.setProductName(event.productName());
        dn.setDescription("Additional premium for endorsement " + event.endorsementNumber()
                + " on policy " + event.policyNumber());
        dn.setAmount(event.premiumAdjustment());
        dn.setTaxAmount(BigDecimal.ZERO);
        dn.setTotalAmount(event.premiumAdjustment());
        dn.setCurrencyCode(event.currencyCode());
        dn.setStatus(DebitNoteStatus.OUTSTANDING);
        return debitNoteRepository.save(dn);
    }

    @Transactional
    public void cancel(UUID id) {
        DebitNote dn = findOrThrow(id);
        if (dn.getStatus() == DebitNoteStatus.SETTLED) {
            throw new IllegalStateException("Cannot cancel a settled debit note");
        }
        dn.setStatus(DebitNoteStatus.CANCELLED);
        debitNoteRepository.save(dn);
    }

    @Transactional
    public void markVoid(UUID id) {
        DebitNote dn = findOrThrow(id);
        if (dn.getPaidAmount().compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("Cannot void a partially or fully paid debit note");
        }
        dn.setStatus(DebitNoteStatus.VOID);
        debitNoteRepository.save(dn);
    }

    // Called by ReceiptService after posting or reversing a receipt
    @Transactional
    public void recalculateStatus(UUID debitNoteId, BigDecimal newPaidAmount) {
        DebitNote dn = findOrThrow(debitNoteId);
        dn.setPaidAmount(newPaidAmount);
        if (newPaidAmount.compareTo(BigDecimal.ZERO) == 0) {
            dn.setStatus(DebitNoteStatus.OUTSTANDING);
        } else if (newPaidAmount.compareTo(dn.getTotalAmount()) >= 0) {
            dn.setStatus(DebitNoteStatus.SETTLED);
        } else {
            dn.setStatus(DebitNoteStatus.PARTIAL);
        }
        debitNoteRepository.save(dn);
    }

    private String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }
}
