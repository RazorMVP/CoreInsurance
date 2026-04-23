package com.nubeero.cia.audit.login;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface LoginAuditLogRepository extends JpaRepository<LoginAuditLog, UUID> {

    Page<LoginAuditLog> findByUserIdOrderByTimestampDesc(String userId, Pageable pageable);

    Page<LoginAuditLog> findByEventTypeOrderByTimestampDesc(LoginEventType eventType, Pageable pageable);

    Page<LoginAuditLog> findByTimestampBetweenOrderByTimestampDesc(Instant from, Instant to, Pageable pageable);

    long countByUserIdAndEventTypeAndTimestampAfter(String userId, LoginEventType eventType, Instant after);
}
