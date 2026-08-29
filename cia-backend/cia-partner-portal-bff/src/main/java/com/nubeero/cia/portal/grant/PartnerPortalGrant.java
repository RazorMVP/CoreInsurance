package com.nubeero.cia.portal.grant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Cross-tenant registry row mapping a partner human developer ({@code partnerUserId}, the
 * Partner Portal's own Keycloak-issued user id — distinct from the M2M
 * client_credentials principal cia-partner-api authenticates) to a single Partner App they
 * may manage, with a {@link GrantRole}.
 *
 * <p>Lives in {@code public.partner_portal_grant} — a registry table like {@code public.tenants},
 * <strong>not</strong> a tenant-schema entity. {@code tenantSchema} + {@code partnerAppId}
 * together identify the app; {@code partnerAppId} is a soft cross-schema reference (the actual
 * Partner App row lives inside that tenant's schema) with no DB foreign key, by design.
 *
 * <p>Intentionally <strong>not</strong> a {@code BaseEntity} subclass: this registry table has no
 * {@code updated_at} column (rows are immutable once created — only {@code deleted_at} changes on
 * revocation), so the shared {@code BaseEntity} shape doesn't fit. {@code createdAt} is populated
 * by a {@code @PrePersist} callback rather than Spring Data JPA auditing ({@code @CreatedDate}),
 * so this entity does not require {@code CiaCommonAutoConfiguration}'s
 * {@code @EnableJpaAuditing} to be present on the test/application context.
 */
@Getter
@Setter
@Entity
@Table(schema = "public", name = "partner_portal_grant")
public class PartnerPortalGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "partner_user_id", nullable = false, updatable = false)
    private UUID partnerUserId;

    @Column(name = "partner_user_email", nullable = false, length = 255)
    private String partnerUserEmail;

    @Column(name = "tenant_schema", nullable = false, updatable = false, length = 63)
    private String tenantSchema;

    @Column(name = "partner_app_id", nullable = false, updatable = false)
    private UUID partnerAppId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private GrantRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false, length = 255)
    private String createdBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
