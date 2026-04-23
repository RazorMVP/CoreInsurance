package com.nubeero.cia.audit.alert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AuditAlertConfigRepository extends JpaRepository<AuditAlertConfig, UUID> {

    Optional<AuditAlertConfig> findFirstByOrderByCreatedAtAsc();
}
