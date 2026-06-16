# Money-Math Test Coverage (Slice 1: RI Allocation + Pro-Rata) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add focused unit-test coverage for the two highest-risk, zero-coverage, pure-arithmetic money engines — reinsurance cession (`AllocationService`: surplus / quota-share / XOL / line distribution + reinsurer commission) and endorsement pro-rata premium (`EndorsementService.calculatePremiumAdjustment`).

**Architecture:** Plain JUnit 5 + Mockito + AssertJ unit tests, no Spring context, no Testcontainers, no production-code changes. `AllocationService.allocate(...)` is public and returns the fully-computed `RiAllocation` (amounts + lines) — mocking its three repository/service collaborators exercises the real cession math in-memory. `calculatePremiumAdjustment(...)` is a public pure method. Both test classes live in the **same package** as their target so no visibility change is needed.

**Tech Stack:** Java 21, JUnit 5 (Jupiter), Mockito (`mock`, `when`, `thenAnswer`), AssertJ (`assertThat(...).isEqualByComparingTo(...)` for scale-insensitive `BigDecimal` comparison). Both `cia-reinsurance` and `cia-endorsement` already have these on the test classpath (existing `*LockableByPeriodTest` / `*EventContractTest`).

---

## Test policy (applies to every task)

1. **Assert the documented-correct behaviour, not whatever the code currently emits.** Compute every expected value by hand from the business rule (shown inline per case). If a test fails because the production code disagrees, **do NOT change the test to match the code and do NOT fix the production code in this slice.** Instead: keep the test asserting the correct value, mark it `@Disabled("BUG: <one line> — see backlog money-math-<area>-bug")`, and report the discrepancy in your status so a backlog row can be added. Surfacing a money bug is a *success* of this slice; fixing it is a separate slice.
2. **`BigDecimal` comparisons use `isEqualByComparingTo`** (AssertJ) — never `isEqualTo`/`equals`, which are scale-sensitive (`100000.00` ≠ `100000`).
3. **No production-code edits.** If a test seems to need one, stop and report — don't widen visibility or refactor.

---

## File Structure

| File | Responsibility |
|---|---|
| `cia-backend/cia-reinsurance/src/test/java/com/nubeero/cia/reinsurance/AllocationServiceTest.java` (create) | Unit tests for surplus/quota-share/XOL cession math + line distribution + reinsurer commission + the not-active guard. |
| `cia-backend/cia-endorsement/src/test/java/com/nubeero/cia/endorsement/EndorsementProRataTest.java` (create) | Unit tests for the `(new−old)×days/365` pro-rata formula (increase/decrease/zero/null/rounding). |

Reference (read-only, do not modify): `cia-reinsurance/.../AllocationService.java` (the target), `RiTreaty.java` / `RiTreatyParticipant.java` / `RiAllocation.java` / `RiAllocationLine.java` (Lombok `@Builder` entities), `cia-endorsement/.../EndorsementService.java:251` (`calculatePremiumAdjustment`).

---

### Task 1: `AllocationServiceTest` — reinsurance cession math (CRITICAL — currently zero coverage)

**Files:**
- Create: `cia-backend/cia-reinsurance/src/test/java/com/nubeero/cia/reinsurance/AllocationServiceTest.java`
- Reference: `cia-backend/cia-reinsurance/src/main/java/com/nubeero/cia/reinsurance/AllocationService.java:55-224`

**Context for the implementer.** `AllocationService` (`@RequiredArgsConstructor`) takes `(RiAllocationRepository allocationRepository, RiTreatyRepository treatyRepository, RiNumberService numberService)`. The public `allocate(UUID policyId, String policyNumber, UUID treatyId, BigDecimal sumInsured, BigDecimal premium, String currencyCode, UUID endorsementId)`:
1. loads the treaty via `treatyRepository.findByIdAndDeletedAtIsNull(treatyId)` (throw `ResourceNotFoundException` if empty),
2. throws `BusinessRuleException("TREATY_NOT_ACTIVE", ...)` unless `treaty.getStatus() == TreatyStatus.ACTIVE`,
3. builds an `RiAllocation`, dispatches on `treaty.getTreatyType()` to `applySurplus`/`applyQuotaShare`/`applyXol` (private), and `return allocationRepository.save(allocation)`.

