package com.nubeero.cia.common.audit;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private String entityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditAction action;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "user_name")
    private String userName;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(name = "old_value", columnDefinition = "jsonb")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "jsonb")
    private String newValue;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "session_id")
    private String sessionId;
}
