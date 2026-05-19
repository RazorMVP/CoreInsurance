package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Chart-of-accounts row. Seeded by V32; immutable from the service layer
 * (CRUD is intentionally absent until the post-Phase-7 tenant-customisation
 * epic).
 * <p>
 * IFRS 17 and IFRS 9 role columns store the {@link Ifrs17Role} /
 * {@link Ifrs9Role} enum names as VARCHAR(50). The vocabulary is locked in
 * Java; the DB column is intentionally free-text so adding a new role does
 * not require a CHECK constraint migration.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "chart_of_account")
public class ChartOfAccount extends BaseEntity {

    @Column(name = "code", nullable = false, unique = true, length = 20, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private ChartOfAccount parent;

    @Enumerated(EnumType.STRING)
    @Column(name = "ifrs17_role", length = 50)
    private Ifrs17Role ifrs17Role;

    @Enumerated(EnumType.STRING)
    @Column(name = "ifrs9_role", length = 50)
    private Ifrs9Role ifrs9Role;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
