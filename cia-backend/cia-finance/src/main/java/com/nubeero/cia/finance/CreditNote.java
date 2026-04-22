package com.nubeero.cia.finance;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "credit_notes")
public class CreditNote extends BaseEntity {

    @Column(name = "credit_note_number", nullable = false, unique = true, length = 30)
    private String creditNoteNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CreditNoteStatus status = CreditNoteStatus.OUTSTANDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 30)
    private FinanceEntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "entity_reference", nullable = false, length = 60)
    private String entityReference;

    @Column(name = "beneficiary_id")
    private UUID beneficiaryId;

    @Column(name = "beneficiary_name", nullable = false, length = 200)
    private String beneficiaryName;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "tax_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "paid_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "NGN";

    @Column(name = "due_date")
    private LocalDate dueDate;

    // getters and setters

    public String getCreditNoteNumber() { return creditNoteNumber; }
    public void setCreditNoteNumber(String creditNoteNumber) { this.creditNoteNumber = creditNoteNumber; }

    public CreditNoteStatus getStatus() { return status; }
    public void setStatus(CreditNoteStatus status) { this.status = status; }

    public FinanceEntityType getEntityType() { return entityType; }
    public void setEntityType(FinanceEntityType entityType) { this.entityType = entityType; }

    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }

    public String getEntityReference() { return entityReference; }
    public void setEntityReference(String entityReference) { this.entityReference = entityReference; }

    public UUID getBeneficiaryId() { return beneficiaryId; }
    public void setBeneficiaryId(UUID beneficiaryId) { this.beneficiaryId = beneficiaryId; }

    public String getBeneficiaryName() { return beneficiaryName; }
    public void setBeneficiaryName(String beneficiaryName) { this.beneficiaryName = beneficiaryName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }

    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
}
