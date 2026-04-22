package com.nubeero.cia.finance;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment extends BaseEntity {

    @Column(name = "payment_number", nullable = false, unique = true, length = 30)
    private String paymentNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credit_note_id", nullable = false)
    private CreditNote creditNote;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "bank_id")
    private UUID bankId;

    @Column(name = "bank_name", length = 100)
    private String bankName;

    @Column(name = "bank_account_name", length = 200)
    private String bankAccountName;

    @Column(name = "bank_account_number", length = 50)
    private String bankAccountNumber;

    @Column(name = "narration", columnDefinition = "TEXT")
    private String narration;

    @Column(name = "posted_by", length = 100)
    private String postedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransactionStatus status = TransactionStatus.POSTED;

    @Column(name = "reversal_reason", columnDefinition = "TEXT")
    private String reversalReason;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    @Column(name = "reversed_by", length = 100)
    private String reversedBy;

    // getters and setters

    public String getPaymentNumber() { return paymentNumber; }
    public void setPaymentNumber(String paymentNumber) { this.paymentNumber = paymentNumber; }

    public CreditNote getCreditNote() { return creditNote; }
    public void setCreditNote(CreditNote creditNote) { this.creditNote = creditNote; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public LocalDate getPaymentDate() { return paymentDate; }
    public void setPaymentDate(LocalDate paymentDate) { this.paymentDate = paymentDate; }

    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }

    public UUID getBankId() { return bankId; }
    public void setBankId(UUID bankId) { this.bankId = bankId; }

    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }

    public String getBankAccountName() { return bankAccountName; }
    public void setBankAccountName(String bankAccountName) { this.bankAccountName = bankAccountName; }

    public String getBankAccountNumber() { return bankAccountNumber; }
    public void setBankAccountNumber(String bankAccountNumber) { this.bankAccountNumber = bankAccountNumber; }

    public String getNarration() { return narration; }
    public void setNarration(String narration) { this.narration = narration; }

    public String getPostedBy() { return postedBy; }
    public void setPostedBy(String postedBy) { this.postedBy = postedBy; }

    public TransactionStatus getStatus() { return status; }
    public void setStatus(TransactionStatus status) { this.status = status; }

    public String getReversalReason() { return reversalReason; }
    public void setReversalReason(String reversalReason) { this.reversalReason = reversalReason; }

    public Instant getReversedAt() { return reversedAt; }
    public void setReversedAt(Instant reversedAt) { this.reversedAt = reversedAt; }

    public String getReversedBy() { return reversedBy; }
    public void setReversedBy(String reversedBy) { this.reversedBy = reversedBy; }
}
