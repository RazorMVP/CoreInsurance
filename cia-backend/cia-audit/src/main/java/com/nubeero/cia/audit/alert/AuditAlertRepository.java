package com.nubeero.cia.audit.alert;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditAlertRepository extends JpaRepository<AuditAlert, UUID> {

    Page<AuditAlert> findAllByOrderByTriggeredAtDesc(Pageable pageable);

    Page<AuditAlert> findByAcknowledgedOrderByTriggeredAtDesc(boolean acknowledged, Pageable pageable);

    Page<AuditAlert> findByAlertTypeOrderByTriggeredAtDesc(AlertType alertType, Pageable pageable);
}
