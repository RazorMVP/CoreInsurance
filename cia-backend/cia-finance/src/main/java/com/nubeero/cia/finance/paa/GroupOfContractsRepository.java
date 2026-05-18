package com.nubeero.cia.finance.paa;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
