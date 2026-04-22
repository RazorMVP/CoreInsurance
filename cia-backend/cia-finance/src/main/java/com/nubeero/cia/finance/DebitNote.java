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
@Table(name = "debit_notes")
public class DebitNote extends BaseEntity {

    @Column(name = "debit_note_number", nullable = false, unique = true, length = 30)
    private String debitNoteNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DebitNoteStatus status = DebitNoteStatus.OUTSTANDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 30)
    private FinanceEntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "entity_reference", nullable = false, length = 60)
    private String entityReference;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "customer_name", nullable = false, length = 200)
    private String customerName;

    @Column(name = "broker_id")
    private UUID brokerId;

    @Column(name = "broker_name", length = 100)
    private String brokerName;

    @Column(name = "product_name", length = 100)
    private String productName;

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

    public String getDebitNoteNumber() { return debitNoteNumber; }
    public void setDebitNoteNumber(String debitNoteNumber) { this.debitNoteNumber = debitNoteNumber; }

    public DebitNoteStatus getStatus() { return status; }
    public void setStatus(DebitNoteStatus status) { this.status = status; }

    public FinanceEntityType getEntityType() { return entityType; }
    public void setEntityType(FinanceEntityType entityType) { this.entityType = entityType; }

    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }

    public String getEntityReference() { return entityReference; }
    public void setEntityReference(String entityReference) { this.entityReference = entityReference; }

    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public UUID getBrokerId() { return brokerId; }
    public void setBrokerId(UUID brokerId) { this.brokerId = brokerId; }

    public String getBrokerName() { return brokerName; }
    public void setBrokerName(String brokerName) { this.brokerName = brokerName; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

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
