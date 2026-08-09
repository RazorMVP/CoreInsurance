package com.nubeero.cia.finance.paa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PortfolioRepository extends JpaRepository<Portfolio, UUID> {

    Optional<Portfolio> findByCodeAndDeletedAtIsNull(String code);

    List<Portfolio> findByDeletedAtIsNullOrderByCodeAsc();

    List<Portfolio> findByActiveTrueAndDeletedAtIsNullOrderByCodeAsc();

    List<Portfolio> findByClassOfBusinessIdAndDeletedAtIsNullOrderByCodeAsc(UUID classOfBusinessId);

    /**
     * Nature-scoped lookup — a class-of-business can have a DIRECT portfolio
     * AND a FAC_INWARD/FAC_OUTWARD portfolio at the same time (segregated by
     * the nature-prefixed {@link Portfolio#getCode()}); resolution must never
     * cross natures when reusing an existing portfolio.
     *
     * @since FAC / IFRS-17 PAA workstream Task 2
     */
    List<Portfolio> findByClassOfBusinessIdAndContractNatureAndDeletedAtIsNullOrderByCodeAsc(
        UUID classOfBusinessId, ContractNature contractNature);
}
