package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-tenant CFO + compliance email recipient for {@code PeriodReopenedEvent}
 * — Slice 1.7c. Replaces the v1 Spring property
 * {@code cia.finance.period-reopen-recipients}; the
 * {@code PeriodReopenedNotificationListener} loads active rows here first
 * and only falls back to the property when no DB rows are configured.
 *
 * <p>{@code roleLabel} (e.g. {@code "CFO"}, {@code "Compliance Officer"})
 * is informational — the listener does not route by label, but auditors
 * sampling the distro list want to know which seat each recipient holds.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "tenant_reopen_recipient")
public class TenantReopenRecipient extends BaseEntity {

    @Column(name = "recipient", nullable = false, length = 255, unique = true)
    private String recipient;

    @Column(name = "role_label", length = 100)
    private String roleLabel;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
