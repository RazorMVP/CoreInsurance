package com.nubeero.cia.finance.paa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Singleton-per-tenant accessor. Service layer (Slice 2.x) wraps
 * {@link #findActive()} with a lazy-create fallback that seeds the default
 * row on first access. The DB partial unique index in V36 guarantees there's
 * at most one non-deleted row, so the {@code Optional} is the natural API.
 */
@Repository
public interface PaaConfigRepository extends JpaRepository<PaaConfig, UUID> {

    Optional<PaaConfig> findFirstByDeletedAtIsNull();

    default Optional<PaaConfig> findActive() {
        return findFirstByDeletedAtIsNull();
    }
}
