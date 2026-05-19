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
}
