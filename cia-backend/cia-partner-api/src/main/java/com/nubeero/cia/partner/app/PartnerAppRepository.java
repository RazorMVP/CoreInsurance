package com.nubeero.cia.partner.app;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PartnerAppRepository extends JpaRepository<PartnerApp, UUID> {
    Optional<PartnerApp> findByClientId(String clientId);
}
