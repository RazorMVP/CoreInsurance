package com.nubeero.cia.finance.paa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GroupOfContractsRepository extends JpaRepository<GroupOfContracts, UUID> {

    /**
     * Lookup by the natural-key triple. Slice 2.2's ContractGroupingService
     * uses this to upsert: if absent, create the group; if present, assign
     * the contract to it.
     */
    Optional<GroupOfContracts> findByPortfolioIdAndCohortYearAndOnerousnessAndDeletedAtIsNull(
        UUID portfolioId, Integer cohortYear, Onerousness onerousness);

    List<GroupOfContracts> findByPortfolioIdAndDeletedAtIsNullOrderByCohortYearAscOnerousnessAsc(UUID portfolioId);

    List<GroupOfContracts> findByCohortYearAndDeletedAtIsNullOrderByPortfolioIdAsc(Integer cohortYear);

    List<GroupOfContracts> findByStatusAndDeletedAtIsNullOrderByCohortYearAsc(GroupStatus status);

    /**
     * Multi-predicate search for the Phase 5 frontend list. All filters are
     * optional — pass {@code null} to skip the predicate. Soft-deleted rows
     * are excluded. Ordered by (cohort year DESC, portfolio code ASC,
     * onerousness ASC) — most recent cohort first, with onerous groups
     * appearing last within a (portfolio, cohort) pair.
     *
     * <p>{@code contractNature} is the portfolio's {@link ContractNature}
     * dimension (DIRECT / FAC_INWARD / FAC_OUTWARD) — FAC / IFRS-17 PAA
     * workstream Task 7 M1, mirroring the other four predicates exactly.
     *
     * @since Module 12 Phase 5 — slice F5.11
     */
    @Query("""
        SELECT g FROM GroupOfContracts g
        JOIN g.portfolio p
        WHERE g.deletedAt IS NULL
          AND (:portfolioId    IS NULL OR p.id = :portfolioId)
          AND (:cohortYear     IS NULL OR g.cohortYear = :cohortYear)
          AND (:onerousness    IS NULL OR g.onerousness = :onerousness)
          AND (:status         IS NULL OR g.status = :status)
          AND (:contractNature IS NULL OR p.contractNature = :contractNature)
        ORDER BY g.cohortYear DESC, p.code ASC, g.onerousness ASC
        """)
    List<GroupOfContracts> search(
        @Param("portfolioId")    UUID portfolioId,
        @Param("cohortYear")     Integer cohortYear,
        @Param("onerousness")    Onerousness onerousness,
        @Param("status")         GroupStatus status,
        @Param("contractNature") ContractNature contractNature
    );
}
