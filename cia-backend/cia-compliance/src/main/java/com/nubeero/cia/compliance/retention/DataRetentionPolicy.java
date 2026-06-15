package com.nubeero.cia.compliance.retention;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Per-tenant NDPR retention policy. One row per tenant schema (service-enforced singleton). */
@Entity
@Table(name = "data_retention_policy")
@Getter
@Setter
@NoArgsConstructor
public class DataRetentionPolicy extends BaseEntity {

    @Column(name = "customer_pii_retention_days", nullable = false)
    private int customerPiiRetentionDays = 2555;

    @Column(name = "purge_enabled", nullable = false)
    private boolean purgeEnabled = false;

    @Column(name = "purge_frequency", nullable = false, length = 10)
    private String purgeFrequency = "WEEKLY";   // WEEKLY | MONTHLY

    @Column(name = "purge_day_of_week", nullable = false)
    private int purgeDayOfWeek = 0;             // 0=Sun..6=Sat

    @Column(name = "purge_hour_utc", nullable = false)
    private int purgeHourUtc = 3;               // 0..23

    @Column(name = "last_purge_run_at")
    private Instant lastPurgeRunAt;
}
