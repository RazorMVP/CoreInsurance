package com.nubeero.cia.finance.ifrs9;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Singleton-per-tenant accessor. The DB partial unique index in V39
 * guarantees at most one non-deleted row, so the {@code Optional} is the
 * natural API. Mirrors {@code PaaConfigRepository} (Slice 2.1).
 */
@Repository
public interface Ifrs9ConfigRepository extends JpaRepository<Ifrs9Config, UUID> {

    Optional<Ifrs9Config> findFirstByDeletedAtIsNull();

    default Optional<Ifrs9Config> findActive() {
        return findFirstByDeletedAtIsNull();
    }
}
