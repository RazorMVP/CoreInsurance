package com.nubeero.cia.finance.backfill;

import com.nubeero.cia.common.event.ClaimApprovedEvent;
import com.nubeero.cia.common.event.ClaimExpenseApprovedEvent;
import com.nubeero.cia.common.event.ClaimSettledEvent;
import com.nubeero.cia.common.event.EndorsementApprovedEvent;
import com.nubeero.cia.common.event.FacPremiumCededEvent;
import com.nubeero.cia.common.event.PolicyApprovedEvent;
import com.nubeero.cia.finance.gl.FiscalPeriodStatus;
import com.nubeero.cia.finance.gl.JournalEntryDuplicateException;
import com.nubeero.cia.finance.gl.LockReportEntry;
import com.nubeero.cia.finance.gl.PeriodLockService;
import com.nubeero.cia.finance.gl.SubledgerPostingService;
import com.nubeero.cia.workflow.backfill.BackfillChunkRequest;
import com.nubeero.cia.workflow.backfill.BackfillChunkResult;
import com.nubeero.cia.workflow.backfill.BackfillEventType;
import com.nubeero.cia.workflow.backfill.BackfillPreflightResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RetroactiveJournalBackfillActivitiesImpl}.
 *
 * <h2>Why hand-rolled fakes</h2>
 * <p>Mockito's inline mock-maker cannot redefine concrete-class bytecode on
 * Java 25 when the class inherits from sealed bootstrap-module types — the
 * symptom is "Could not modify all classes [class java.lang.Object, class
 * com.nubeero.cia.finance.gl.SubledgerPostingService]". The existing
 * {@code SubledgerPostingServiceTest} sidesteps the issue by mocking only
 * Spring Data repository interfaces; this test follows the same convention
 * by hand-rolling subclasses of {@link SubledgerPostingService} and
 * {@link PeriodLockService} that override the methods the activity calls.
 *
 * <p>The {@link EntityManager} also defies Mockito (extends sealed
 * {@code AutoCloseable}); a JDK reflective {@link Proxy} intercepts the
 * single {@code createNativeQuery(String)} call the activity makes and
 * returns the still-mockable {@link Query} interface for chained stubbing.
 */
@ExtendWith(MockitoExtension.class)
class RetroactiveJournalBackfillActivitiesImplTest {

