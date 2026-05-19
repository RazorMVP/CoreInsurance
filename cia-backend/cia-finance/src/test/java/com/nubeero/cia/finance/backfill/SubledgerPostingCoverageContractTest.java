package com.nubeero.cia.finance.backfill;

import com.nubeero.cia.common.event.ClaimApprovedEvent;
import com.nubeero.cia.common.event.ClaimExpenseApprovedEvent;
import com.nubeero.cia.common.event.ClaimSettledEvent;
import com.nubeero.cia.common.event.EndorsementApprovedEvent;
import com.nubeero.cia.common.event.FacPremiumCededEvent;
import com.nubeero.cia.common.event.PolicyApprovedEvent;
import com.nubeero.cia.finance.gl.SubledgerPostingService;
import com.nubeero.cia.workflow.backfill.BackfillEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Contract test for the Slice 1.9 reconciliation gate: confirms that every
 * value in {@link BackfillEventType} is reachable through both the live
 * event-listener path and the backfill replay path on
 * {@link SubledgerPostingService}.
 *
 * <h2>Why a reflection-based meta-test</h2>
 * <p>The reconciliation gate's main IT (
 * {@code cia-api/.../ReconciliationGateIT}) exercises this contract
 * implicitly by firing all six events and asserting JEs appear. But the
 * IT spins up Postgres via Testcontainers, takes ~30 s, and only runs in
 * Maven's integration phase. This test runs in milliseconds on every
 * {@code mvn test} and fails immediately when a future engineer adds a new
 * {@code BackfillEventType} value (e.g. {@code INVESTMENT_INCOME} for
 * Phase 3) without wiring the corresponding replay method. Cheap, fast,
 * always-on.
 *
 * <h2>Coverage map</h2>
 * <p>Each enum value maps to one Spring event class. The contract:
 * <ol>
 *   <li>A {@code replay&lt;PascalCase&gt;(EventClass)} method must exist on
 *       {@link SubledgerPostingService} for every enum value. The
 *       no-historical-date overload is the live-flow entry point.</li>
 *   <li>For event records that lack an intrinsic business-date field
 *       (Claim, ClaimExpense, Endorsement, Fac), a
 *       {@code replay&lt;...&gt;(EventClass, LocalDate)} overload must also
 *       exist so the backfill activity can supply the historical date.
 *       {@link PolicyApprovedEvent} carries {@code policyStartDate} and
 *       {@link ClaimSettledEvent} carries {@code settledAt}, so those are
 *       exempt.</li>
 *   <li>An {@link EventListener}-annotated method consuming the same event
 *       class must exist so the live application-event flow still produces
 *       JEs.</li>
 * </ol>
 *
 * <p>If a future enum value isn't yet wired, the test prints a precise
 * remediation hint naming the missing method signature.
 */
class SubledgerPostingCoverageContractTest {

    /**
     * Canonical mapping from {@link BackfillEventType} to the Spring
     * application-event class that {@link SubledgerPostingService} consumes.
     * Co-located here (rather than on the enum or on the service) so the
     * mapping itself is the test fixture — adding a new enum value forces a
     * code change here and surfaces the missing replay wiring.
     */
    private static final Map<BackfillEventType, Class<?>> EVENT_CLASS = new EnumMap<>(BackfillEventType.class);

    /** Backfill enum values whose event record lacks a business-date field. */
    private static final Set<BackfillEventType> REQUIRES_DATE_OVERLOAD = EnumSet.of(
            BackfillEventType.CLAIM_APPROVED,
            BackfillEventType.CLAIM_EXPENSE_APPROVED,
            BackfillEventType.ENDORSEMENT_APPROVED,
            BackfillEventType.FAC_PREMIUM_CEDED);

