package com.nubeero.cia.common.audit;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
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

    // @JdbcTypeCode(SqlTypes.JSON) — Hibernate 6 contract for String→jsonb.
    // Without it, the JPA driver binds via PreparedStatement.setString which
    // ships parameters as TEXT/varchar; Postgres rejects TEXT→jsonb without
    // an explicit cast and the audit_log INSERT fails. The columnDefinition
    // controls DDL generation only — not parameter binding.
    @Column(name = "old_value", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String newValue;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "approval_amount", precision = 19, scale = 2)
    private BigDecimal approvalAmount;

    /**
     * Free-text justification recorded for any "reasoned" action — most
     * commonly DELETE (master-data soft deletes), but also reusable for
     * REJECT / OVERRIDE / REOPEN_PERIOD flows. Nullable: CREATE / UPDATE
     * actions don't require a reason. V47 adds the column.
     */
    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;
}