    private static final String TENANT = "tenant-acme";
    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);
    private static final LocalDate TO = LocalDate.of(2026, 4, 30);

    @Mock private Query query;

    private RecordingSubledgerPostingService subledger;
    private StubbingPeriodLockService periodLock;
    private RetroactiveJournalBackfillActivitiesImpl activities;

    @BeforeEach
    void wire() {
        subledger = new RecordingSubledgerPostingService();
        periodLock = new StubbingPeriodLockService();
        activities = new RetroactiveJournalBackfillActivitiesImpl(subledger, periodLock);
        ReflectionTestUtils.setField(activities, "em", fakeEntityManager(query));
    }

    // ── Preflight ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("previewPeriodLocks returns hasBlockingLocks=true when a period is HARD-closed in range")
    void previewPeriodLocks_BlockedWhenHardClosedInRange() {
        periodLock.previewLockResult = List.of(
                new LockReportEntry(LocalDate.of(2026, 4, 10), UUID.randomUUID(),
                        "Apr 2026", FiscalPeriodStatus.HARD_CLOSED, null, false, true),
                new LockReportEntry(LocalDate.of(2026, 4, 11), UUID.randomUUID(),
                        "Apr 2026", FiscalPeriodStatus.OPEN, null, false, false));

        BackfillPreflightResult result = activities.previewPeriodLocks(TENANT, FROM, TO);

        assertThat(result.hasBlockingLocks()).isTrue();
        assertThat(result.blockingPeriodLabels()).containsExactly("Apr 2026");
        assertThat(result.summary()).contains("Apr 2026");
    }

    @Test
    @DisplayName("previewPeriodLocks returns hasBlockingLocks=false when all days are writable")
    void previewPeriodLocks_AllowedWhenAllOpen() {
        periodLock.previewLockResult = List.of(
                new LockReportEntry(LocalDate.of(2026, 4, 11), UUID.randomUUID(),
                        "Apr 2026", FiscalPeriodStatus.OPEN, null, false, false));

        BackfillPreflightResult result = activities.previewPeriodLocks(TENANT, FROM, TO);

        assertThat(result.hasBlockingLocks()).isFalse();
        assertThat(result.blockingPeriodLabels()).isEmpty();
    }

    // ── processChunk : POLICY_APPROVED ───────────────────────────────────────

    @Test
    @DisplayName("processChunk calls replayPolicyApproved with reconstructed event when not dry-run")
    void processChunk_PolicyApproved_HappyPath() {
        stubPolicyApprovedQueryReturns(List.<Object[]>of(policyRow()));

        BackfillChunkResult result = activities.processChunk(
                chunkRequest(BackfillEventType.POLICY_APPROVED, false));

        assertThat(result.attempted()).isEqualTo(1);
        assertThat(result.posted()).isEqualTo(1);
        assertThat(result.alreadyExists()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.exhausted()).isTrue();
        assertThat(subledger.policyApprovedCalls).hasSize(1);

        PolicyApprovedEvent ev = subledger.policyApprovedCalls.get(0);
        assertThat(ev.policyNumber()).isEqualTo("POL-2026-0001");
        assertThat(ev.netPremium()).isEqualByComparingTo(new BigDecimal("125000.00"));
        assertThat(ev.policyStartDate()).isEqualTo(LocalDate.of(2026, 4, 12));
        // approvalDate is the booking date the GL business_date anchors to —
        // independent of (and here, earlier than) the coverage start date.
        assertThat(ev.approvalDate()).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(ev.currencyCode()).isEqualTo("NGN");
    }

    @Test
    @DisplayName("processChunk dry-run skips replay but counts the row as posted")
    void processChunk_PolicyApproved_DryRunSkipsReplay() {
        stubPolicyApprovedQueryReturns(List.<Object[]>of(policyRow()));

        BackfillChunkResult result = activities.processChunk(
                chunkRequest(BackfillEventType.POLICY_APPROVED, true));

        assertThat(result.posted()).isEqualTo(1);
        assertThat(subledger.policyApprovedCalls).isEmpty();
    }

    @Test
    @DisplayName("processChunk counts JournalEntryDuplicateException as alreadyExists, not failed")
    void processChunk_PolicyApproved_DuplicateCountsAsAlreadyExists() {
        stubPolicyApprovedQueryReturns(List.<Object[]>of(policyRow()));
        subledger.policyApprovedException = new JournalEntryDuplicateException(
                "policy", "POLICY_APPROVED", "abc");

        BackfillChunkResult result = activities.processChunk(
                chunkRequest(BackfillEventType.POLICY_APPROVED, false));

        assertThat(result.attempted()).isEqualTo(1);
        assertThat(result.alreadyExists()).isEqualTo(1);
        assertThat(result.posted()).isZero();
        assertThat(result.failed()).isZero();
    }

    @Test
    @DisplayName("processChunk counts an unexpected RuntimeException as failed and continues to the next row")
    void processChunk_PolicyApproved_UnexpectedFailureCountsAsFailed() {
        stubPolicyApprovedQueryReturns(List.<Object[]>of(policyRow(), policyRow()));
        // First call throws, second succeeds (continuation behaviour).
        subledger.failFirstNCalls = 1;
        subledger.policyApprovedException = new IllegalStateException("decommissioned COA");

        BackfillChunkResult result = activities.processChunk(
                chunkRequest(BackfillEventType.POLICY_APPROVED, false));

        assertThat(result.attempted()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.posted()).isEqualTo(1);
    }

    @Test
    @DisplayName("processChunk with zero rows reports exhausted=true and zero counts")
    void processChunk_PolicyApproved_EmptyExhausts() {
        stubPolicyApprovedQueryReturns(List.<Object[]>of());

        BackfillChunkResult result = activities.processChunk(
                chunkRequest(BackfillEventType.POLICY_APPROVED, false));

        assertThat(result.attempted()).isZero();
        assertThat(result.exhausted()).isTrue();
        assertThat(subledger.policyApprovedCalls).isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("rawtypes")
    private void stubPolicyApprovedQueryReturns(List<Object[]> rows) {
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setParameter(anyString(), anyInt())).thenReturn(query);
        when(query.getResultList()).thenReturn((List) rows);
    }

    private static EntityManager fakeEntityManager(Query query) {
        return (EntityManager) Proxy.newProxyInstance(
                EntityManager.class.getClassLoader(),
                new Class<?>[] { EntityManager.class },
                (proxy, method, args) -> {
                    if ("createNativeQuery".equals(method.getName())
                            && args != null && args.length == 1) {
                        return query;
                    }
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("toString".equals(method.getName())) return "fakeEntityManager";
                    throw new UnsupportedOperationException("Not stubbed: " + method.getName());
                });
    }

    private static Object[] policyRow() {
        return new Object[] {
                UUID.randomUUID(),                                          // 0  id
                "POL-2026-0001",                                            // 1  policy_number
                UUID.randomUUID(),                                          // 2  customer_id
                "Acme Industries Ltd",                                      // 3  customer_name
                UUID.randomUUID(),                                          // 4  broker_id
                "Best Brokers Ltd",                                         // 5  broker_name
                UUID.randomUUID(),                                          // 6  product_id
                "Motor Comprehensive",                                      // 7  product_name
                new BigDecimal("125000.00"),                                // 8  net_premium
                "NGN",                                                      // 9  currency_code
                Date.valueOf(LocalDate.of(2026, 4, 12)),                    // 10 policy_start_date
                Date.valueOf(LocalDate.of(2027, 4, 11)),                    // 11 policy_end_date
                new BigDecimal("5000000.00"),                               // 12 total_sum_insured
                UUID.randomUUID(),                                          // 13 class_of_business_id
                // 14-17 added by slices 84c/84d (commission source + agent
                // attribution). The impl SELECTs 19 columns and reads
                // row[14..18]; this double had lagged at 14 elements, causing
                // Index-14-out-of-bounds. Null = a pre-attribution policy that
                // skips the commission/agent chain (matches the impl comment).
                null,                                                       // 14 commission_source_type
                null,                                                       // 15 commission_rate
                null,                                                       // 16 agent_id
                null,                                                       // 17 agent_name
                // 18 approved_at — the booking date the GL business_date now
                // anchors to (je-business-date fix). Deliberately distinct from
                // policy_start_date (row[10] = 2026-04-12) to prove the two are
                // wired independently: this policy was booked 2026-04-05 for
                // coverage starting a week later (the future-effective shape).
                java.time.LocalDate.of(2026, 4, 5)
                        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant()  // 18 approved_at
        };
    }

    private static BackfillChunkRequest chunkRequest(BackfillEventType type, boolean dryRun) {
        return new BackfillChunkRequest(
                TENANT, "admin@example.com", type, FROM, TO, 0, 100, dryRun);
    }

    // ── Hand-rolled test doubles ──────────────────────────────────────────────

    /**
     * Hand-rolled subclass of {@link SubledgerPostingService} that captures
     * replay invocations and can be told to throw on the first N calls. Pass
     * nulls for all collaborators — none of the overridden methods touch
     * them.
     */
    private static class RecordingSubledgerPostingService extends SubledgerPostingService {
        final List<PolicyApprovedEvent> policyApprovedCalls = new ArrayList<>();
        RuntimeException policyApprovedException;
        int failFirstNCalls = Integer.MAX_VALUE;   // throw on every call by default if exception set

        RecordingSubledgerPostingService() {
            // 4 nulls: journalEntryService, postingRuleService, policyClassResolver, clock.
            // Slice 1.10a added policyClassResolver — recording test overrides
            // replayPolicyApproved so the resolver is never invoked.
            super(null, null, null, null);
        }

        @Override
        public void replayPolicyApproved(PolicyApprovedEvent event) {
            if (policyApprovedException != null && policyApprovedCalls.size() < failFirstNCalls) {
                policyApprovedCalls.add(event);   // count attempt before throwing
                throw policyApprovedException;
            }
            policyApprovedCalls.add(event);
        }

        // Defensive overrides — unused in current tests but keep activity safe
        // if a future test exercises other event types via this double.
        @Override public void replayClaimApproved(ClaimApprovedEvent event) { }
        @Override public void replayClaimApproved(ClaimApprovedEvent event, LocalDate d) { }
        @Override public void replayClaimSettled(ClaimSettledEvent event) { }
        @Override public void replayClaimExpenseApproved(ClaimExpenseApprovedEvent event) { }
        @Override public void replayClaimExpenseApproved(ClaimExpenseApprovedEvent event, LocalDate d) { }
        @Override public void replayEndorsementApproved(EndorsementApprovedEvent event) { }
        @Override public void replayEndorsementApproved(EndorsementApprovedEvent event, LocalDate d) { }
        @Override public void replayFacPremiumCeded(FacPremiumCededEvent event) { }
        @Override public void replayFacPremiumCeded(FacPremiumCededEvent event, LocalDate d) { }
    }

    /**
     * Hand-rolled subclass of {@link PeriodLockService} exposing a tunable
     * {@link #previewLockResult} for the preflight tests.
     */
    private static class StubbingPeriodLockService extends PeriodLockService {
        List<LockReportEntry> previewLockResult = List.of();

        StubbingPeriodLockService() {
            // Slice 1.7c added the 7th constructor arg (TenantHolidayRepository).
            super(null, null, null, null, null, null, null);
        }

        @Override
        public List<LockReportEntry> previewLock(LocalDate fromDate, LocalDate toDate) {
            return previewLockResult;
        }
    }
}
