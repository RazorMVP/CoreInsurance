package com.nubeero.cia.audit.login;

import com.nubeero.cia.audit.login.dto.LoginAuditLogResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class LoginAuditService {

    private final LoginAuditLogRepository repository;

    @Transactional
    public void record(String userId, String userName, LoginEventType eventType,
                       boolean success, String failureReason,
                       String ipAddress, String userAgent) {
        LoginAuditLog entry = LoginAuditLog.builder()
                .eventType(eventType)
                .userId(userId)
                .userName(userName)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .timestamp(Instant.now())
                .success(success)
                .failureReason(failureReason)
                .build();
        repository.save(entry);
    }

    @Transactional(readOnly = true)
    public Page<LoginAuditLogResponse> listAll(Pageable pageable) {
        return repository.findAll(pageable).map(LoginAuditLogResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<LoginAuditLogResponse> listByUser(String userId, Pageable pageable) {
        return repository.findByUserIdOrderByTimestampDesc(userId, pageable)
                .map(LoginAuditLogResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<LoginAuditLogResponse> listByDateRange(Instant from, Instant to, Pageable pageable) {
        return repository.findByTimestampBetweenOrderByTimestampDesc(from, to, pageable)
                .map(LoginAuditLogResponse::from);
    }

    @Transactional(readOnly = true)
    public long countRecentFailedLogins(String userId, Instant since) {
        return repository.countByUserIdAndEventTypeAndTimestampAfter(
                userId, LoginEventType.LOGIN_FAILED, since);
    }
}