    static {
        EVENT_CLASS.put(BackfillEventType.POLICY_APPROVED, PolicyApprovedEvent.class);
        EVENT_CLASS.put(BackfillEventType.CLAIM_APPROVED, ClaimApprovedEvent.class);
        EVENT_CLASS.put(BackfillEventType.CLAIM_SETTLED, ClaimSettledEvent.class);
        EVENT_CLASS.put(BackfillEventType.CLAIM_EXPENSE_APPROVED, ClaimExpenseApprovedEvent.class);
        EVENT_CLASS.put(BackfillEventType.ENDORSEMENT_APPROVED, EndorsementApprovedEvent.class);
        EVENT_CLASS.put(BackfillEventType.FAC_PREMIUM_CEDED, FacPremiumCededEvent.class);
    }

    @Test
    @DisplayName("EVENT_CLASS table covers every BackfillEventType enum value")
    void mappingTableMatchesEnum() {
        Set<BackfillEventType> covered = EVENT_CLASS.keySet();
        Set<BackfillEventType> missing = EnumSet.allOf(BackfillEventType.class);
        missing.removeAll(covered);
        if (!missing.isEmpty()) {
            fail(
                "BackfillEventType has values without a mapping in SubledgerPostingCoverageContractTest.EVENT_CLASS: "
                    + missing + ". Add the (enum → event class) entry then add the corresponding "
                    + "replay method on SubledgerPostingService."
            );
        }
    }

    @Test
    @DisplayName("Every BackfillEventType has a replay(Event) method on SubledgerPostingService")
    void everyEventTypeHasReplayMethod() {
        for (Map.Entry<BackfillEventType, Class<?>> entry : EVENT_CLASS.entrySet()) {
            BackfillEventType type = entry.getKey();
            Class<?> eventClass = entry.getValue();
            String expectedMethodName = "replay" + pascalCase(type);
            Method method = findMethod(expectedMethodName, eventClass);
            assertThat(method)
                .as("SubledgerPostingService is missing %s(%s) — required so the live event listener and the "
                    + "backfill workflow share a single posting code path",
                    expectedMethodName, eventClass.getSimpleName())
                .isNotNull();
        }
    }

    @Test
    @DisplayName("Events without an intrinsic business-date field have a (Event, LocalDate) backfill overload")
    void dateOverloadExistsForDatelessEvents() {
        for (BackfillEventType type : REQUIRES_DATE_OVERLOAD) {
            Class<?> eventClass = EVENT_CLASS.get(type);
            String expectedMethodName = "replay" + pascalCase(type);
            Method method = findMethod(expectedMethodName, eventClass, LocalDate.class);
            assertThat(method)
                .as("SubledgerPostingService is missing %s(%s, LocalDate) overload — required because %s "
                    + "lacks an intrinsic business-date field, so the backfill activity must supply the "
                    + "historical date explicitly",
                    expectedMethodName, eventClass.getSimpleName(), eventClass.getSimpleName())
                .isNotNull();
        }
    }

    @Test
    @DisplayName("Every event class has a matching @EventListener method (live flow stays wired)")
    void liveEventListenerExistsForEveryEventClass() {
        Set<Class<?>> listenerEventClasses = Arrays.stream(SubledgerPostingService.class.getMethods())
            .filter(m -> m.isAnnotationPresent(EventListener.class))
            .filter(m -> m.getParameterCount() == 1)
            .map(m -> m.getParameterTypes()[0])
            .collect(Collectors.toSet());

        for (Map.Entry<BackfillEventType, Class<?>> entry : EVENT_CLASS.entrySet()) {
            Class<?> eventClass = entry.getValue();
            assertThat(listenerEventClasses)
                .as("SubledgerPostingService has no @EventListener consuming %s. Live business events of type "
                    + "%s would no longer produce a journal entry. Add an @EventListener method that "
                    + "delegates to replay%s.",
                    eventClass.getSimpleName(), entry.getKey(), pascalCase(entry.getKey()))
                .contains(eventClass);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String pascalCase(BackfillEventType type) {
        // POLICY_APPROVED → PolicyApproved
        String[] parts = type.name().split("_");
        StringBuilder out = new StringBuilder(type.name().length());
        for (String part : parts) {
            out.append(Character.toUpperCase(part.charAt(0)))
               .append(part.substring(1).toLowerCase());
        }
        return out.toString();
    }

    private static Method findMethod(String name, Class<?>... paramTypes) {
        try {
            return SubledgerPostingService.class.getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
