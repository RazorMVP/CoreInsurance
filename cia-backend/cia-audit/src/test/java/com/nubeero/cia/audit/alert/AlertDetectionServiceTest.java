package com.nubeero.cia.audit.alert;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nubeero.cia.audit.login.LoginAuditService;
import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditLog;
import com.nubeero.cia.common.audit.AuditLogCreatedEvent;
import com.nubeero.cia.common.audit.AuditLogRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the security-alert threshold logic in {@link AlertDetectionService}
 * — failed-login flood, bulk-delete, large-financial-approval, and off-hours
 * activity. These are the Module 10 compliance alerts; the rule is "fire iff the
 * configured threshold is met", so each is tested at and below its boundary with
 * mocked collaborators (config + count queries). First tests in {@code cia-audit}
 * ({@code zero-test-modules} backlog).
 */
@ExtendWith(MockitoExtension.class)
class AlertDetectionServiceTest {

    @Mock AuditAlertConfigService configService;
    @Mock AuditAlertService alertService;
    @Mock AuditLogRepository auditLogRepository;
    @Mock LoginAuditService loginAuditService;

    @InjectMocks AlertDetectionService service;

    private static AuditAlertConfig config() {
        return AuditAlertConfig.builder()
                .businessHoursStart(LocalTime.of(9, 0))
                .businessHoursEnd(LocalTime.of(17, 0))
                .businessDays("MON,TUE,WED,THU,FRI")
                .largeApprovalThreshold(new BigDecimal("50000000"))
                .maxFailedLoginAttempts(3)
                .bulkDeleteCount(5)
                .bulkDeleteWindowMinutes(5)
                .build();
    }

    // 2026-06-17 is a Wednesday. Africa/Lagos = UTC+1.
    private static final Instant BUSINESS_HOURS = Instant.parse("2026-06-17T11:00:00Z"); // Wed 12:00 Lagos
    private static final Instant OFF_HOURS      = Instant.parse("2026-06-17T01:00:00Z"); // Wed 02:00 Lagos

    // ── Failed logins ──────────────────────────────────────────────────────

    @Test
    void failedLogins_atThreshold_firesAlert() {
        when(configService.loadConfig()).thenReturn(config());
        when(loginAuditService.countRecentFailedLogins(eq("u-1"), any())).thenReturn(3L);

        service.checkFailedLogins("u-1", "Alice", "1.2.3.4");

        verify(alertService).fire(eq(AlertType.FAILED_LOGIN), any(), any(), any(), any(), any());
    }

    @Test
    void failedLogins_belowThreshold_doesNotFire() {
        when(configService.loadConfig()).thenReturn(config());
        when(loginAuditService.countRecentFailedLogins(eq("u-1"), any())).thenReturn(2L);

        service.checkFailedLogins("u-1", "Alice", "1.2.3.4");

        verify(alertService, never()).fire(any(), any(), any(), any(), any(), any());
    }

    // ── Bulk delete (via the AuditLogCreatedEvent path) ────────────────────

    @Test
    void bulkDelete_atThreshold_firesAlert() {
        when(configService.loadConfig()).thenReturn(config());
        when(auditLogRepository.countByUserIdAndActionAndTimestampAfter(eq("u-1"), eq(AuditAction.DELETE), any()))
                .thenReturn(5L);

        service.onAuditLogCreated(event(AuditLog.builder()
                .action(AuditAction.DELETE).userId("u-1").userName("Alice").timestamp(BUSINESS_HOURS).build()));

        verify(alertService).fire(eq(AlertType.BULK_DELETE), any(), any(), any(), any(), any());
    }

    @Test
    void bulkDelete_belowThreshold_doesNotFire() {
        when(configService.loadConfig()).thenReturn(config());
        when(auditLogRepository.countByUserIdAndActionAndTimestampAfter(eq("u-1"), eq(AuditAction.DELETE), any()))
                .thenReturn(4L);

        service.onAuditLogCreated(event(AuditLog.builder()
                .action(AuditAction.DELETE).userId("u-1").userName("Alice").timestamp(BUSINESS_HOURS).build()));

        verify(alertService, never()).fire(any(), any(), any(), any(), any(), any());
    }

    // ── Large financial approval ───────────────────────────────────────────

    @Test
    void largeApproval_atOrAboveThreshold_duringBusinessHours_firesOnlyLargeApproval() {
        when(configService.loadConfig()).thenReturn(config());

        service.onAuditLogCreated(event(AuditLog.builder()
                .action(AuditAction.APPROVE).userId("u-1").userName("Alice")
                .entityType("Policy").entityId("p-1")
                .approvalAmount(new BigDecimal("60000000"))   // ≥ 50,000,000
                .timestamp(BUSINESS_HOURS)                    // so off-hours does NOT also fire
                .build()));

        verify(alertService).fire(eq(AlertType.LARGE_FINANCIAL_APPROVAL), any(), any(), any(), any(), any());
        verify(alertService, never()).fire(eq(AlertType.OFF_HOURS_ACTIVITY), any(), any(), any(), any(), any());
    }

    @Test
    void approval_belowThreshold_duringBusinessHours_doesNotFire() {
        when(configService.loadConfig()).thenReturn(config());

        service.onAuditLogCreated(event(AuditLog.builder()
                .action(AuditAction.APPROVE).userId("u-1").userName("Alice")
                .approvalAmount(new BigDecimal("49999999"))   // < threshold
                .timestamp(BUSINESS_HOURS)
                .build()));

        verify(alertService, never()).fire(any(), any(), any(), any(), any(), any());
    }

    // ── Off-hours activity ─────────────────────────────────────────────────

    @Test
    void offHoursApproval_firesOffHoursAlert() {
        when(configService.loadConfig()).thenReturn(config());

        service.onAuditLogCreated(event(AuditLog.builder()
                .action(AuditAction.APPROVE).userId("u-1").userName("Alice")
                .approvalAmount(null)            // null → large-approval check returns early
                .timestamp(OFF_HOURS)            // 02:00 Lagos → off-hours
                .build()));

        verify(alertService).fire(eq(AlertType.OFF_HOURS_ACTIVITY), any(), any(), any(), any(), any());
    }

    private AuditLogCreatedEvent event(AuditLog log) {
        return new AuditLogCreatedEvent(this, log);
    }
}
