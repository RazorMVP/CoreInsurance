package com.nubeero.cia.setup.quote;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface QuoteConfigRepository extends JpaRepository<QuoteConfig, UUID> {
    Optional<QuoteConfig> findFirstByDeletedAtIsNull();
}