So: mock the 3 collaborators, build a treaty, call `allocate(...)`, assert the returned `RiAllocation`. Stub `allocationRepository.save(any())` to **return its argument** so the computed allocation flows back.

Entity builders (Lombok `@Builder`, BaseEntity gives `deletedAt` defaulting to null):
- `RiTreaty.builder().treatyType(TreatyType.SURPLUS).status(TreatyStatus.ACTIVE).retentionLimit(new BigDecimal("10000000")).surplusCapacity(new BigDecimal("40000000")).participants(List.of(...)).build()`
- `RiTreatyParticipant.builder().reinsuranceCompanyId(UUID.randomUUID()).reinsuranceCompanyName("Re A").sharePercentage(new BigDecimal("60")).commissionRate(new BigDecimal("10")).build()`
- Getters on the result: `getRetainedAmount()`, `getCededAmount()`, `getExcessAmount()`, `getRetainedPremium()`, `getCededPremium()`, `getLines()` (→ `RiAllocationLine` with `getCededAmount()`, `getCededPremium()`, `getCommissionAmount()`, `getSharePercentage()`, `getReinsuranceCompanyName()`).

The arithmetic under test (copied from the source so expected values are derivable):
- **Surplus** (`applySurplus`): `retainedSI = min(SI, retentionLimit)`; `toAllocate = SI − retainedSI`; `cededSI = min(toAllocate, surplusCapacity)`; `excessSI = toAllocate − cededSI`; `retainedPrem = premium × retainedSI / SI` (HALF_UP 2dp, or 0 if SI=0); `cededPrem = premium − retainedPrem`. Lines built only if `cededSI > 0`.
- **Quota-share** (`applyQuotaShare`): `totalCededPct = Σ participant.sharePercentage`; `cededSI = SI × totalCededPct / 100` (HALF_UP 2dp); `retainedSI = SI − cededSI`; `cededPrem = premium × totalCededPct / 100`; `retainedPrem = premium − cededPrem`; `excess = 0`. Always distributes lines.
- **XOL** (`applyXol`): retain 100% — `retained = SI`, `ceded = 0`, `excess = 0`, `retainedPrem = premium`, `cededPrem = 0`, no lines.
- **Line distribution** (`distributeToLines`, used by surplus + QS): `totalPct = Σ share`; per participant `pctOfCeded = share / totalPct` (10dp HALF_UP); `lineSI = cededSI × pctOfCeded` (2dp HALF_UP); `linePrem = cededPrem × pctOfCeded` (2dp); `commission = linePrem × commissionRate / 100` (2dp).

- [ ] **Step 1: Write the test class with all cases**

