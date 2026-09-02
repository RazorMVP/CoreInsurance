package com.nubeero.cia.partner.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One durable row per (Partner App, calendar day) — the flushed-to-DB counterpart of the live
 * Redis/in-memory {@link PartnerUsageRollupStore} counters. Written exclusively by {@link
 * PartnerUsageFlushActivitiesImpl}'s daily 03:00 UTC cron (never by the request path itself —
 * {@link com.nubeero.cia.partner.config.PartnerRequestMetricsFilter} only ever touches the live
 * store); read by {@code PortalUsageService} (cia-partner-portal-bff) to compose the "history"
 * section of {@code GET /portal/apps/{id}/usage}.
 *
 * <p>Not a {@code BaseEntity} subclass — V81 gives this table no {@code created_at} /
 * {@code updated_at} / {@code created_by} / {@code deleted_at} columns (mirrors {@code
 * WebhookDeliveryLog}'s append/upsert-only shape, not a soft-deletable master-data row).
 */
@Entity
@Table(name = "partner_request_daily",
        uniqueConstraints = @UniqueConstraint(name = "ux_prd_app_date", columnNames = {"partner_app_id", "usage_date"}))
@Getter
@Setter
@NoArgsConstructor
public class PartnerRequestDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "partner_app_id", nullable = false)
    private UUID partnerAppId;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(nullable = false)
    private long total;

    @Column(nullable = false)
    private long success;

    @Column(name = "client_error", nullable = false)
    private long clientError;

    @Column(name = "server_error", nullable = false)
    private long serverError;
}
