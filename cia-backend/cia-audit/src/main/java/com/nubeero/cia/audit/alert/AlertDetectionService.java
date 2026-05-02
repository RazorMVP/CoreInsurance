package com.nubeero.cia.audit.alert;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditLog;
import com.nubeero.cia.common.audit.AuditLogCreatedEvent;
import com.nubeero.cia.common.audit.AuditLogRepository;
import com.nubeero.cia.audit.login.LoginAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertDetectionService {

    private final AuditAlertConfigService configService;
    private final AuditAlertService alertService;
    private final AuditLogRepository auditLogRepository;
    private final LoginAuditService loginAuditService;

    /**
     * Runs synchronously on the request thread so {@code TenantContext}
     * (a ThreadLocal) is still populated when {@link AuditAlertConfigService}
     * resolves the per-tenant config. The detection logic is lightweight
     * (small COUNT queries) — keep it on the request thread.
     */
    @EventListener
    public void onAuditLogCreated(AuditLogCreatedEvent event) {
        try {
            AuditLog entry = event.getAuditLog();
            AuditAlertConfig config = configService.loadConfig();

            checkBulkDelete(entry, config);
            checkOffHoursActivity(entry, config);
            checkLargeFinancialApproval(entry, config);
        } catch (Exception e) {
            log.error("Alert detection failed for auditLog={}: {}", event.getAuditLog().getId(), e.getMessage());
        }
    }

    public void checkFailedLogins(String userId, String userName, String ipAddress) {
        try {
            AuditAlertConfig config = configService.loadConfig();
            Instant since = Instant.now().minusSeconds(3600);
            long failCount = loginAuditService.countRecentFailedLogins(userId, since);
            if (failCount >= config.getMaxFailedLoginAttempts()) {
                alertService.fire(
                        AlertType.FAILED_LOGIN,
                        "WARNING",
                        userId,
                        userName,
                        String.format("User '%s' has had %d failed login attempts in the last hour. IP: %s",
                                userName, failCount, ipAddress),
                        String.format("{\"failCount\":%d,\"ipAddress\":\"%s\"}", failCount, ipAddress)
                );
            }
        } catch (Exception e) {
            log.error("Failed login alert detection failed for userId={}: {}", userId, e.getMessage());
        }
    }

    private void checkBulkDelete(AuditLog entry, AuditAlertConfig config) {
        if (entry.getAction() != AuditAction.DELETE || entry.getUserId() == null) return;

        Instant windowStart = Instant.now().minusSeconds(config.getBulkDeleteWindowMinutes() * 60L);
        long deleteCount = auditLogRepository.countByUserIdAndActionAndTimestampAfter(
                entry.getUserId(), AuditAction.DELETE, windowStart);

        if (deleteCount >= config.getBulkDeleteCount()) {
            alertService.fire(
                    AlertType.BULK_DELETE,
                    "CRITICAL",
                    entry.getUserId(),
                    entry.getUserName(),
                    String.format("User '%s' performed %d deletions in %d minutes",
                            entry.getUserName(), deleteCount, config.getBulkDeleteWindowMinutes()),
                    String.format("{\"deleteCount\":%d,\"windowMinutes\":%d}",
                            deleteCount, config.getBulkDeleteWindowMinutes())
            );
        }
    }

    private void checkOffHoursActivity(AuditLog entry, AuditAlertConfig config) {
        if (entry.getAction() != AuditAction.APPROVE && entry.getAction() != AuditAction.CREATE) return;

        ZonedDateTime eventTime = ZonedDateTime.ofInstant(entry.getTimestamp(), ZoneId.of("Africa/Lagos"));
        boolean isOutsideHours = eventTime.toLocalTime().isBefore(config.getBusinessHoursStart())
                || eventTime.toLocalTime().isAfter(config.getBusinessHoursEnd());
        boolean isOutsideDays = !isBusinessDay(eventTime.getDayOfWeek(), config.getBusinessDays());

        if (isOutsideHours || isOutsideDays) {
            alertService.fire(
                    AlertType.OFF_HOURS_ACTIVITY,
                    "WARNING",
                    entry.getUserId(),
                    entry.getUserName(),
                    String.format("Off-hours %s by '%s' at %s",
                            entry.getAction().name(), entry.getUserName(), eventTime),
                    String.format("{\"action\":\"%s\",\"timestamp\":\"%s\"}", entry.getAction(), entry.getTimestamp())
            );
        }
    }

    private void checkLargeFinancialApproval(AuditLog entry, AuditAlertConfig config) {
        if (entry.getAction() != AuditAction.APPROVE || entry.getApprovalAmount() == null) return;

        if (entry.getApprovalAmount().compareTo(config.getLargeApprovalThreshold()) >= 0) {
            alertService.fire(
                    AlertType.LARGE_FINANCIAL_APPROVAL,
                    "WARNING",
                    entry.getUserId(),
                    entry.getUserName(),
                    String.format("Large approval of ₦%,.2f on %s (id=%s) by '%s'",
                            entry.getApprovalAmount(), entry.getEntityType(),
                            entry.getEntityId(), entry.getUserName()),
                    String.format("{\"amount\":%.2f,\"entityType\":\"%s\",\"entityId\":\"%s\"}",
                            entry.getApprovalAmount(), entry.getEntityType(), entry.getEntityId())
            );
        }
    }

    private boolean isBusinessDay(DayOfWeek day, String businessDays) {
        Set<String> days = Arrays.stream(businessDays.split(","))
                .map(String::trim)
                .collect(Collectors.toSet());
        return days.contains(day.name().substring(0, 3));
    }
}