```java
package com.nubeero.cia.reinsurance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nubeero.cia.common.exception.BusinessRuleException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the reinsurance cession math in {@link AllocationService}. Pure Mockito — the
 * public {@code allocate(...)} returns the fully-computed {@link RiAllocation} (amounts + lines),
 * so mocking the three collaborators exercises surplus / quota-share / XOL / line-distribution
 * arithmetic with no Spring context or database.
 *
 * <p>Expected values are hand-computed from the documented cession rules (see each test). Per the
 * slice's test policy, these assert the CORRECT business outcome; a failure signals a production
 * bug to surface, not a test to relax.
 */
class AllocationServiceTest {

    private RiAllocationRepository allocationRepository;
    private RiTreatyRepository treatyRepository;
    private RiNumberService numberService;
    private AllocationService service;

    private final UUID treatyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        allocationRepository = mock(RiAllocationRepository.class);
        treatyRepository = mock(RiTreatyRepository.class);
        numberService = mock(RiNumberService.class);
        when(numberService.nextAllocationNumber()).thenReturn("RI-0001");
        // save() echoes its argument so the computed allocation flows back to the caller.
        when(allocationRepository.save(any(RiAllocation.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new AllocationService(allocationRepository, treatyRepository, numberService);
    }

    private RiTreatyParticipant participant(String sharePct, String commissionPct, String name) {
        return RiTreatyParticipant.builder()
                .reinsuranceCompanyId(UUID.randomUUID())
                .reinsuranceCompanyName(name)
                .sharePercentage(new BigDecimal(sharePct))
                .commissionRate(new BigDecimal(commissionPct))
                .build();
    }

    private RiAllocation allocateWith(RiTreaty treaty, String si, String premium) {
        when(treatyRepository.findByIdAndDeletedAtIsNull(treatyId)).thenReturn(Optional.of(treaty));
        return service.allocate(UUID.randomUUID(), "POL-1", treatyId,
                new BigDecimal(si), new BigDecimal(premium), "NGN", null);
    }

    // ─── SURPLUS ──────────────────────────────────────────────────────────

    @Test
    void surplus_retainsUpToLimit_cedesRemainderWithinCapacity_premiumProRata() {
        // retention 10M, capacity 40M, SI 30M, premium 300k.
        // retained=10M; toAllocate=20M; ceded=min(20M,40M)=20M; excess=0.
        // retainedPrem = 300000 × 10M/30M = 100000.00; cededPrem = 200000.00.
        RiTreaty treaty = RiTreaty.builder()
                .treatyType(TreatyType.SURPLUS).status(TreatyStatus.ACTIVE)
                .retentionLimit(new BigDecimal("10000000"))
                .surplusCapacity(new BigDecimal("40000000"))
                .participants(List.of(participant("60", "10", "Re A"),
                                      participant("40", "5", "Re B")))
                .build();

        RiAllocation a = allocateWith(treaty, "30000000", "300000");

        assertThat(a.getRetainedAmount()).isEqualByComparingTo("10000000");
        assertThat(a.getCededAmount()).isEqualByComparingTo("20000000");
        assertThat(a.getExcessAmount()).isEqualByComparingTo("0");
        assertThat(a.getRetainedPremium()).isEqualByComparingTo("100000.00");
        assertThat(a.getCededPremium()).isEqualByComparingTo("200000.00");

        // Lines split the ceded 20M / 200k by 60:40 of total pct (100).
        assertThat(a.getLines()).hasSize(2);
        RiAllocationLine reA = a.getLines().stream()
                .filter(l -> l.getReinsuranceCompanyName().equals("Re A")).findFirst().orElseThrow();
        RiAllocationLine reB = a.getLines().stream()
                .filter(l -> l.getReinsuranceCompanyName().equals("Re B")).findFirst().orElseThrow();
        assertThat(reA.getCededAmount()).isEqualByComparingTo("12000000.00");   // 20M × 0.6
        assertThat(reA.getCededPremium()).isEqualByComparingTo("120000.00");    // 200k × 0.6
        assertThat(reA.getCommissionAmount()).isEqualByComparingTo("12000.00"); // 120k × 10%
        assertThat(reB.getCededAmount()).isEqualByComparingTo("8000000.00");    // 20M × 0.4
        assertThat(reB.getCededPremium()).isEqualByComparingTo("80000.00");     // 200k × 0.4
        assertThat(reB.getCommissionAmount()).isEqualByComparingTo("4000.00");  // 80k × 5%

        // Line cessions reconcile to the allocation's ceded totals (no rounding leak here).
        BigDecimal lineSiSum = a.getLines().stream().map(RiAllocationLine::getCededAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal linePremSum = a.getLines().stream().map(RiAllocationLine::getCededPremium)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(lineSiSum).isEqualByComparingTo(a.getCededAmount());
        assertThat(linePremSum).isEqualByComparingTo(a.getCededPremium());
    }

    @Test
    void surplus_tagsExcessBeyondRetentionPlusCapacity() {
        // retention 10M, capacity 15M, SI 40M, premium 400k.
        // retained=10M; toAllocate=30M; ceded=min(30M,15M)=15M; excess=15M.
        // retainedPrem = 400000 × 10M/40M = 100000.00; cededPrem = 300000.00.
        RiTreaty treaty = RiTreaty.builder()
                .treatyType(TreatyType.SURPLUS).status(TreatyStatus.ACTIVE)
                .retentionLimit(new BigDecimal("10000000"))
                .surplusCapacity(new BigDecimal("15000000"))
                .participants(List.of(participant("100", "0", "Re A")))
                .build();

        RiAllocation a = allocateWith(treaty, "40000000", "400000");

        assertThat(a.getRetainedAmount()).isEqualByComparingTo("10000000");
        assertThat(a.getCededAmount()).isEqualByComparingTo("15000000");
        assertThat(a.getExcessAmount()).isEqualByComparingTo("15000000");
        assertThat(a.getRetainedPremium()).isEqualByComparingTo("100000.00");
        assertThat(a.getCededPremium()).isEqualByComparingTo("300000.00");
    }

    @Test
    void surplus_siWithinRetention_cedesNothing_noLines() {
        // retention 10M, SI 8M, premium 80k. retained=8M; ceded=0; excess=0; retainedPrem=80000.00.
        RiTreaty treaty = RiTreaty.builder()
                .treatyType(TreatyType.SURPLUS).status(TreatyStatus.ACTIVE)
                .retentionLimit(new BigDecimal("10000000"))
                .surplusCapacity(new BigDecimal("40000000"))
                .participants(List.of(participant("100", "10", "Re A")))
                .build();

        RiAllocation a = allocateWith(treaty, "8000000", "80000");

        assertThat(a.getRetainedAmount()).isEqualByComparingTo("8000000");
        assertThat(a.getCededAmount()).isEqualByComparingTo("0");
        assertThat(a.getExcessAmount()).isEqualByComparingTo("0");
        assertThat(a.getRetainedPremium()).isEqualByComparingTo("80000.00");
        assertThat(a.getCededPremium()).isEqualByComparingTo("0");
        assertThat(a.getLines()).isEmpty();
    }

    // ─── QUOTA SHARE ──────────────────────────────────────────────────────

    @Test
    void quotaShare_cedesByTotalParticipantPercentage_andSplitsLines() {
        // participants 40% (comm 10%) + 20% (comm 5%) ⇒ totalCededPct 60.
        // SI 10M, premium 100k. cededSI=6M; retainedSI=4M; cededPrem=60k; retainedPrem=40k; excess=0.
        // lines split ceded by 40:20 of totalPct 60 ⇒ 2/3 and 1/3.
        RiTreaty treaty = RiTreaty.builder()
                .treatyType(TreatyType.QUOTA_SHARE).status(TreatyStatus.ACTIVE)
                .participants(List.of(participant("40", "10", "Re A"),
                                      participant("20", "5", "Re B")))
                .build();

        RiAllocation a = allocateWith(treaty, "10000000", "100000");

        assertThat(a.getRetainedAmount()).isEqualByComparingTo("4000000.00");
        assertThat(a.getCededAmount()).isEqualByComparingTo("6000000.00");
        assertThat(a.getExcessAmount()).isEqualByComparingTo("0");
        assertThat(a.getRetainedPremium()).isEqualByComparingTo("40000.00");
        assertThat(a.getCededPremium()).isEqualByComparingTo("60000.00");

        RiAllocationLine reA = a.getLines().stream()
                .filter(l -> l.getReinsuranceCompanyName().equals("Re A")).findFirst().orElseThrow();
        RiAllocationLine reB = a.getLines().stream()
                .filter(l -> l.getReinsuranceCompanyName().equals("Re B")).findFirst().orElseThrow();
        // Re A: 40/60 = 0.6666666667 ⇒ 6M×=4,000,000.00 ; 60k×=40,000.00 ; comm 10% = 4,000.00
        assertThat(reA.getCededAmount()).isEqualByComparingTo("4000000.00");
        assertThat(reA.getCededPremium()).isEqualByComparingTo("40000.00");
        assertThat(reA.getCommissionAmount()).isEqualByComparingTo("4000.00");
        // Re B: 20/60 = 0.3333333333 ⇒ 6M×=2,000,000.00 ; 60k×=20,000.00 ; comm 5% = 1,000.00
        assertThat(reB.getCededAmount()).isEqualByComparingTo("2000000.00");
        assertThat(reB.getCededPremium()).isEqualByComparingTo("20000.00");
        assertThat(reB.getCommissionAmount()).isEqualByComparingTo("1000.00");
    }

    // ─── XOL ──────────────────────────────────────────────────────────────

    @Test
    void xol_retainsEverything_cedesNothing_noLines() {
        // XOL is blanket loss cover — no per-policy premium cession.
        RiTreaty treaty = RiTreaty.builder()
                .treatyType(TreatyType.XOL).status(TreatyStatus.ACTIVE)
                .participants(List.of(participant("100", "10", "Re A")))
                .build();

        RiAllocation a = allocateWith(treaty, "10000000", "100000");

        assertThat(a.getRetainedAmount()).isEqualByComparingTo("10000000");
        assertThat(a.getCededAmount()).isEqualByComparingTo("0");
        assertThat(a.getExcessAmount()).isEqualByComparingTo("0");
        assertThat(a.getRetainedPremium()).isEqualByComparingTo("100000");
        assertThat(a.getCededPremium()).isEqualByComparingTo("0");
        assertThat(a.getLines()).isEmpty();
    }

    // ─── GUARD ────────────────────────────────────────────────────────────

    @Test
    void allocate_rejectsNonActiveTreaty() {
        RiTreaty draft = RiTreaty.builder()
                .treatyType(TreatyType.SURPLUS).status(TreatyStatus.DRAFT)
                .retentionLimit(new BigDecimal("10000000"))
                .surplusCapacity(new BigDecimal("40000000"))
                .build();
        when(treatyRepository.findByIdAndDeletedAtIsNull(treatyId)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.allocate(UUID.randomUUID(), "POL-1", treatyId,
                new BigDecimal("30000000"), new BigDecimal("300000"), "NGN", null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ACTIVE");
    }
}
```

