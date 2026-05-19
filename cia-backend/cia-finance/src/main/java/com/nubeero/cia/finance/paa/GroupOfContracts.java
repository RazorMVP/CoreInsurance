package com.nubeero.cia.finance.paa;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * IFRS 17 group of contracts (§16-22) — an annual cohort within a portfolio,
 * further split by onerousness at initial recognition. The smallest unit of
 * account for PAA measurement.
 *
 * <p>Per §22, a contract's assignment is permanent at initial recognition:
 * onerousness is not a flag that flips on the same row. Loss components for
 * groups whose measurement later deteriorates are recognised against the
 * group the contracts were originally assigned to.
 *
 * <p>The {@code (portfolio, cohort_year, onerousness)} triple is unique, and
 * the DB enforces it via {@code uq_group_portfolio_cohort_onerousness} in V36.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
    name = "group_of_contracts",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_group_portfolio_cohort_onerousness",
        columnNames = {"portfolio_id", "cohort_year", "onerousness"}
    )
)
public class GroupOfContracts extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "portfolio_id", nullable = false)
    private Portfolio portfolio;

    @Column(name = "cohort_year", nullable = false, updatable = false)
    private Integer cohortYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "onerousness", nullable = false, length = 40, updatable = false)
    private Onerousness onerousness;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GroupStatus status = GroupStatus.OPEN;
}
