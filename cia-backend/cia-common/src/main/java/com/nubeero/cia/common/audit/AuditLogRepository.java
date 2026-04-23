package com.nubeero.cia.common.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID>,
        JpaSpecificationExecutor<AuditLog> {

    Page<AuditLog> findByEntityTypeAndEntityId(String entityType, String entityId, Pageable pageable);

    Page<AuditLog> findByUserId(String userId, Pageable pageable);

    Page<AuditLog> findByTimestampBetween(Instant from, Instant to, Pageable pageable);

    long countByUserIdAndActionAndTimestampAfter(String userId, AuditAction action, Instant after);

    @Query("SELECT a.userId AS userId, a.userName AS userName, COUNT(a) AS actionCount " +
           "FROM AuditLog a WHERE a.timestamp BETWEEN :from AND :to " +
           "GROUP BY a.userId, a.userName ORDER BY COUNT(a) DESC")
    List<UserActivityProjection> findUserActivitySummary(@Param("from") Instant from, @Param("to") Instant to);

    interface UserActivityProjection {
        String getUserId();
        String getUserName();
        Long getActionCount();
    }
}