- [ ] **Step 2: Run the test, expect green (or a surfaced bug)**

Run: `cd cia-backend && mvn -q -pl cia-reinsurance test -Dtest=AllocationServiceTest`
Expected: **BUILD SUCCESS, 6 tests pass.** If any case fails, FIRST re-derive the expected value by hand from the rule in the comment; if your arithmetic is right and the code is wrong, that's a production bug — mark only that test `@Disabled("BUG: ...")`, keep the correct expected value, and note it in your status (do NOT edit `AllocationService.java`). If a builder field name or repository method name differs from this plan, read the real entity/repository and adjust the test (not the asserted numbers).

- [ ] **Step 3: Commit**

```bash
git add cia-backend/cia-reinsurance/src/test/java/com/nubeero/cia/reinsurance/AllocationServiceTest.java
git commit -m "test(reinsurance): unit-cover AllocationService cession math (surplus/QS/XOL/lines)"
```

---

### Task 2: `EndorsementProRataTest` — pro-rata endorsement premium (currently zero coverage)

**Files:**
- Create: `cia-backend/cia-endorsement/src/test/java/com/nubeero/cia/endorsement/EndorsementProRataTest.java`
- Reference: `cia-backend/cia-endorsement/src/main/java/com/nubeero/cia/endorsement/EndorsementService.java:251-260`

