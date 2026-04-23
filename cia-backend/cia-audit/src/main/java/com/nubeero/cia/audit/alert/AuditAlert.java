package com.nubeero.cia.audit.alert;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_alert")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false)
    private AlertType alertType;

    @Column(nullable = false)
    private String severity;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "user_name")
    private String userName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "triggered_at", nullable = false)
    private Instant triggeredAt;

    @Column(nullable = false)
    private boolean acknowledged;

    @Column(name = "acknowledged_by")
    private String acknowledgedBy;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;
}
