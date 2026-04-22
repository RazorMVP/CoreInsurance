package com.nubeero.cia.setup.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CurrencyRepository extends JpaRepository<Currency, UUID> {
    Optional<Currency> findByCodeAndDeletedAtIsNull(String code);
    Optional<Currency> findByIsDefaultTrueAndDeletedAtIsNull();
    List<Currency> findAllByDeletedAtIsNull();
}