**Context for the implementer.** The target is the public pure method:
```java
public BigDecimal calculatePremiumAdjustment(BigDecimal oldPremium, BigDecimal newPremium, long remainingDays) {
    if (newPremium == null || newPremium.compareTo(oldPremium) == 0) return BigDecimal.ZERO;
    BigDecimal diff = newPremium.subtract(oldPremium);
    return diff.multiply(BigDecimal.valueOf(remainingDays)).divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
}
```
It only uses its three arguments — its enclosing `EndorsementService` collaborators are never touched on this path. `EndorsementService` is `@RequiredArgsConstructor`, so construct it by reading the constructor and passing a Mockito `mock(...)` for **each** dependency (the mocks are never invoked). Do not pass `null` for any dependency that the constructor body dereferences; mocks are always safe.

- [ ] **Step 1: Write the test class**

```java
package com.nubeero.cia.endorsement;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EndorsementService#calculatePremiumAdjustment} — the pro-rata endorsement
 * premium {@code (newAnnual − oldAnnual) × remainingDays / 365}, HALF_UP to 2dp. Pure arithmetic;
 * the service's collaborators are never exercised on this path (construct with Mockito mocks).
 *
 * <p>Expected values are hand-computed from the rule. Per the slice's test policy these assert the
 * correct outcome, not the current output.
 */
class EndorsementProRataTest {

    // Construct the service with mocks for every constructor dependency. The implementer fills these
    // in by reading EndorsementService's @RequiredArgsConstructor — e.g.
    //   new EndorsementService(mock(Dep1.class), mock(Dep2.class), ...);
    // calculatePremiumAdjustment touches none of them.
    private final EndorsementService service = EndorsementServiceTestFactory.withMockedDeps();

    @Test
    void increase_isPositiveAdditionalPremium() {
        // (730000 − 365000) × 100 / 365 = 365000 × 100 / 365 = 100000.00
        assertThat(service.calculatePremiumAdjustment(
                new BigDecimal("365000"), new BigDecimal("730000"), 100))
                .isEqualByComparingTo("100000.00");
    }

    @Test
    void decrease_isNegativeReturnPremium() {
        // (365000 − 730000) × 100 / 365 = −100000.00
        assertThat(service.calculatePremiumAdjustment(
                new BigDecimal("730000"), new BigDecimal("365000"), 100))
                .isEqualByComparingTo("-100000.00");
    }

    @Test
    void equalPremiums_returnZero() {
        assertThat(service.calculatePremiumAdjustment(
                new BigDecimal("500000"), new BigDecimal("500000"), 100))
                .isEqualByComparingTo("0");
    }

    @Test
    void nullNewPremium_returnsZero() {
        assertThat(service.calculatePremiumAdjustment(new BigDecimal("500000"), null, 100))
                .isEqualByComparingTo("0");
    }

    @Test
    void fullRemainingYear_isFullDifference() {
        // (200000 − 100000) × 365 / 365 = 100000.00
        assertThat(service.calculatePremiumAdjustment(
                new BigDecimal("100000"), new BigDecimal("200000"), 365))
                .isEqualByComparingTo("100000.00");
    }

    @Test
    void roundsHalfUpToTwoDecimals() {
        // (200000 − 100000) × 30 / 365 = 3000000 / 365 = 8219.1780... ⇒ 8219.18
        assertThat(service.calculatePremiumAdjustment(
                new BigDecimal("100000"), new BigDecimal("200000"), 30))
                .isEqualByComparingTo("8219.18");
    }
}
```

