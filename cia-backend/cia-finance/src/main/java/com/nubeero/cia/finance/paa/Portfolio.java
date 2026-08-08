package com.nubeero.cia.finance.paa;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * IFRS 17 portfolio — a set of contracts subject to similar risks and managed
 * together (§14). The natural grain for GB insurance is one row per
 * class-of-business × sales-channel combination.
 *
 * <p>Master data; populated by {@code ContractGroupingService} (Slice 2.2)
 * on first need per tenant. {@code classOfBusinessId} is a raw UUID rather
 * than a {@code @ManyToOne} so the PAA module doesn't pull the
 * {@code ClassOfBusiness} mapping into its persistence context — same loose-
 * coupling pattern as {@code JournalEntryLine.portfolioId}.
 *
 * <p>{@code contractNature} (V76, FAC / IFRS-17 PAA workstream Task 1)
 * distinguishes direct-policy portfolios from facultative-reinsurance
 * portfolios reserved for a later slice. Every portfolio created by
 * {@code ContractGroupingService} today is {@link ContractNature#DIRECT}.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "portfolio")
public class Portfolio extends BaseEntity {

    @Column(name = "code", nullable = false, unique = true, length = 20, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "class_of_business_id")
    private UUID classOfBusinessId;

    @Column(name = "description")
    private String description;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "contract_nature", updatable = false)
    private ContractNature contractNature = ContractNature.DIRECT;
}
