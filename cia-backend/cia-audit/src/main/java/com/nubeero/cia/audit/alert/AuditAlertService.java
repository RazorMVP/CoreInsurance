package com.nubeero.cia.audit.alert;

import com.nubeero.cia.audit.alert.dto.AuditAlertResponse;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.notifications.NotificationService;
import com.nubeero.cia.notifications.model.NotificationChannel;
import com.nubeero.cia.notifications.model.NotificationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditAlertService {

    private final AuditAlertRepository alertRepository;
    private final NotificationService notificationService;

    @Transactional
    public AuditAlert fire(AlertType type, String severity, String userId, String userName,
                           String description, String metadata) {
        AuditAlert alert = AuditAlert.builder()
                .alertType(type)
                .severity(severity)
                .userId(userId)
                .userName(userName)
                .description(description)
                .metadata(metadata)
                .triggeredAt(Instant.now())
                .acknowledged(false)
                .build();
        AuditAlert saved = alertRepository.save(alert);
        notifyAuditors(saved);
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<AuditAlertResponse> listAll(Pageable pageable) {
        return alertRepository.findAllByOrderByTriggeredAtDesc(pageable).map(AuditAlertResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<AuditAlertResponse> listUnacknowledged(Pageable pageable) {
        return alertRepository.findByAcknowledgedOrderByTriggeredAtDesc(false, pageable)
                .map(AuditAlertResponse::from);
    }

    @Transactional
    public AuditAlertResponse acknowledge(UUID alertId) {
        AuditAlert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditAlert", alertId));
        alert.setAcknowledged(true);
        alert.setAcknowledgedBy(resolveUserName());
        alert.setAcknowledgedAt(Instant.now());
        return AuditAlertResponse.from(alertRepository.save(alert));
    }

    private void notifyAuditors(AuditAlert alert) {
        try {
            NotificationRequest request = NotificationRequest.builder()
                    .recipient("auditor@nubeero.com")
                    .subject("[CIA Alert] " + alert.getAlertType().name() + " — " + alert.getSeverity())
                    .body(alert.getDescription())
                    .channel(NotificationChannel.EMAIL)
                    .build();
            notificationService.send(request);
        } catch (Exception e) {
            log.warn("Failed to send alert notification for alertId={}: {}", alert.getId(), e.getMessage());
        }
    }

    private String resolveUserName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String name = jwt.getClaimAsString("preferred_username");
            return name != null ? name : jwt.getSubject();
        }
        return "system";
    }
}