- [ ] **Step 2: Provide the tiny test factory (keeps the test readable)**

Create `cia-backend/cia-endorsement/src/test/java/com/nubeero/cia/endorsement/EndorsementServiceTestFactory.java`. Read `EndorsementService`'s constructor and return an instance with a Mockito `mock(...)` for each parameter. Example shape (replace the parameter list with the real one):

```java
package com.nubeero.cia.endorsement;

import static org.mockito.Mockito.mock;

/** Builds an {@link EndorsementService} with all collaborators mocked, for pure-method unit tests. */
final class EndorsementServiceTestFactory {
    private EndorsementServiceTestFactory() {}

    static EndorsementService withMockedDeps() {
        // TODO(implementer): mirror EndorsementService's @RequiredArgsConstructor parameter list.
        // Each is a mock; calculatePremiumAdjustment never calls them.
        return new EndorsementService(/* mock(XxxRepository.class), mock(YyyService.class), ... */);
    }
}
```
> If `EndorsementService` has many dependencies or a dependency whose construction is awkward, the simpler alternative is to inline the mocks directly in the test's field initializer and delete this factory — either is fine. The goal is a readable, dependency-free invocation of the pure method.

- [ ] **Step 3: Run the test, expect green (or a surfaced bug)**

Run: `cd cia-backend && mvn -q -pl cia-endorsement test -Dtest=EndorsementProRataTest`
Expected: **BUILD SUCCESS, 6 tests pass.** Same bug-surfacing policy as Task 1.

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-endorsement/src/test/java/com/nubeero/cia/endorsement/EndorsementProRataTest.java cia-backend/cia-endorsement/src/test/java/com/nubeero/cia/endorsement/EndorsementServiceTestFactory.java
git commit -m "test(endorsement): unit-cover pro-rata premium adjustment formula"
```

---

## Out of scope (log to backlog, do NOT build in this slice)

The other money-math areas from the audit need Testcontainers ITs or a testability refactor (private methods that persist) and are partially covered indirectly — they belong in a follow-up slice. After this slice lands, refine the `money-math-test-coverage` backlog row to point at the remainder:
- **Quote premium sequence** (`QuoteService.computeItemNet`/`sumAdjustments`/`recalculateTotals` — LOADING_FIRST vs DISCOUNT_FIRST, PERCENT vs FLAT, quote-level aggregation): private + persists → IT in `cia-api` mirroring `QuoteRiskGrossPremiumColumnIT`, or extract a pure `PremiumCalculator` (separate refactor slice).
- **Policy direct-entry premium + `computeCommissionAmount`** (`SI × rate` with no `/100`, `discount.min(total)`, `net × rate/100`): IT, or fold the commission-amount formula into a pure helper.
- **Claims money defaults** (`approvedAmount ← reserveAmount`, `dvAmount ← approvedAmount`, reserve replacement): IT in `cia-api`.

Also note in the backlog/status if Task 1 or Task 2 **surfaced a production bug** (a `@Disabled("BUG: ...")` test), with a new row per bug.

---

## Self-Review (completed by plan author)

- **Spec coverage:** The two zero-coverage pure-arithmetic engines named as this slice's goal each have a task (Task 1 = AllocationService surplus/QS/XOL/lines/guard; Task 2 = pro-rata increase/decrease/zero/null/round/full-year). The audit's other three areas are explicitly listed as out-of-scope follow-ups, not silently dropped.
- **Placeholder scan:** The only `TODO` is the intentional implementer seam in the `EndorsementServiceTestFactory` (the constructor parameter list genuinely must be read from the real source — the plan can't know it without guessing); every asserted numeric value is concrete and hand-derived. No "add edge cases"/"write tests for the above" placeholders.
- **Type consistency:** `allocate(...)` signature, `RiTreaty`/`RiTreatyParticipant`/`RiAllocation`/`RiAllocationLine` builder fields + getters, and `calculatePremiumAdjustment(BigDecimal, BigDecimal, long)` all match the read source. `isEqualByComparingTo` used for every `BigDecimal` assertion. Treaty must be `ACTIVE` (guard test uses `DRAFT`).
