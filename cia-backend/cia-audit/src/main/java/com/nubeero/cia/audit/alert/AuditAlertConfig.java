package com.nubeero.cia.audit.alert;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "audit_alert_config")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditAlertConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "business_hours_start", nullable = false)
    private LocalTime businessHoursStart;

    @Column(name = "business_hours_end", nullable = false)
    private LocalTime businessHoursEnd;

    @Column(name = "business_days", nullable = false)
    private String businessDays;

    @Column(name = "large_approval_threshold", nullable = false, precision = 19, scale = 2)
    private BigDecimal largeApprovalThreshold;

    @Column(name = "max_failed_login_attempts", nullable = false)
    private int maxFailedLoginAttempts;

    @Column(name = "bulk_delete_count", nullable = false)
    private int bulkDeleteCount;

    @Column(name = "bulk_delete_window_minutes", nullable = false)
    private int bulkDeleteWindowMinutes;

    @Column(name = "retention_years", nullable = false)
    private int retentionYears;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
