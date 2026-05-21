package com.nubeero.cia.finance.gl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditLog;
import com.nubeero.cia.common.audit.AuditLogRepository;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.entity.LockableByPeriod;
import com.nubeero.cia.common.exception.BusinessRuleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Decision-matrix tests for {@link PeriodLockService#checkWrite} plus the
 * lifecycle transitions ({@code softClose / hardClose / reopen}). The
 * 9-state matrix from the Slice 1.7 design pass:
 *
 * <pre>
 *  entity null              → ALLOW
 *  entity.lockDate null     → ALLOW
 *  entity.isReversal()      → ALLOW (carve-out)
 *  no period for date       → REJECT
 *  OPEN period, no lock     → ALLOW
 *  SOFT lock, in grace      → ALLOW
 *  SOFT lock, past grace,  no override → REJECT
 *  SOFT lock, past grace,  with override → OVERRIDE
 *  HARD lock                → REJECT
 * </pre>
 *
 * <p>Mockito agent under Java 25 cannot redefine concrete classes
 * (documented in {@code JournalEntryServiceTest} header). We therefore use
 * real {@link FiscalPeriodResolver} and {@link FiscalPeriodLookupCache}
 * instances backed by mocked repositories — same isolation depth, but the
 * mock surface stays on the interface types Mockito handles via dynamic
 * proxies.
 *
 * @since Module 12, Slice 1.7
 */
@ExtendWith(MockitoExtension.class)
class PeriodLockServiceTest {

    private static final LocalDate LOCK_DATE = LocalDate.of(2026, 5, 14);
    private static final UUID PERIOD_ID = UUID.randomUUID();

    @Mock private FiscalPeriodRepository periodRepository;
    @Mock private PeriodLockRepository lockRepository;
    @Mock private FiscalYearRepository fiscalYearRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private ApplicationEventPublisher auditEvents;
    @Mock private ApplicationEventPublisher events;

    private PeriodLockService service;
    private FiscalPeriodResolver resolver;
    private FiscalPeriodLookupCache cache;
    private AuditService auditService;
    private FiscalPeriod period;

    @BeforeEach
    void init() {
        resolver = new FiscalPeriodResolver(periodRepository);
        cache = new FiscalPeriodLookupCache();
        // Real AuditService — Java 25's Mockito agent cannot redefine concrete
        // Spring services. Built from mocked repository + real ObjectMapper.
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        auditService = new AuditService(auditLogRepository, objectMapper, auditEvents);
        // save returns the entity unchanged (real Hibernate would stamp id/timestamps).
        org.mockito.Mockito.lenient()
            .when(auditLogRepository.save(org.mockito.ArgumentMatchers.any(AuditLog.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        // Slice 1.7c added the optional TenantHolidayRepository — pass null
        // here so weekends-only behaviour is preserved for the pre-1.7c
        // assertions; a dedicated holiday-aware test lives separately.
        service = new PeriodLockService(periodRepository, lockRepository, resolver,
            cache, auditService, events, null);

        period = new FiscalPeriod();
        period.setId(PERIOD_ID);
        period.setPeriodType(FiscalPeriodType.MONTH);
        period.setStartDate(LocalDate.of(2026, 5, 1));
        period.setEndDate(LocalDate.of(2026, 5, 31));
        period.setStatus(FiscalPeriodStatus.OPEN);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    // ─── checkWrite decision matrix ───────────────────────────────────────────

    @Nested
    @DisplayName("checkWrite — decision matrix")
    class CheckWrite {

        @Test
        @DisplayName("null entity → ALLOW")
        void nullEntity() {
            assertThat(service.checkWrite(null).outcome()).isEqualTo(LockOutcome.ALLOW);
        }

        @Test
        @DisplayName("null lockDate → ALLOW")
        void nullLockDate() {
            assertThat(service.checkWrite(new TestLockable(null, false)).outcome())
                .isEqualTo(LockOutcome.ALLOW);
        }

        @Test
        @DisplayName("reversal carve-out → ALLOW even when period would reject")
        void reversal() {
            // No repository stubbing — reversal short-circuits before lookup.
            assertThat(service.checkWrite(new TestLockable(LOCK_DATE, true)).outcome())
                .isEqualTo(LockOutcome.ALLOW);
        }

        @Test
        @DisplayName("no period for date → REJECT")
        void noPeriod() {
            stubPeriodLookup(Optional.empty());
            LockDecision d = service.checkWrite(new TestLockable(LOCK_DATE, false));
            assertThat(d.outcome()).isEqualTo(LockOutcome.REJECT);
            assertThat(d.reason()).contains("No fiscal period");
        }

        @Test
        @DisplayName("OPEN period, no active lock → ALLOW")
        void openPeriod() {
            stubPeriodLookup(Optional.of(period));
            stubActiveLock(Optional.empty());
            assertThat(service.checkWrite(new TestLockable(LOCK_DATE, false)).outcome())
                .isEqualTo(LockOutcome.ALLOW);
        }

        @Test
        @DisplayName("SOFT lock within grace → ALLOW")
        void softInGrace() {
            stubPeriodLookup(Optional.of(period));
            stubActiveLock(Optional.of(softLockWithGrace(Instant.now().plus(Duration.ofDays(2)))));
            assertThat(service.checkWrite(new TestLockable(LOCK_DATE, false)).outcome())
                .isEqualTo(LockOutcome.ALLOW);
        }

        @Test
        @DisplayName("SOFT lock past grace, no override → REJECT")
        void softPastGraceNoOverride() {
            authenticateWith(/* no roles */);
            stubPeriodLookup(Optional.of(period));
            stubActiveLock(Optional.of(softLockWithGrace(Instant.now().minus(Duration.ofDays(2)))));

            LockDecision d = service.checkWrite(new TestLockable(LOCK_DATE, false));

            assertThat(d.outcome()).isEqualTo(LockOutcome.REJECT);
            assertThat(d.overrideRoles()).contains("FINANCE_OVERRIDE_LOCK");
        }

        @Test
        @DisplayName("SOFT lock past grace, with override → OVERRIDE")
        void softPastGraceWithOverride() {
            authenticateWith(PeriodLockService.ROLE_OVERRIDE_LOCK);
            Instant graceEnded = Instant.now().minus(Duration.ofDays(2));
            stubPeriodLookup(Optional.of(period));
            stubActiveLock(Optional.of(softLockWithGrace(graceEnded)));

            LockDecision d = service.checkWrite(new TestLockable(LOCK_DATE, false));

            assertThat(d.outcome()).isEqualTo(LockOutcome.OVERRIDE);
            assertThat(d.graceEndsAt()).isEqualTo(graceEnded);
        }

        @Test
        @DisplayName("HARD lock → REJECT regardless of override role")
        void hardLockEvenWithOverride() {
            authenticateWith(PeriodLockService.ROLE_OVERRIDE_LOCK);
            stubPeriodLookup(Optional.of(period));
            stubActiveLock(Optional.of(hardLock()));

            LockDecision d = service.checkWrite(new TestLockable(LOCK_DATE, false));

            assertThat(d.outcome()).isEqualTo(LockOutcome.REJECT);
            assertThat(d.reason()).contains("HARD-closed");
            assertThat(d.overrideRoles()).isEmpty();
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Lifecycle — softClose / hardClose / reopen")
    class Lifecycle {

        @Test
        @DisplayName("softClose creates SOFT lock + flips status + audit row")
        void softCloseOpen() {
            when(periodRepository.findById(PERIOD_ID)).thenReturn(Optional.of(period));
            when(lockRepository.findFirstByFiscalPeriodIdAndReleasedAtIsNullAndDeletedAtIsNull(PERIOD_ID))
                .thenReturn(Optional.empty());
            when(lockRepository.save(any(PeriodLock.class))).thenAnswer(inv -> inv.getArgument(0));

            PeriodLock lock = service.softClose(PERIOD_ID, "month-end close");

            assertThat(lock.getLockType()).isEqualTo(LockType.SOFT);
            assertThat(lock.getGraceWindowUntil()).isAfter(Instant.now());
            assertThat(period.getStatus()).isEqualTo(FiscalPeriodStatus.SOFT_CLOSED);
            assertThat(period.getSoftClosedAt()).isNotNull();
            ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(auditCaptor.capture());
            assertThat(auditCaptor.getValue().getEntityType()).isEqualTo("FiscalPeriod");
            assertThat(auditCaptor.getValue().getAction()).isEqualTo(AuditAction.CLOSE);
        }

        @Test
        @DisplayName("softClose is idempotent when SOFT lock exists")
        void softCloseIdempotent() {
            PeriodLock existing = softLockWithGrace(Instant.now().plus(Duration.ofDays(5)));
            when(periodRepository.findById(PERIOD_ID)).thenReturn(Optional.of(period));
            when(lockRepository.findFirstByFiscalPeriodIdAndReleasedAtIsNullAndDeletedAtIsNull(PERIOD_ID))
                .thenReturn(Optional.of(existing));

            PeriodLock returned = service.softClose(PERIOD_ID, "retry");

            assertThat(returned).isSameAs(existing);
            verify(lockRepository, never()).save(any(PeriodLock.class));
            verify(auditLogRepository, never()).save(any(AuditLog.class));
        }

        @Test
        @DisplayName("softClose rejects when HARD lock exists")
        void softCloseAfterHardThrows() {
            when(periodRepository.findById(PERIOD_ID)).thenReturn(Optional.of(period));
            when(lockRepository.findFirstByFiscalPeriodIdAndReleasedAtIsNullAndDeletedAtIsNull(PERIOD_ID))
                .thenReturn(Optional.of(hardLock()));

            assertThatThrownBy(() -> service.softClose(PERIOD_ID, "?"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("HARD-closed");
        }

        @Test
        @DisplayName("hardClose on OPEN auto-soft-closes first (chronology invariant)")
        void hardCloseAutoSoft() {
            when(periodRepository.findById(PERIOD_ID)).thenReturn(Optional.of(period));
            when(lockRepository.findFirstByFiscalPeriodIdAndReleasedAtIsNullAndDeletedAtIsNull(PERIOD_ID))
                .thenReturn(Optional.empty());
            when(lockRepository.save(any(PeriodLock.class))).thenAnswer(inv -> inv.getArgument(0));

            PeriodLock hard = service.hardClose(PERIOD_ID, "year-end");

            assertThat(hard.getLockType()).isEqualTo(LockType.HARD);
            assertThat(hard.getGraceWindowUntil()).isNull();
            assertThat(period.getStatus()).isEqualTo(FiscalPeriodStatus.HARD_CLOSED);
            assertThat(period.getSoftClosedAt()).isNotNull();
            assertThat(period.getHardClosedAt()).isNotNull();
            verify(lockRepository, times(2)).save(any(PeriodLock.class));
        }

        @Test
        @DisplayName("hardClose from SOFT releases SOFT then creates HARD")
        void hardCloseFromSoft() {
            PeriodLock soft = softLockWithGrace(Instant.now().plus(Duration.ofDays(5)));
            when(periodRepository.findById(PERIOD_ID)).thenReturn(Optional.of(period));
            when(lockRepository.findFirstByFiscalPeriodIdAndReleasedAtIsNullAndDeletedAtIsNull(PERIOD_ID))
                .thenReturn(Optional.of(soft));
            when(lockRepository.save(any(PeriodLock.class))).thenAnswer(inv -> inv.getArgument(0));

            PeriodLock hard = service.hardClose(PERIOD_ID, "final");

            assertThat(hard.getLockType()).isEqualTo(LockType.HARD);
            assertThat(soft.getReleasedAt()).isNotNull();
            assertThat(soft.getReleaseReason()).contains("promoted to HARD");
        }

        @Test
        @DisplayName("reopen releases HARD, flips status, publishes event")
        void reopenHard() {
            PeriodLock hard = hardLock();
            period.setStatus(FiscalPeriodStatus.HARD_CLOSED);
            period.setHardClosedAt(Instant.now());
            when(periodRepository.findById(PERIOD_ID)).thenReturn(Optional.of(period));
            when(lockRepository.findFirstByFiscalPeriodIdAndReleasedAtIsNullAndDeletedAtIsNull(PERIOD_ID))
                .thenReturn(Optional.of(hard));
            when(lockRepository.save(any(PeriodLock.class))).thenAnswer(inv -> inv.getArgument(0));

            service.reopen(PERIOD_ID, "auditor adjustment");

            assertThat(hard.getReleasedAt()).isNotNull();
            assertThat(hard.getReleaseReason()).isEqualTo("auditor adjustment");
            assertThat(period.getStatus()).isEqualTo(FiscalPeriodStatus.REOPENED);
            assertThat(period.getHardClosedAt()).isNull();
            ArgumentCaptor<PeriodReopenedEvent> ev = ArgumentCaptor.forClass(PeriodReopenedEvent.class);
            verify(events).publishEvent(ev.capture());
            assertThat(ev.getValue().getReason()).isEqualTo("auditor adjustment");
            ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(auditCaptor.capture());
            assertThat(auditCaptor.getValue().getAction()).isEqualTo(AuditAction.REOPEN);
        }

        @Test
        @DisplayName("reopen rejects when no HARD lock is active")
        void reopenWithoutHardThrows() {
            when(periodRepository.findById(PERIOD_ID)).thenReturn(Optional.of(period));
            when(lockRepository.findFirstByFiscalPeriodIdAndReleasedAtIsNullAndDeletedAtIsNull(PERIOD_ID))
                .thenReturn(Optional.of(softLockWithGrace(Instant.now().plus(Duration.ofDays(5)))));

            assertThatThrownBy(() -> service.reopen(PERIOD_ID, "?"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("HARD");
        }
    }

    // ─── Business-day arithmetic ──────────────────────────────────────────────

    @Nested
    @DisplayName("addBusinessDays")
    class BusinessDays {

        @Test
        @DisplayName("Wed + 5 BD = following Wed (skips Sat/Sun)")
        void wednesdayPlus5() {
            Instant wed = Instant.parse("2026-05-13T00:00:00Z");
            Instant result = PeriodLockService.addBusinessDays(wed, 5);
            assertThat(result).isEqualTo(Instant.parse("2026-05-20T00:00:00Z"));
        }

        @Test
        @DisplayName("Fri + 5 BD = following Fri (skips Sat/Sun)")
        void fridayPlus5() {
            Instant fri = Instant.parse("2026-05-15T00:00:00Z");
            Instant result = PeriodLockService.addBusinessDays(fri, 5);
            assertThat(result).isEqualTo(Instant.parse("2026-05-22T00:00:00Z"));
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Stubs {@code FiscalPeriodRepository}'s date-range finder so the real
     * {@link FiscalPeriodResolver} returns either the canned period or
     * throws {@link FiscalPeriodNotFoundException}.
     */
    private void stubPeriodLookup(Optional<FiscalPeriod> result) {
        when(periodRepository.findFirstByPeriodTypeAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndDeletedAtIsNull(
            eq(FiscalPeriodType.MONTH), eq(LOCK_DATE), eq(LOCK_DATE))).thenReturn(result);
    }

    private void stubActiveLock(Optional<PeriodLock> result) {
        when(lockRepository.findFirstByFiscalPeriodIdAndReleasedAtIsNullAndDeletedAtIsNull(PERIOD_ID))
            .thenReturn(result);
    }

    private PeriodLock softLockWithGrace(Instant graceWindowUntil) {
        PeriodLock l = new PeriodLock();
        l.setFiscalPeriodId(PERIOD_ID);
        l.setLockType(LockType.SOFT);
        l.setLockedAt(Instant.now().minus(Duration.ofDays(5)));
        l.setLockedBy("test");
        l.setGraceWindowUntil(graceWindowUntil);
        return l;
    }

    private PeriodLock hardLock() {
        PeriodLock l = new PeriodLock();
        l.setFiscalPeriodId(PERIOD_ID);
        l.setLockType(LockType.HARD);
        l.setLockedAt(Instant.now());
        l.setLockedBy("test");
        return l;
    }

    private void authenticateWith(String... roles) {
        if (roles.length == 0) {
            SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("anon", "anon",
                    List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
            return;
        }
        List<SimpleGrantedAuthority> auths = java.util.Arrays.stream(roles)
            .map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("test-user", "pw", auths));
    }

    /** Minimal {@link LockableByPeriod} implementation for tests. */
    private record TestLockable(LocalDate lockDate, boolean reversal) implements LockableByPeriod {
        @Override public LocalDate getLockDate() { return lockDate; }
        @Override public boolean isReversal() { return reversal; }
    }
}
