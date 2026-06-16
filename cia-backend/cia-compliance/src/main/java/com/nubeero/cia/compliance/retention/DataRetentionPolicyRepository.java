package com.nubeero.cia.compliance.retention;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataRetentionPolicyRepository extends JpaRepository<DataRetentionPolicy, UUID> {
    /** Singleton lookup — the first (and only) non-deleted row in the current tenant schema. */
    Optional<DataRetentionPolicy> findFirstByDeletedAtIsNull();
}
