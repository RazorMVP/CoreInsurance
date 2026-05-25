# F7 Slice α — Receipt & Payment Visibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every receipt and payment visible in two surfaces — (1) a flat list per Finance sub-module under restored "Receipts"/"Payments" sub-tabs and (2) a nested list inside the DN/CN detail dialogs — with reversal-audit columns surfaced everywhere a reversed row appears. Wire the pre-existing `ReverseTransactionDialog.tsx` (dead since Session 103) into all four surfaces. **No PDF, no email** (those are slices β, γ).

**Architecture:** Two new flat-list controllers (`ReceiptListController`, `PaymentListController`) at `/api/v1/receipts` and `/api/v1/payments`, keeping the existing nested controllers untouched. Filtering via `JpaSpecificationExecutor` + a static `*Specs` factory class. Projection DTOs (`ReceiptListItemResponse`, `PaymentListItemResponse`) built from JPQL constructor expressions to avoid N+1. Audit-fix on `reverse()` (currently writes no `audit_log` row) is in scope because the visibility UI shows reversal columns and needs the audit data populated.

**Tech Stack:** Java 21 + Spring Boot 3 + Spring Data JPA + Hibernate + Spring Security (`@PreAuthorize`); JUnit 5 + Testcontainers + `@SpringBootTest`; React 18 + Vite + TypeScript + zod + TanStack React Query + shadcn/ui DataTable.

**Backlog row drained on commit of final task:** none yet (F7 stays open until γ ships).

---

## File structure

### Backend (`cia-backend/`)

| Action | Path | Responsibility |
|---|---|---|
| Modify | `cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptService.java` | Add `findAll(Specification, Pageable)`; modify `reverse(...)` to invoke `AuditService.log` with `action=REVERSE`. |
| Modify | `cia-finance/src/main/java/com/nubeero/cia/finance/PaymentService.java` | Same pattern as ReceiptService. |
| Modify | `cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptRepository.java` | Add `extends JpaSpecificationExecutor<Receipt>` to the interface declaration. |
| Modify | `cia-finance/src/main/java/com/nubeero/cia/finance/PaymentRepository.java` | Same. |
| Create | `cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptSpecs.java` | Static factory class composing JPA Specifications for filtering. |
| Create | `cia-finance/src/main/java/com/nubeero/cia/finance/PaymentSpecs.java` | Same shape for Payment. |
| Create | `cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptListItemResponse.java` | Projection DTO for the flat list (carries DN context, customer, reversal columns). |
| Create | `cia-finance/src/main/java/com/nubeero/cia/finance/PaymentListItemResponse.java` | Mirror with `beneficiaryType` + `beneficiaryReference`. |
| Create | `cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptListController.java` | `GET /api/v1/receipts` flat list. |
| Create | `cia-finance/src/main/java/com/nubeero/cia/finance/PaymentListController.java` | `GET /api/v1/payments` flat list. |
| Create | `cia-api/src/test/java/com/nubeero/cia/api/finance/ReceiptReverseAuditIT.java` | 1 IT: `reverse()` writes an `AuditLog` row with `action=REVERSE`. |
| Create | `cia-api/src/test/java/com/nubeero/cia/api/finance/PaymentReverseAuditIT.java` | Mirror. |
| Create | `cia-api/src/test/java/com/nubeero/cia/api/finance/ReceiptListControllerIT.java` | 5 ITs: happy path, status filter, debitNoteId filter, role gating, tenant isolation. |
| Create | `cia-api/src/test/java/com/nubeero/cia/api/finance/PaymentListControllerIT.java` | Mirror. |

### Frontend (`cia-frontend/`)

| Action | Path | Responsibility |
|---|---|---|
| Modify | `packages/api-client/src/modules/finance.ts` | Add `ReceiptListItemResponseSchema` + `PaymentListItemResponseSchema` (zod); add `listReceipts(filters)` / `listPayments(filters)` functions. |
| Create | `apps/back-office/src/modules/finance/hooks/useReceipts.ts` | `useReceiptList(filters)` query hook + `useReverseReceipt()` mutation. |
| Create | `apps/back-office/src/modules/finance/hooks/usePayments.ts` | Mirror. |
| Create | `apps/back-office/src/modules/finance/pages/receivables/ReceiptsListSection.tsx` | Flat list table with filter bar + Reverse row action. |
| Create | `apps/back-office/src/modules/finance/pages/payables/PaymentsListSection.tsx` | Mirror. |
| Modify | `apps/back-office/src/modules/finance/pages/receivables/ReceivablesTab.tsx` | Wrap existing debit-notes table in `<Tabs>` with two TabsTriggers; second tab renders `<ReceiptsListSection>`. |
| Modify | `apps/back-office/src/modules/finance/pages/payables/PayablesTab.tsx` | Same for credit-notes + payments. |
| Modify | `apps/back-office/src/modules/finance/pages/receivables/DebitNoteDetailDialog.tsx` | Add nested "Receipts" section listing receipts for this DN, with per-row Reverse action. |
| Modify | `apps/back-office/src/modules/finance/pages/payables/CreditNoteDetailDialog.tsx` | Same for payments. |

### Documentation

| Action | Path | Responsibility |
|---|---|---|
| Modify | `CLAUDE.md` | Module 8 Finance feature description; Frontend Build Queue Build 6 sub-page descriptions; new Development Conventions bullet for separate `*ListController` per child aggregate. |
| Append | `cia-log.md` | New session entry describing slice α landing; backlog reconciliation unchanged (F7 stays). |
| Modify | `docs-site/static/internal-api.json` | Add `/receipts` (GET) + `/payments` (GET) entries. |

---

## Tasks

### Task 1: Receipt reverse-audit fix — write failing IT

**Files:**
- Test: `cia-api/src/test/java/com/nubeero/cia/api/finance/ReceiptReverseAuditIT.java` (create)

- [ ] **Step 1: Create the failing IT file**

```java
package com.nubeero.cia.api.finance;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditLogRepository;
import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.Receipt;
import com.nubeero.cia.finance.ReceiptService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE", "FINANCE_UPDATE"})
class ReceiptReverseAuditIT extends FinanceItSupport {

    @Autowired ReceiptService receiptService;
    @Autowired AuditLogRepository auditLogRepository;

    @Test
    void reverse_writesAuditLogRowWithActionReverse() {
        UUID dnId = fixtures.createApprovedPolicyAndDebitNote();
        Receipt posted = receiptService.post(
                dnId,
                new BigDecimal("100000.00"),
                LocalDate.now(),
                PaymentMethod.BANK_TRANSFER,
                null, "First Bank", null, "Initial premium"
        );

        long auditBefore = auditLogRepository.count();

        receiptService.reverse(posted.getId(), "Customer requested refund — wrong account credited");

        long auditAfter = auditLogRepository.count();
        assertThat(auditAfter).isEqualTo(auditBefore + 1);

        var newest = auditLogRepository.findTopByOrderByTimestampDesc().orElseThrow();
        assertThat(newest.getAction()).isEqualTo(AuditAction.REVERSE);
        assertThat(newest.getEntityType()).isEqualTo("Receipt");
        assertThat(newest.getEntityId()).isEqualTo(posted.getId().toString());
        assertThat(newest.getOldValue()).contains("\"status\":\"POSTED\"");
        assertThat(newest.getNewValue()).contains("\"status\":\"REVERSED\"");
        assertThat(newest.getNewValue()).contains("Customer requested refund");
    }
}
```

> **Note on `FinanceItSupport`:** if this base class does not exist yet in `cia-api/src/test/.../finance/`, look at the existing `cia-api/src/test/java/com/nubeero/cia/api/finance/gl/PeriodLockInterceptorIT.java` for the Testcontainers + Flyway boot pattern and create a minimal `FinanceItSupport.java` superclass in the same package that pulls in the `PostgreSQLContainer<?> POSTGRES` + `@DynamicPropertySource datasourceProps(...)` + a `fixtures` helper bean. The fixture method `createApprovedPolicyAndDebitNote()` should insert a tenant + approved policy + debit-note row directly via `JdbcTemplate` so the test does not depend on the policy approval workflow. If `findTopByOrderByTimestampDesc()` is not on `AuditLogRepository` yet, add it.

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
cd /Users/razormvp/CoreInsurance/cia-backend
mvn -pl cia-api failsafe:integration-test -Dit.test=ReceiptReverseAuditIT -DskipUnitTests=true
```

Expected: FAIL with `expected: <auditBefore + 1> but was: <auditBefore>` — `ReceiptService.reverse(...)` does not currently write an audit log row (verified at `cia-finance/.../ReceiptService.java:74-89` — no `auditService.log(...)` call).

---

### Task 2: Receipt reverse-audit fix — implement

**Files:**
- Modify: `cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptService.java`

- [ ] **Step 1: Add AuditService dependency + invoke from reverse()**

Replace the constructor + `reverse(...)` method in `ReceiptService.java`. Add the import `import com.nubeero.cia.common.audit.AuditAction;` and `import com.nubeero.cia.common.audit.AuditService;` near the top of the file.

New constructor (replaces lines 24-30):

```java
public ReceiptService(ReceiptRepository receiptRepository,
                      DebitNoteService debitNoteService,
                      FinanceNumberService numberService,
                      com.nubeero.cia.common.audit.AuditService auditService) {
    this.receiptRepository = receiptRepository;
    this.debitNoteService = debitNoteService;
    this.numberService = numberService;
    this.auditService = auditService;
}
```

Add the field next to the existing three (after line 22):

```java
private final com.nubeero.cia.common.audit.AuditService auditService;
```

New `reverse(...)` (replaces lines 74-89):

```java
@Transactional
public void reverse(UUID receiptId, String reversalReason) {
    Receipt receipt = findOrThrow(receiptId);
    if (receipt.getStatus() == TransactionStatus.REVERSED) {
        throw new IllegalStateException("Receipt is already reversed");
    }

    // Capture pre-state snapshot for audit BEFORE mutation.
    ReverseSnapshot oldValue = new ReverseSnapshot(
            receipt.getStatus(), null, null, null);

    receipt.setStatus(TransactionStatus.REVERSED);
    receipt.setReversalReason(reversalReason);
    receipt.setReversedAt(Instant.now());
    receipt.setReversedBy(currentUser());
    Receipt saved = receiptRepository.save(receipt);

    ReverseSnapshot newValue = new ReverseSnapshot(
            saved.getStatus(),
            saved.getReversedAt(),
            saved.getReversedBy(),
            saved.getReversalReason());

    auditService.log("Receipt", saved.getId().toString(),
            AuditAction.REVERSE, oldValue, newValue);

    UUID debitNoteId = saved.getDebitNote().getId();
    BigDecimal newPaid = sumPostedReceipts(debitNoteId);
    debitNoteService.recalculateStatus(debitNoteId, newPaid);
}

private record ReverseSnapshot(
        TransactionStatus status,
        Instant reversedAt,
        String reversedBy,
        String reversalReason) {}
```

- [ ] **Step 2: Run the test to verify it passes**

Run:
```bash
cd /Users/razormvp/CoreInsurance/cia-backend
mvn -pl cia-api failsafe:integration-test -Dit.test=ReceiptReverseAuditIT -DskipUnitTests=true
```

Expected: PASS — 1 test, 0 failures.

- [ ] **Step 3: Run full cia-finance test suite to confirm no regression**

Run:
```bash
mvn -pl cia-finance test
```

Expected: All existing tests still pass.

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptService.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/ReceiptReverseAuditIT.java
git commit -m "$(cat <<'EOF'
feat(finance): Slice α / Task 2 — ReceiptService.reverse now writes AuditLog REVERSE row

The reverse() method previously mutated status + reversedAt + reversedBy + reversalReason
without an AuditLog write. Slice α's visibility UI surfaces those columns, so the audit data
must exist to be displayed honestly. New ReverseSnapshot record captures before/after for the
audit row. New ReceiptReverseAuditIT pins the property.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Payment reverse-audit fix — write failing IT

**Files:**
- Test: `cia-api/src/test/java/com/nubeero/cia/api/finance/PaymentReverseAuditIT.java` (create)

- [ ] **Step 1: Create the failing IT file**

```java
package com.nubeero.cia.api.finance;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditLogRepository;
import com.nubeero.cia.finance.Payment;
import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE", "FINANCE_UPDATE"})
class PaymentReverseAuditIT extends FinanceItSupport {

    @Autowired PaymentService paymentService;
    @Autowired AuditLogRepository auditLogRepository;

    @Test
    void reverse_writesAuditLogRowWithActionReverse() {
        UUID cnId = fixtures.createApprovedClaimAndCreditNote();
        Payment posted = paymentService.post(
                cnId,
                new BigDecimal("250000.00"),
                LocalDate.now(),
                PaymentMethod.BANK_TRANSFER,
                null, "First Bank", "0123456789", "John Doe", null, "Claim settlement"
        );

        long auditBefore = auditLogRepository.count();

        paymentService.reverse(posted.getId(), "Beneficiary account closed — rerouting required");

        long auditAfter = auditLogRepository.count();
        assertThat(auditAfter).isEqualTo(auditBefore + 1);

        var newest = auditLogRepository.findTopByOrderByTimestampDesc().orElseThrow();
        assertThat(newest.getAction()).isEqualTo(AuditAction.REVERSE);
        assertThat(newest.getEntityType()).isEqualTo("Payment");
        assertThat(newest.getEntityId()).isEqualTo(posted.getId().toString());
        assertThat(newest.getOldValue()).contains("\"status\":\"POSTED\"");
        assertThat(newest.getNewValue()).contains("\"status\":\"REVERSED\"");
        assertThat(newest.getNewValue()).contains("Beneficiary account closed");
    }
}
```

> **Note on `PaymentService.post(...) signature`:** read the current signature at `cia-finance/.../PaymentService.java:42-72` — the parameter list may include additional fields (`bankAccountNumber`, `bankAccountName`, `beneficiaryName`) that Receipt does not. Match the test call to the actual signature before running.

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
mvn -pl cia-api failsafe:integration-test -Dit.test=PaymentReverseAuditIT -DskipUnitTests=true
```

Expected: FAIL — `PaymentService.reverse(...)` does not currently write an audit log row.

---

### Task 4: Payment reverse-audit fix — implement

**Files:**
- Modify: `cia-finance/src/main/java/com/nubeero/cia/finance/PaymentService.java`

- [ ] **Step 1: Add AuditService dependency + invoke from reverse()**

Mirror the change from Task 2. Add the AuditService field + constructor parameter, then replace `reverse(...)`:

```java
@Transactional
public void reverse(UUID paymentId, String reversalReason) {
    Payment payment = findOrThrow(paymentId);
    if (payment.getStatus() == TransactionStatus.REVERSED) {
        throw new IllegalStateException("Payment is already reversed");
    }

    ReverseSnapshot oldValue = new ReverseSnapshot(
            payment.getStatus(), null, null, null);

    payment.setStatus(TransactionStatus.REVERSED);
    payment.setReversalReason(reversalReason);
    payment.setReversedAt(Instant.now());
    payment.setReversedBy(currentUser());
    Payment saved = paymentRepository.save(payment);

    ReverseSnapshot newValue = new ReverseSnapshot(
            saved.getStatus(),
            saved.getReversedAt(),
            saved.getReversedBy(),
            saved.getReversalReason());

    auditService.log("Payment", saved.getId().toString(),
            AuditAction.REVERSE, oldValue, newValue);

    UUID creditNoteId = saved.getCreditNote().getId();
    BigDecimal newPaid = sumPostedPayments(creditNoteId);
    creditNoteService.recalculateStatus(creditNoteId, newPaid);
}

private record ReverseSnapshot(
        TransactionStatus status,
        Instant reversedAt,
        String reversedBy,
        String reversalReason) {}
```

Add the same imports + field + constructor parameter as Task 2 (substituting `Payment` for `Receipt`).

- [ ] **Step 2: Run the test to verify it passes**

Run:
```bash
mvn -pl cia-api failsafe:integration-test -Dit.test=PaymentReverseAuditIT -DskipUnitTests=true
```

Expected: PASS.

- [ ] **Step 3: Run full cia-finance test suite**

```bash
mvn -pl cia-finance test
```

Expected: All existing tests still pass.

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/PaymentService.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/PaymentReverseAuditIT.java
git commit -m "$(cat <<'EOF'
feat(finance): Slice α / Task 4 — PaymentService.reverse now writes AuditLog REVERSE row

Mirror of ReceiptService.reverse audit fix (Task 2). PaymentService.reverse previously
mutated status + reversal columns without an AuditLog write. Slice α's visibility UI
surfaces those columns, so the audit row must exist. New PaymentReverseAuditIT pins
the property.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Receipt repository + ReceiptSpecs

**Files:**
- Modify: `cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptRepository.java`
- Create: `cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptSpecs.java`

- [ ] **Step 1: Add JpaSpecificationExecutor to ReceiptRepository**

Open `cia-finance/.../ReceiptRepository.java`. Modify the interface declaration:

```java
package com.nubeero.cia.finance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReceiptRepository
        extends JpaRepository<Receipt, UUID>, JpaSpecificationExecutor<Receipt> {

    Optional<Receipt> findByIdAndDeletedAtIsNull(UUID id);

    Page<Receipt> findAllByDebitNote_IdAndDeletedAtIsNull(UUID debitNoteId, Pageable pageable);

    List<Receipt> findAllByDebitNote_IdAndStatusAndDeletedAtIsNull(
            UUID debitNoteId, TransactionStatus status);
}
```

- [ ] **Step 2: Create ReceiptSpecs static factory**

```java
package com.nubeero.cia.finance;

import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.UUID;

/** Static factory for filtering Receipt list queries. Each method returns a JPA Specification
 *  that can be composed via Specification.where(...).and(...). */
public final class ReceiptSpecs {

    private ReceiptSpecs() {}

    public static Specification<Receipt> deletedAtIsNull() {
        return (root, q, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<Receipt> statusEquals(TransactionStatus status) {
        if (status == null) return null;
        return (root, q, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Receipt> createdBetween(Instant from, Instant to) {
        if (from == null && to == null) return null;
        return (root, q, cb) -> {
            if (from != null && to != null) {
                return cb.between(root.get("createdAt"), from, to);
            }
            if (from != null) {
                return cb.greaterThanOrEqualTo(root.get("createdAt"), from);
            }
            return cb.lessThanOrEqualTo(root.get("createdAt"), to);
        };
    }

    public static Specification<Receipt> paymentMethodEquals(PaymentMethod method) {
        if (method == null) return null;
        return (root, q, cb) -> cb.equal(root.get("paymentMethod"), method);
    }

    public static Specification<Receipt> debitNoteIdEquals(UUID debitNoteId) {
        if (debitNoteId == null) return null;
        return (root, q, cb) -> cb.equal(
                root.join("debitNote", JoinType.INNER).get("id"), debitNoteId);
    }
}
```

- [ ] **Step 3: Compile check**

```bash
mvn -pl cia-finance compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptRepository.java \
        cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptSpecs.java
git commit -m "$(cat <<'EOF'
feat(finance): Slice α / Task 5 — Receipt repo + ReceiptSpecs for flat-list filtering

ReceiptRepository now extends JpaSpecificationExecutor<Receipt>. New ReceiptSpecs static
factory composes JPA Specifications for the upcoming flat-list endpoint: status, date range,
payment method, debit-note id, soft-delete. Each spec returns null when its filter arg is
null so callers can compose with Specification.where(...).and(...) without conditional logic.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Payment repository + PaymentSpecs

**Files:**
- Modify: `cia-finance/src/main/java/com/nubeero/cia/finance/PaymentRepository.java`
- Create: `cia-finance/src/main/java/com/nubeero/cia/finance/PaymentSpecs.java`

- [ ] **Step 1: Add JpaSpecificationExecutor to PaymentRepository**

```java
package com.nubeero.cia.finance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository
        extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    Optional<Payment> findByIdAndDeletedAtIsNull(UUID id);

    Page<Payment> findAllByCreditNote_IdAndDeletedAtIsNull(UUID creditNoteId, Pageable pageable);

    List<Payment> findAllByCreditNote_IdAndStatusAndDeletedAtIsNull(
            UUID creditNoteId, TransactionStatus status);
}
```

- [ ] **Step 2: Create PaymentSpecs static factory**

```java
package com.nubeero.cia.finance;

import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.UUID;

public final class PaymentSpecs {

    private PaymentSpecs() {}

    public static Specification<Payment> deletedAtIsNull() {
        return (root, q, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<Payment> statusEquals(TransactionStatus status) {
        if (status == null) return null;
        return (root, q, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Payment> createdBetween(Instant from, Instant to) {
        if (from == null && to == null) return null;
        return (root, q, cb) -> {
            if (from != null && to != null) {
                return cb.between(root.get("createdAt"), from, to);
            }
            if (from != null) {
                return cb.greaterThanOrEqualTo(root.get("createdAt"), from);
            }
            return cb.lessThanOrEqualTo(root.get("createdAt"), to);
        };
    }

    public static Specification<Payment> paymentMethodEquals(PaymentMethod method) {
        if (method == null) return null;
        return (root, q, cb) -> cb.equal(root.get("paymentMethod"), method);
    }

    public static Specification<Payment> creditNoteIdEquals(UUID creditNoteId) {
        if (creditNoteId == null) return null;
        return (root, q, cb) -> cb.equal(
                root.join("creditNote", JoinType.INNER).get("id"), creditNoteId);
    }
}
```

- [ ] **Step 3: Compile check**

```bash
mvn -pl cia-finance compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/PaymentRepository.java \
        cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/PaymentSpecs.java
git commit -m "$(cat <<'EOF'
feat(finance): Slice α / Task 6 — Payment repo + PaymentSpecs for flat-list filtering

Mirror of Task 5 for Payment. PaymentRepository now extends JpaSpecificationExecutor<Payment>.
New PaymentSpecs composes filters for the upcoming /api/v1/payments flat endpoint.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Receipt projection DTO + service findAll

**Files:**
- Create: `cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptListItemResponse.java`
- Modify: `cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptService.java`

- [ ] **Step 1: Create ReceiptListItemResponse projection record**

```java
package com.nubeero.cia.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Projection DTO for the flat list endpoint GET /api/v1/receipts.
 *  Carries DN + policy + customer context so the table row does not need to
 *  separately fetch each parent. Reversal columns are populated when
 *  status == REVERSED. */
public record ReceiptListItemResponse(
        UUID id,
        String reference,            // = receiptNumber e.g. REC-2026-00001
        UUID debitNoteId,
        String debitNoteNumber,
        String policyNumber,         // nullable — DN may not be policy-backed
        String customerName,         // nullable — derived from DN -> policy -> customer
        BigDecimal amount,
        PaymentMethod paymentMethod,
        LocalDate paymentDate,
        TransactionStatus status,
        Instant reversedAt,          // nullable
        String reversedBy,           // nullable
        String reversalReason,       // nullable
        Instant createdAt
) {}
```

- [ ] **Step 2: Add findAll(Specification, Pageable) to ReceiptService**

Add the new method to `ReceiptService.java` (place it next to the existing `findByDebitNote`, line ~32):

```java
public org.springframework.data.domain.Page<ReceiptListItemResponse> findAll(
        org.springframework.data.jpa.domain.Specification<Receipt> spec,
        org.springframework.data.domain.Pageable pageable) {
    // Always exclude soft-deleted rows.
    var fullSpec = (spec == null
            ? ReceiptSpecs.deletedAtIsNull()
            : org.springframework.data.jpa.domain.Specification
                    .where(ReceiptSpecs.deletedAtIsNull()).and(spec));

    return receiptRepository.findAll(fullSpec, pageable).map(this::toListItem);
}

private ReceiptListItemResponse toListItem(Receipt r) {
    DebitNote dn = r.getDebitNote();
    String policyNumber = null;
    String customerName = null;
    if (dn != null && dn.getPolicy() != null) {
        policyNumber = dn.getPolicy().getPolicyNumber();
        if (dn.getPolicy().getCustomer() != null) {
            customerName = dn.getPolicy().getCustomer().getDisplayName();
        }
    }
    return new ReceiptListItemResponse(
            r.getId(),
            r.getReceiptNumber(),
            dn != null ? dn.getId() : null,
            dn != null ? dn.getDebitNoteNumber() : null,
            policyNumber,
            customerName,
            r.getAmount(),
            r.getPaymentMethod(),
            r.getPaymentDate(),
            r.getStatus(),
            r.getReversedAt(),
            r.getReversedBy(),
            r.getReversalReason(),
            r.getCreatedAt());
}
```

> **Note on `Customer.getDisplayName()`:** inspect the `Customer` entity to find the field exposing the human-readable name. If it's `companyName` for corporate + `firstName + lastName` for individual, build a helper that picks based on customer type. The plan assumes a `getDisplayName()` method — add one to `Customer` if missing, or inline the resolution here.

- [ ] **Step 3: Compile check**

```bash
mvn -pl cia-finance compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptListItemResponse.java \
        cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptService.java
git commit -m "$(cat <<'EOF'
feat(finance): Slice α / Task 7 — ReceiptListItemResponse projection + service findAll

ReceiptListItemResponse is the projection the flat list endpoint returns. It carries
DN + policy + customer context so the table row does not need N+1 fetches. New
ReceiptService.findAll(Specification, Pageable) returns Page<ReceiptListItemResponse>
and always composes with deletedAtIsNull() so callers cannot accidentally return
soft-deleted rows.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Payment projection DTO + service findAll

**Files:**
- Create: `cia-finance/src/main/java/com/nubeero/cia/finance/PaymentListItemResponse.java`
- Modify: `cia-finance/src/main/java/com/nubeero/cia/finance/PaymentService.java`

- [ ] **Step 1: Create PaymentListItemResponse projection record**

```java
package com.nubeero.cia.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Projection DTO for GET /api/v1/payments. Includes beneficiaryType +
 *  beneficiaryReference so the flat-list UI can show "Claim DV CLM-001234"
 *  vs "Commission BRK-007" vs "FAC Outward FAC-2026-009". */
public record PaymentListItemResponse(
        UUID id,
        String reference,             // = paymentNumber e.g. PAY-2026-00001
        UUID creditNoteId,
        String creditNoteNumber,
        String beneficiaryType,       // = CreditNote.sourceType.name() e.g. CLAIM_DV
        String beneficiaryReference,  // = CreditNote.sourceReference
        BigDecimal amount,
        PaymentMethod paymentMethod,
        LocalDate paymentDate,
        TransactionStatus status,
        Instant reversedAt,
        String reversedBy,
        String reversalReason,
        Instant createdAt
) {}
```

- [ ] **Step 2: Add findAll(Specification, Pageable) to PaymentService**

Add to `PaymentService.java`:

```java
public org.springframework.data.domain.Page<PaymentListItemResponse> findAll(
        org.springframework.data.jpa.domain.Specification<Payment> spec,
        org.springframework.data.domain.Pageable pageable) {
    var fullSpec = (spec == null
            ? PaymentSpecs.deletedAtIsNull()
            : org.springframework.data.jpa.domain.Specification
                    .where(PaymentSpecs.deletedAtIsNull()).and(spec));

    return paymentRepository.findAll(fullSpec, pageable).map(this::toListItem);
}

private PaymentListItemResponse toListItem(Payment p) {
    CreditNote cn = p.getCreditNote();
    return new PaymentListItemResponse(
            p.getId(),
            p.getPaymentNumber(),
            cn != null ? cn.getId() : null,
            cn != null ? cn.getCreditNoteNumber() : null,
            cn != null && cn.getSourceType() != null ? cn.getSourceType().name() : null,
            cn != null ? cn.getSourceReference() : null,
            p.getAmount(),
            p.getPaymentMethod(),
            p.getPaymentDate(),
            p.getStatus(),
            p.getReversedAt(),
            p.getReversedBy(),
            p.getReversalReason(),
            p.getCreatedAt());
}
```

- [ ] **Step 3: Compile check**

```bash
mvn -pl cia-finance compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/PaymentListItemResponse.java \
        cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/PaymentService.java
git commit -m "$(cat <<'EOF'
feat(finance): Slice α / Task 8 — PaymentListItemResponse projection + service findAll

Mirror of Task 7 for Payment. PaymentListItemResponse carries beneficiaryType +
beneficiaryReference (resolved from CreditNote.sourceType / sourceReference) so the
flat-list UI shows readable beneficiary labels per payment.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: ReceiptListController + IT

**Files:**
- Create: `cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptListController.java`
- Create: `cia-api/src/test/java/com/nubeero/cia/api/finance/ReceiptListControllerIT.java`

- [ ] **Step 1: Write the failing IT first**

```java
package com.nubeero.cia.api.finance;

import com.nubeero.cia.finance.TransactionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE", "FINANCE_UPDATE"})
class ReceiptListControllerIT extends FinanceItSupport {

    @Autowired MockMvc mockMvc;

    @Test
    void listReceipts_returnsPagedResults() throws Exception {
        UUID dnId = fixtures.createApprovedPolicyAndDebitNote();
        fixtures.postReceiptOf(dnId, "100000.00");
        fixtures.postReceiptOf(dnId, "200000.00");

        mockMvc.perform(get("/api/v1/receipts").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()", greaterThan(1)))
                .andExpect(jsonPath("$.meta.total", greaterThan(1)));
    }

    @Test
    void listReceipts_statusPostedFiltersOutReversed() throws Exception {
        UUID dnId = fixtures.createApprovedPolicyAndDebitNote();
        UUID r1 = fixtures.postReceiptOf(dnId, "100000.00");
        UUID r2 = fixtures.postReceiptOf(dnId, "200000.00");
        fixtures.reverseReceipt(r2, "wrong amount");

        mockMvc.perform(get("/api/v1/receipts").param("status", "POSTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + r1 + "')].status").value("POSTED"))
                .andExpect(jsonPath("$.data[?(@.id == '" + r2 + "')]").isEmpty());
    }

    @Test
    void listReceipts_debitNoteIdFilterNarrowsToOneDn() throws Exception {
        UUID dn1 = fixtures.createApprovedPolicyAndDebitNote();
        UUID dn2 = fixtures.createApprovedPolicyAndDebitNote();
        fixtures.postReceiptOf(dn1, "100000.00");
        fixtures.postReceiptOf(dn2, "300000.00");

        mockMvc.perform(get("/api/v1/receipts").param("debitNoteId", dn1.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.debitNoteId == '" + dn1 + "')]").exists())
                .andExpect(jsonPath("$.data[?(@.debitNoteId == '" + dn2 + "')]").isEmpty());
    }

    @Test
    @WithMockUser(username = "bob", authorities = {"CLAIMS_VIEW"})
    void listReceipts_returns403WithoutFinanceViewRole() throws Exception {
        mockMvc.perform(get("/api/v1/receipts"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listReceipts_paginationMetaIsPopulated() throws Exception {
        UUID dnId = fixtures.createApprovedPolicyAndDebitNote();
        for (int i = 0; i < 25; i++) {
            fixtures.postReceiptOf(dnId, "10000.00");
        }
        mockMvc.perform(get("/api/v1/receipts").param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.meta.total", greaterThan(20)));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails (no controller yet)**

Run:
```bash
mvn -pl cia-api failsafe:integration-test -Dit.test=ReceiptListControllerIT -DskipUnitTests=true
```

Expected: FAIL — `GET /api/v1/receipts` → 404 (no controller registered).

- [ ] **Step 3: Create ReceiptListController**

```java
package com.nubeero.cia.finance;

import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/receipts")
public class ReceiptListController {

    private final ReceiptService receiptService;

    public ReceiptListController(ReceiptService receiptService) {
        this.receiptService = receiptService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('FINANCE_VIEW')")
    public ResponseEntity<ApiResponse<List<ReceiptListItemResponse>>> list(
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @RequestParam(required = false) PaymentMethod paymentMethod,
            @RequestParam(required = false) UUID debitNoteId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Specification<Receipt> spec = Specification.where(ReceiptSpecs.statusEquals(status))
                .and(ReceiptSpecs.createdBetween(createdFrom, createdTo))
                .and(ReceiptSpecs.paymentMethodEquals(paymentMethod))
                .and(ReceiptSpecs.debitNoteIdEquals(debitNoteId));

        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<ReceiptListItemResponse> result = receiptService.findAll(spec, pageable);

        ApiMeta meta = ApiMeta.builder()
                .total(result.getTotalElements())
                .page(result.getNumber())
                .size(result.getSize())
                .build();

        return ResponseEntity.ok(ApiResponse.success(result.getContent(), meta));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
mvn install -DskipTests -pl cia-api -am
mvn -pl cia-api failsafe:integration-test -Dit.test=ReceiptListControllerIT -DskipUnitTests=true
```

> **Why `mvn install` first:** `mvn spring-boot:run` and `mvn failsafe:integration-test` resolve dependencies from `~/.m2`. After editing a non-cia-api module, a stale SNAPSHOT JAR will cause the test to load the previous module bytecode. CLAUDE.md documents this gotcha.

Expected: PASS — 5 tests.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptListController.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/ReceiptListControllerIT.java
git commit -m "$(cat <<'EOF'
feat(finance): Slice α / Task 9 — ReceiptListController for GET /api/v1/receipts

New flat-list controller, deliberately kept separate from the existing nested
ReceiptController (which stays at /api/v1/debit-notes/{dnId}/receipts). FINANCE_VIEW
gated; supports status / createdFrom / createdTo / paymentMethod / debitNoteId
filters; cursor-style pagination via Spring Page; envelope is the standard
ApiResponse<List<T>> + ApiMeta { total, page, size }. ReceiptListControllerIT
exercises happy path, status filter, debitNoteId filter, role gating, and
pagination meta.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: PaymentListController + IT

**Files:**
- Create: `cia-finance/src/main/java/com/nubeero/cia/finance/PaymentListController.java`
- Create: `cia-api/src/test/java/com/nubeero/cia/api/finance/PaymentListControllerIT.java`

- [ ] **Step 1: Write the failing IT**

```java
package com.nubeero.cia.api.finance;

import com.nubeero.cia.finance.TransactionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE", "FINANCE_UPDATE"})
class PaymentListControllerIT extends FinanceItSupport {

    @Autowired MockMvc mockMvc;

    @Test
    void listPayments_returnsPagedResults() throws Exception {
        UUID cnId = fixtures.createApprovedClaimAndCreditNote();
        fixtures.postPaymentOf(cnId, "250000.00");
        fixtures.postPaymentOf(cnId, "350000.00");

        mockMvc.perform(get("/api/v1/payments").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()", greaterThan(1)));
    }

    @Test
    void listPayments_statusPostedFiltersOutReversed() throws Exception {
        UUID cnId = fixtures.createApprovedClaimAndCreditNote();
        UUID p1 = fixtures.postPaymentOf(cnId, "100000.00");
        UUID p2 = fixtures.postPaymentOf(cnId, "200000.00");
        fixtures.reversePayment(p2, "duplicate");

        mockMvc.perform(get("/api/v1/payments").param("status", "POSTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + p1 + "')].status").value("POSTED"))
                .andExpect(jsonPath("$.data[?(@.id == '" + p2 + "')]").isEmpty());
    }

    @Test
    void listPayments_creditNoteIdFilterNarrowsToOneCn() throws Exception {
        UUID cn1 = fixtures.createApprovedClaimAndCreditNote();
        UUID cn2 = fixtures.createApprovedClaimAndCreditNote();
        fixtures.postPaymentOf(cn1, "100000.00");
        fixtures.postPaymentOf(cn2, "300000.00");

        mockMvc.perform(get("/api/v1/payments").param("creditNoteId", cn1.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.creditNoteId == '" + cn1 + "')]").exists())
                .andExpect(jsonPath("$.data[?(@.creditNoteId == '" + cn2 + "')]").isEmpty());
    }

    @Test
    @WithMockUser(username = "bob", authorities = {"CLAIMS_VIEW"})
    void listPayments_returns403WithoutFinanceViewRole() throws Exception {
        mockMvc.perform(get("/api/v1/payments"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listPayments_paginationMetaIsPopulated() throws Exception {
        UUID cnId = fixtures.createApprovedClaimAndCreditNote();
        for (int i = 0; i < 25; i++) {
            fixtures.postPaymentOf(cnId, "10000.00");
        }
        mockMvc.perform(get("/api/v1/payments").param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.meta.total", greaterThan(20)));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -pl cia-api failsafe:integration-test -Dit.test=PaymentListControllerIT -DskipUnitTests=true
```

Expected: FAIL — 404.

- [ ] **Step 3: Create PaymentListController**

```java
package com.nubeero.cia.finance;

import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentListController {

    private final PaymentService paymentService;

    public PaymentListController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('FINANCE_VIEW')")
    public ResponseEntity<ApiResponse<List<PaymentListItemResponse>>> list(
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @RequestParam(required = false) PaymentMethod paymentMethod,
            @RequestParam(required = false) UUID creditNoteId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Specification<Payment> spec = Specification.where(PaymentSpecs.statusEquals(status))
                .and(PaymentSpecs.createdBetween(createdFrom, createdTo))
                .and(PaymentSpecs.paymentMethodEquals(paymentMethod))
                .and(PaymentSpecs.creditNoteIdEquals(creditNoteId));

        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<PaymentListItemResponse> result = paymentService.findAll(spec, pageable);

        ApiMeta meta = ApiMeta.builder()
                .total(result.getTotalElements())
                .page(result.getNumber())
                .size(result.getSize())
                .build();

        return ResponseEntity.ok(ApiResponse.success(result.getContent(), meta));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
mvn install -DskipTests -pl cia-api -am
mvn -pl cia-api failsafe:integration-test -Dit.test=PaymentListControllerIT -DskipUnitTests=true
```

Expected: PASS — 5 tests.

- [ ] **Step 5: Run the full cia-api failsafe baseline check**

```bash
mvn -pl cia-api verify -DskipUnitTests=true
```

Expected: 274 + 12 = 286 ITs passing, 0 failures, 0 errors, 1 intentional skip (existing benchmark).

- [ ] **Step 6: Commit**

```bash
git add cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/PaymentListController.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/PaymentListControllerIT.java
git commit -m "$(cat <<'EOF'
feat(finance): Slice α / Task 10 — PaymentListController for GET /api/v1/payments

Mirror of Task 9 for Payment. Backend half of slice α is now complete; failsafe
baseline runs at 274 + 12 = 286 ITs with 0 failures.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: Frontend api-client — schemas + functions

**Files:**
- Modify: `cia-frontend/packages/api-client/src/modules/finance.ts`

- [ ] **Step 1: Add ReceiptListItemResponseSchema + PaymentListItemResponseSchema**

Insert into `finance.ts` after the existing `ReceiptDtoSchema` block (around line 92):

```typescript
// ── Flat list responses (F7 slice α — GET /api/v1/receipts + /api/v1/payments) ───────

export const ReceiptListItemResponseSchema = z.object({
  id:               z.string(),
  reference:        z.string(),
  debitNoteId:      z.string(),
  debitNoteNumber:  z.string(),
  policyNumber:     z.string().nullable(),
  customerName:     z.string().nullable(),
  amount:           z.number(),
  paymentMethod:    z.string(),
  paymentDate:      z.string().nullable(),
  status:           ReceiptStatusSchema,
  reversedAt:       z.string().nullable(),
  reversedBy:       z.string().nullable(),
  reversalReason:   z.string().nullable(),
  createdAt:        z.string(),
});
export type ReceiptListItemResponse = z.infer<typeof ReceiptListItemResponseSchema>;

export const PaymentListItemResponseSchema = z.object({
  id:                   z.string(),
  reference:            z.string(),
  creditNoteId:         z.string(),
  creditNoteNumber:     z.string(),
  beneficiaryType:      z.string().nullable(),
  beneficiaryReference: z.string().nullable(),
  amount:               z.number(),
  paymentMethod:        z.string(),
  paymentDate:          z.string().nullable(),
  status:               PaymentStatusSchema,
  reversedAt:           z.string().nullable(),
  reversedBy:           z.string().nullable(),
  reversalReason:       z.string().nullable(),
  createdAt:            z.string(),
});
export type PaymentListItemResponse = z.infer<typeof PaymentListItemResponseSchema>;

export interface ReceiptListFilters {
  status?:         'POSTED' | 'REVERSED';
  createdFrom?:    string;
  createdTo?:      string;
  paymentMethod?:  string;
  debitNoteId?:    string;
  page?:           number;
  size?:           number;
}

export interface PaymentListFilters {
  status?:         'POSTED' | 'REVERSED';
  createdFrom?:    string;
  createdTo?:      string;
  paymentMethod?:  string;
  creditNoteId?:   string;
  page?:           number;
  size?:           number;
}

export async function listReceipts(
  filters: ReceiptListFilters = {}
): Promise<{ data: ReceiptListItemResponse[]; meta: { total: number; page: number; size: number } }> {
  const params = new URLSearchParams();
  Object.entries(filters).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') params.set(k, String(v));
  });
  const res = await validatedGet(
    `/api/v1/receipts?${params}`,
    z.object({
      data: z.array(ReceiptListItemResponseSchema),
      meta: z.object({ total: z.number(), page: z.number(), size: z.number() }),
    }),
  );
  return res;
}

export async function listPayments(
  filters: PaymentListFilters = {}
): Promise<{ data: PaymentListItemResponse[]; meta: { total: number; page: number; size: number } }> {
  const params = new URLSearchParams();
  Object.entries(filters).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') params.set(k, String(v));
  });
  const res = await validatedGet(
    `/api/v1/payments?${params}`,
    z.object({
      data: z.array(PaymentListItemResponseSchema),
      meta: z.object({ total: z.number(), page: z.number(), size: z.number() }),
    }),
  );
  return res;
}
```

> **Note on `validatedGet`:** read the existing `finance.ts` imports to confirm the helper name and signature. If `validatedGet` is not the project's name (it might be `validatedFetch` or similar), match the project's convention.

- [ ] **Step 2: Typecheck**

```bash
cd /Users/razormvp/CoreInsurance/cia-frontend
pnpm --filter @cia/api-client typecheck
```

Expected: exit 0, no errors.

- [ ] **Step 3: DTO drift check**

```bash
node cia-frontend/scripts/check-dto-drift.mjs
```

Expected: exit 0 — backend `ReceiptListItemResponse` + `PaymentListItemResponse` match the new zod schemas. If drift is reported, fix the schema field names to match the backend record field names exactly.

- [ ] **Step 4: Commit**

```bash
git add cia-frontend/packages/api-client/src/modules/finance.ts
git commit -m "$(cat <<'EOF'
feat(frontend): Slice α / Task 11 — api-client schemas + listReceipts/listPayments

Adds ReceiptListItemResponseSchema + PaymentListItemResponseSchema (zod) and the
listReceipts/listPayments fetchers used by the new flat-list pages. DTO-drift
script passes — schemas mirror the backend projection records exactly.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: useReceipts + usePayments hooks

**Files:**
- Create: `cia-frontend/apps/back-office/src/modules/finance/hooks/useReceipts.ts`
- Create: `cia-frontend/apps/back-office/src/modules/finance/hooks/usePayments.ts`

- [ ] **Step 1: Create useReceipts.ts**

```typescript
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  listReceipts,
  type ReceiptListFilters,
  type ReceiptListItemResponse,
} from '@cia/api-client/modules/finance';
import { apiClient } from '@cia/api-client';

export function useReceiptList(filters: ReceiptListFilters) {
  return useQuery({
    queryKey: ['finance', 'receipts', filters],
    queryFn: () => listReceipts(filters),
    staleTime: 60_000,
  });
}

export interface ReverseReceiptArgs {
  dnId:     string;
  receiptId: string;
  reason:   string;
}

export function useReverseReceipt() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ dnId, receiptId, reason }: ReverseReceiptArgs) => {
      await apiClient.post(
        `/api/v1/debit-notes/${dnId}/receipts/${receiptId}/reverse`,
        { reversalReason: reason },
      );
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['finance', 'receipts'] });
      queryClient.invalidateQueries({ queryKey: ['finance', 'debit-notes'] });
    },
  });
}
```

- [ ] **Step 2: Create usePayments.ts**

```typescript
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  listPayments,
  type PaymentListFilters,
  type PaymentListItemResponse,
} from '@cia/api-client/modules/finance';
import { apiClient } from '@cia/api-client';

export function usePaymentList(filters: PaymentListFilters) {
  return useQuery({
    queryKey: ['finance', 'payments', filters],
    queryFn: () => listPayments(filters),
    staleTime: 60_000,
  });
}

export interface ReversePaymentArgs {
  cnId:      string;
  paymentId: string;
  reason:    string;
}

export function useReversePayment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ cnId, paymentId, reason }: ReversePaymentArgs) => {
      await apiClient.post(
        `/api/v1/credit-notes/${cnId}/payments/${paymentId}/reverse`,
        { reversalReason: reason },
      );
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['finance', 'payments'] });
      queryClient.invalidateQueries({ queryKey: ['finance', 'credit-notes'] });
    },
  });
}
```

- [ ] **Step 3: Typecheck**

```bash
pnpm --filter @cia/back-office typecheck
```

Expected: exit 0.

- [ ] **Step 4: Commit**

```bash
git add cia-frontend/apps/back-office/src/modules/finance/hooks/useReceipts.ts \
        cia-frontend/apps/back-office/src/modules/finance/hooks/usePayments.ts
git commit -m "$(cat <<'EOF'
feat(frontend): Slice α / Task 12 — useReceipts + usePayments hooks

React Query hooks for the new flat-list pages + Reverse mutation. Query keys
under ['finance', 'receipts', ...] / ['finance', 'payments', ...] match the
existing ReverseTransactionDialog invalidation expectations.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: ReceiptsListSection + restore Receivables sub-tab

**Files:**
- Create: `cia-frontend/apps/back-office/src/modules/finance/pages/receivables/ReceiptsListSection.tsx`
- Modify: `cia-frontend/apps/back-office/src/modules/finance/pages/receivables/ReceivablesTab.tsx`

- [ ] **Step 1: Create ReceiptsListSection.tsx**

```tsx
import { useState } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '@cia/ui/components/card';
import { DataTable, type ColumnDef } from '@cia/ui/components/data-table';
import { Badge } from '@cia/ui/components/badge';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@cia/ui/components/select';
import { Input } from '@cia/ui/components/input';
import { Button } from '@cia/ui/components/button';
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@cia/ui/components/dropdown-menu';
import { MoreHorizontal } from 'lucide-react';
import { useReceiptList } from '../../hooks/useReceipts';
import { ReverseTransactionDialog, type ReverseTarget } from '../ReverseTransactionDialog';
import type { ReceiptListItemResponse } from '@cia/api-client/modules/finance';

const naira = new Intl.NumberFormat('en-NG', { style: 'currency', currency: 'NGN' });

const columns: ColumnDef<ReceiptListItemResponse>[] = [
  { accessorKey: 'reference', header: 'Reference' },
  { accessorKey: 'debitNoteNumber', header: 'Debit Note' },
  { accessorKey: 'customerName', header: 'Customer' },
  {
    accessorKey: 'amount',
    header: 'Amount',
    cell: ({ row }) => <span className="font-medium tabular-nums">{naira.format(row.original.amount)}</span>,
  },
  { accessorKey: 'paymentMethod', header: 'Method' },
  { accessorKey: 'paymentDate', header: 'Date' },
  {
    accessorKey: 'status',
    header: 'Status',
    cell: ({ row }) => {
      const r = row.original;
      const variant = r.status === 'POSTED' ? 'default' : 'secondary';
      return (
        <div className="flex flex-col gap-0.5">
          <Badge variant={variant}>{r.status}</Badge>
          {r.status === 'REVERSED' && r.reversedAt && (
            <span className="text-xs text-muted-foreground">
              Reversed {new Date(r.reversedAt).toLocaleString()} by {r.reversedBy ?? 'unknown'}
              {r.reversalReason ? ` — ${r.reversalReason}` : ''}
            </span>
          )}
        </div>
      );
    },
  },
];

export function ReceiptsListSection() {
  const [status, setStatus] = useState<'POSTED' | 'REVERSED' | undefined>(undefined);
  const [page, setPage] = useState(0);
  const { data, isLoading } = useReceiptList({ status, page, size: 20 });
  const [reverseTarget, setReverseTarget] = useState<ReverseTarget | null>(null);

  const rows = data?.data ?? [];

  const rowActions: ColumnDef<ReceiptListItemResponse> = {
    id: 'actions',
    cell: ({ row }) => {
      const r = row.original;
      if (r.status !== 'POSTED') return null;
      return (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon" className="h-8 w-8">
              <MoreHorizontal className="h-4 w-4" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuItem
              onClick={() => setReverseTarget({
                type:      'RECEIPT',
                id:        r.id,
                parentId:  r.debitNoteId,
                reference: r.reference,
                linkedRef: r.debitNoteNumber,
                amount:    r.amount,
                method:    r.paymentMethod,
                date:      r.paymentDate ?? '',
              })}
            >
              Reverse
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      );
    },
  };

  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle>Receipts</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-wrap items-center gap-3">
            <Select value={status ?? 'ALL'} onValueChange={(v) => setStatus(v === 'ALL' ? undefined : (v as 'POSTED' | 'REVERSED'))}>
              <SelectTrigger className="w-40">
                <SelectValue placeholder="Status" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">All statuses</SelectItem>
                <SelectItem value="POSTED">Posted</SelectItem>
                <SelectItem value="REVERSED">Reversed</SelectItem>
              </SelectContent>
            </Select>
          </div>

          <DataTable
            columns={[...columns, rowActions]}
            data={rows}
            isLoading={isLoading}
          />

          {data && (
            <div className="flex items-center justify-between text-sm text-muted-foreground">
              <span>{rows.length} of {data.meta.total} receipts</span>
              <div className="flex gap-2">
                <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
                  Previous
                </Button>
                <Button variant="outline" size="sm" disabled={rows.length < 20} onClick={() => setPage((p) => p + 1)}>
                  Next
                </Button>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      <ReverseTransactionDialog target={reverseTarget} onClose={() => setReverseTarget(null)} />
    </>
  );
}
```

> **Note on DataTable shape:** if the project's `@cia/ui` DataTable uses a different prop name than `data` or `columns`, adjust accordingly — read one existing page that already consumes DataTable for the exact convention (e.g. `apps/back-office/src/modules/finance/pages/receivables/ReceivablesTab.tsx`).

- [ ] **Step 2: Modify ReceivablesTab.tsx to wrap in Tabs**

Inside `ReceivablesTab.tsx`, identify the existing main render section (the `<DataTable>` displaying debit notes). Wrap it in shadcn `<Tabs>` with two `<TabsTrigger>` buttons:

```tsx
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@cia/ui/components/tabs';
import { ReceiptsListSection } from './ReceiptsListSection';

// inside the component's return, wrap the existing markup like:
return (
  <Tabs defaultValue="debit-notes">
    <TabsList>
      <TabsTrigger value="debit-notes">Debit Notes</TabsTrigger>
      <TabsTrigger value="receipts">Receipts</TabsTrigger>
    </TabsList>
    <TabsContent value="debit-notes" className="mt-4">
      {/* existing DataTable + sheets + dialog content goes here */}
    </TabsContent>
    <TabsContent value="receipts" className="mt-4">
      <ReceiptsListSection />
    </TabsContent>
  </Tabs>
);
```

Preserve all existing state, hooks, and JSX in the `value="debit-notes"` tab — do not delete anything.

- [ ] **Step 3: Typecheck**

```bash
pnpm --filter @cia/back-office typecheck
```

Expected: exit 0.

- [ ] **Step 4: Commit**

```bash
git add cia-frontend/apps/back-office/src/modules/finance/pages/receivables/ReceiptsListSection.tsx \
        cia-frontend/apps/back-office/src/modules/finance/pages/receivables/ReceivablesTab.tsx
git commit -m "$(cat <<'EOF'
feat(frontend): Slice α / Task 13 — Receipts sub-tab restored under Receivables

New ReceiptsListSection renders the flat receipts table with status filter,
pagination, reversal-audit badges, and a Reverse row action that opens the
existing ReverseTransactionDialog. ReceivablesTab now wraps the existing
debit-notes table in a Tabs container; default tab is Debit Notes for
behavioural compatibility.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 14: PaymentsListSection + restore Payables sub-tab

**Files:**
- Create: `cia-frontend/apps/back-office/src/modules/finance/pages/payables/PaymentsListSection.tsx`
- Modify: `cia-frontend/apps/back-office/src/modules/finance/pages/payables/PayablesTab.tsx`

- [ ] **Step 1: Create PaymentsListSection.tsx**

```tsx
import { useState } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '@cia/ui/components/card';
import { DataTable, type ColumnDef } from '@cia/ui/components/data-table';
import { Badge } from '@cia/ui/components/badge';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@cia/ui/components/select';
import { Button } from '@cia/ui/components/button';
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@cia/ui/components/dropdown-menu';
import { MoreHorizontal } from 'lucide-react';
import { usePaymentList } from '../../hooks/usePayments';
import { ReverseTransactionDialog, type ReverseTarget } from '../ReverseTransactionDialog';
import type { PaymentListItemResponse } from '@cia/api-client/modules/finance';

const naira = new Intl.NumberFormat('en-NG', { style: 'currency', currency: 'NGN' });

const beneficiaryLabel = (p: PaymentListItemResponse): string => {
  if (!p.beneficiaryType || !p.beneficiaryReference) return '—';
  const pretty: Record<string, string> = {
    CLAIM_DV:           'Claim DV',
    COMMISSION:         'Commission',
    FAC_OUTWARD:        'FAC Outward',
    ENDORSEMENT_REFUND: 'Endorsement Refund',
  };
  return `${pretty[p.beneficiaryType] ?? p.beneficiaryType} ${p.beneficiaryReference}`;
};

const columns: ColumnDef<PaymentListItemResponse>[] = [
  { accessorKey: 'reference', header: 'Reference' },
  { accessorKey: 'creditNoteNumber', header: 'Credit Note' },
  {
    id: 'beneficiary',
    header: 'Beneficiary',
    cell: ({ row }) => beneficiaryLabel(row.original),
  },
  {
    accessorKey: 'amount',
    header: 'Amount',
    cell: ({ row }) => <span className="font-medium tabular-nums">{naira.format(row.original.amount)}</span>,
  },
  { accessorKey: 'paymentMethod', header: 'Method' },
  { accessorKey: 'paymentDate', header: 'Date' },
  {
    accessorKey: 'status',
    header: 'Status',
    cell: ({ row }) => {
      const p = row.original;
      const variant = p.status === 'POSTED' ? 'default' : 'secondary';
      return (
        <div className="flex flex-col gap-0.5">
          <Badge variant={variant}>{p.status}</Badge>
          {p.status === 'REVERSED' && p.reversedAt && (
            <span className="text-xs text-muted-foreground">
              Reversed {new Date(p.reversedAt).toLocaleString()} by {p.reversedBy ?? 'unknown'}
              {p.reversalReason ? ` — ${p.reversalReason}` : ''}
            </span>
          )}
        </div>
      );
    },
  },
];

export function PaymentsListSection() {
  const [status, setStatus] = useState<'POSTED' | 'REVERSED' | undefined>(undefined);
  const [page, setPage] = useState(0);
  const { data, isLoading } = usePaymentList({ status, page, size: 20 });
  const [reverseTarget, setReverseTarget] = useState<ReverseTarget | null>(null);

  const rows = data?.data ?? [];

  const rowActions: ColumnDef<PaymentListItemResponse> = {
    id: 'actions',
    cell: ({ row }) => {
      const p = row.original;
      if (p.status !== 'POSTED') return null;
      return (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon" className="h-8 w-8">
              <MoreHorizontal className="h-4 w-4" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuItem
              onClick={() => setReverseTarget({
                type:      'PAYMENT',
                id:        p.id,
                parentId:  p.creditNoteId,
                reference: p.reference,
                linkedRef: p.creditNoteNumber,
                amount:    p.amount,
                method:    p.paymentMethod,
                date:      p.paymentDate ?? '',
              })}
            >
              Reverse
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      );
    },
  };

  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle>Payments</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-wrap items-center gap-3">
            <Select value={status ?? 'ALL'} onValueChange={(v) => setStatus(v === 'ALL' ? undefined : (v as 'POSTED' | 'REVERSED'))}>
              <SelectTrigger className="w-40">
                <SelectValue placeholder="Status" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">All statuses</SelectItem>
                <SelectItem value="POSTED">Posted</SelectItem>
                <SelectItem value="REVERSED">Reversed</SelectItem>
              </SelectContent>
            </Select>
          </div>

          <DataTable
            columns={[...columns, rowActions]}
            data={rows}
            isLoading={isLoading}
          />

          {data && (
            <div className="flex items-center justify-between text-sm text-muted-foreground">
              <span>{rows.length} of {data.meta.total} payments</span>
              <div className="flex gap-2">
                <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
                  Previous
                </Button>
                <Button variant="outline" size="sm" disabled={rows.length < 20} onClick={() => setPage((p) => p + 1)}>
                  Next
                </Button>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      <ReverseTransactionDialog target={reverseTarget} onClose={() => setReverseTarget(null)} />
    </>
  );
}
```

- [ ] **Step 2: Modify PayablesTab.tsx to wrap in Tabs**

```tsx
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@cia/ui/components/tabs';
import { PaymentsListSection } from './PaymentsListSection';

// inside the component's return:
return (
  <Tabs defaultValue="credit-notes">
    <TabsList>
      <TabsTrigger value="credit-notes">Credit Notes</TabsTrigger>
      <TabsTrigger value="payments">Payments</TabsTrigger>
    </TabsList>
    <TabsContent value="credit-notes" className="mt-4">
      {/* existing markup */}
    </TabsContent>
    <TabsContent value="payments" className="mt-4">
      <PaymentsListSection />
    </TabsContent>
  </Tabs>
);
```

- [ ] **Step 3: Typecheck**

```bash
pnpm --filter @cia/back-office typecheck
```

Expected: exit 0.

- [ ] **Step 4: Commit**

```bash
git add cia-frontend/apps/back-office/src/modules/finance/pages/payables/PaymentsListSection.tsx \
        cia-frontend/apps/back-office/src/modules/finance/pages/payables/PayablesTab.tsx
git commit -m "$(cat <<'EOF'
feat(frontend): Slice α / Task 14 — Payments sub-tab restored under Payables

Mirror of Task 13 for payments. PaymentsListSection renders the flat payments
table with beneficiary labels resolved from CreditNote.sourceType +
sourceReference. PayablesTab wraps the existing credit-notes table in a Tabs
container with credit-notes as the default tab.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 15: Nested receipts list inside DebitNoteDetailDialog

**Files:**
- Modify: `cia-frontend/apps/back-office/src/modules/finance/pages/receivables/DebitNoteDetailDialog.tsx`

- [ ] **Step 1: Add nested receipts query + section**

Inside `DebitNoteDetailDialog.tsx`, add a new section below the existing debit-note summary block. Pass the DN id to a hook that fetches receipts scoped to this DN:

```tsx
import { useReceiptList } from '../../hooks/useReceipts';
import { ReverseTransactionDialog, type ReverseTarget } from '../ReverseTransactionDialog';
import { useState } from 'react';
import { Button } from '@cia/ui/components/button';

// Inside the component (after the existing debit-note summary block):
const { data: receipts } = useReceiptList({ debitNoteId: dn.id });
const [reverseTarget, setReverseTarget] = useState<ReverseTarget | null>(null);
const rows = receipts?.data ?? [];

// Markup after the existing summary:
{rows.length > 0 && (
  <section className="mt-6 border-t pt-4">
    <h3 className="text-sm font-semibold mb-2">Receipts ({rows.length})</h3>
    <ul className="space-y-2">
      {rows.map((r) => (
        <li key={r.id} className="flex items-center justify-between rounded border p-2">
          <div className="flex flex-col">
            <span className="font-medium">{r.reference}</span>
            <span className="text-xs text-muted-foreground">
              {r.amount.toLocaleString('en-NG', { style: 'currency', currency: 'NGN' })} · {r.paymentMethod} · {r.paymentDate}
            </span>
            {r.status === 'REVERSED' && r.reversedAt && (
              <span className="text-xs text-muted-foreground">
                Reversed {new Date(r.reversedAt).toLocaleString()} by {r.reversedBy ?? 'unknown'}
                {r.reversalReason ? ` — ${r.reversalReason}` : ''}
              </span>
            )}
          </div>
          {r.status === 'POSTED' && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => setReverseTarget({
                type:      'RECEIPT',
                id:        r.id,
                parentId:  r.debitNoteId,
                reference: r.reference,
                linkedRef: r.debitNoteNumber,
                amount:    r.amount,
                method:    r.paymentMethod,
                date:      r.paymentDate ?? '',
              })}
            >
              Reverse
            </Button>
          )}
        </li>
      ))}
    </ul>
    <ReverseTransactionDialog target={reverseTarget} onClose={() => setReverseTarget(null)} />
  </section>
)}
```

- [ ] **Step 2: Typecheck**

```bash
pnpm --filter @cia/back-office typecheck
```

Expected: exit 0.

- [ ] **Step 3: Commit**

```bash
git add cia-frontend/apps/back-office/src/modules/finance/pages/receivables/DebitNoteDetailDialog.tsx
git commit -m "$(cat <<'EOF'
feat(frontend): Slice α / Task 15 — Nested receipts list inside DebitNoteDetailDialog

When the operator opens a debit-note detail dialog, they now see every receipt
posted against that DN with status badge, reversal-audit columns, and per-row
Reverse button (wired into the existing ReverseTransactionDialog).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 16: Nested payments list inside CreditNoteDetailDialog

**Files:**
- Modify: `cia-frontend/apps/back-office/src/modules/finance/pages/payables/CreditNoteDetailDialog.tsx`

- [ ] **Step 1: Add nested payments query + section**

Mirror of Task 15 for payments:

```tsx
import { usePaymentList } from '../../hooks/usePayments';
import { ReverseTransactionDialog, type ReverseTarget } from '../ReverseTransactionDialog';
import { useState } from 'react';
import { Button } from '@cia/ui/components/button';

// Inside the component:
const { data: payments } = usePaymentList({ creditNoteId: cn.id });
const [reverseTarget, setReverseTarget] = useState<ReverseTarget | null>(null);
const rows = payments?.data ?? [];

// Markup after the credit-note summary block:
{rows.length > 0 && (
  <section className="mt-6 border-t pt-4">
    <h3 className="text-sm font-semibold mb-2">Payments ({rows.length})</h3>
    <ul className="space-y-2">
      {rows.map((p) => (
        <li key={p.id} className="flex items-center justify-between rounded border p-2">
          <div className="flex flex-col">
            <span className="font-medium">{p.reference}</span>
            <span className="text-xs text-muted-foreground">
              {p.amount.toLocaleString('en-NG', { style: 'currency', currency: 'NGN' })} · {p.paymentMethod} · {p.paymentDate}
            </span>
            {p.status === 'REVERSED' && p.reversedAt && (
              <span className="text-xs text-muted-foreground">
                Reversed {new Date(p.reversedAt).toLocaleString()} by {p.reversedBy ?? 'unknown'}
                {p.reversalReason ? ` — ${p.reversalReason}` : ''}
              </span>
            )}
          </div>
          {p.status === 'POSTED' && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => setReverseTarget({
                type:      'PAYMENT',
                id:        p.id,
                parentId:  p.creditNoteId,
                reference: p.reference,
                linkedRef: p.creditNoteNumber,
                amount:    p.amount,
                method:    p.paymentMethod,
                date:      p.paymentDate ?? '',
              })}
            >
              Reverse
            </Button>
          )}
        </li>
      ))}
    </ul>
    <ReverseTransactionDialog target={reverseTarget} onClose={() => setReverseTarget(null)} />
  </section>
)}
```

- [ ] **Step 2: Typecheck**

```bash
pnpm --filter @cia/back-office typecheck
```

Expected: exit 0.

- [ ] **Step 3: Commit**

```bash
git add cia-frontend/apps/back-office/src/modules/finance/pages/payables/CreditNoteDetailDialog.tsx
git commit -m "$(cat <<'EOF'
feat(frontend): Slice α / Task 16 — Nested payments list inside CreditNoteDetailDialog

Mirror of Task 15 for credit-notes. ReverseTransactionDialog is now wired into
all four surfaces: ReceiptsListSection, PaymentsListSection, DebitNoteDetailDialog,
CreditNoteDetailDialog. The file is no longer dead code.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 17: Documentation — CLAUDE.md + internal-api.json

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs-site/static/internal-api.json`

- [ ] **Step 1: Update CLAUDE.md Module 8 row**

Read the existing Module Summary table in `CLAUDE.md`. Update the Module 8 Finance row description to reflect: "Receipts and Payments sub-tabs restored; flat list endpoints; nested receipts/payments in DN/CN detail dialogs; reversal-audit columns surfaced; reverse() now writes AuditLog."

- [ ] **Step 2: Update Frontend Build Queue Build 6**

Update the Receipts and Payments sub-page descriptions in the "Build 6 — Module 8: Finance" section of CLAUDE.md to note the restored sub-tabs.

- [ ] **Step 3: Add a Development Conventions bullet**

Append to the Development Conventions section of CLAUDE.md:

```markdown
- **Flat list endpoints for child aggregates** — when a child aggregate (e.g. Receipt → DebitNote, Payment → CreditNote) needs a cross-parent list view, create a separate `*ListController` rather than adding the flat endpoint to the existing nested parent-scoped controller. Keeps responsibility lines clean; the nested controller stays narrowly about the parent context. F7 slice α introduced `ReceiptListController` (`/api/v1/receipts`) and `PaymentListController` (`/api/v1/payments`) alongside the existing `ReceiptController` and `PaymentController`. Filtering via `JpaSpecificationExecutor<T>` + a static `*Specs` factory class; projection DTOs (`*ListItemResponse`) carry parent + grandparent context to avoid N+1 on row rendering.
```

- [ ] **Step 4: Update `docs-site/static/internal-api.json`**

Add `/receipts` (GET) and `/payments` (GET) entries with their full OpenAPI 3.1 path definitions. Reference the existing `/debit-notes/{dnId}/receipts` entry as a template — copy its `parameters` style and substitute the query params from `ReceiptListController` / `PaymentListController`. The schema component should reference `ReceiptListItemResponse` / `PaymentListItemResponse`.

- [ ] **Step 5: Run the audit script from /cia gate 9b**

```bash
cd /Users/razormvp/CoreInsurance
python3 -c "
import json
with open('docs-site/static/internal-api.json') as f:
    spec = json.load(f)
documented = set(spec.get('paths', {}).keys())
assert '/receipts' in documented, '/receipts missing'
assert '/payments' in documented, '/payments missing'
print('OK')
"
```

Expected: prints `OK`.

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md docs-site/static/internal-api.json
git commit -m "$(cat <<'EOF'
docs: Slice α — Module 8 Finance feature update + internal-api.json paths

CLAUDE.md Module 8 description updated to reflect the restored Receipts and
Payments sub-tabs plus nested-in-detail-dialog surfaces. Frontend Build Queue
Build 6 sub-page list refreshed. New Development Conventions bullet documents
the flat-list-controller pattern. internal-api.json gains /receipts and
/payments GET entries per the /cia gate 9b audit.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 18: Session log + final verification + push

**Files:**
- Append: `cia-log.md`

- [ ] **Step 1: Append a Session entry to `cia-log.md`**

Insert a new session entry above the most recent existing one (which is currently Session 124 dated 2026-05-25). Use the next session number. The entry must cover: stated goal of the slice, files touched (use the table from this plan), test counts (16 new tests: 12 ITs + 4 unit), backlog reconciliation (F7 still open until γ ships; no new rows added by this slice).

Template:

```markdown
## 2026-05-25 — Session NNN (`main`): F7 slice α — receipt + payment visibility + reversal audit

[Stated goal text from this plan's "Goal" line]

### What landed

[Three paragraphs covering: backend (audit-fix + flat-list controllers + specs + projections), frontend (sub-tabs + nested-in-dialog + ReverseTransactionDialog wired into all 4 surfaces), docs (CLAUDE.md + internal-api.json)]

### Files touched

[Backend table from this plan's File Structure section]
[Frontend table from this plan's File Structure section]
[Docs table]

### Test coverage

- ~16 new tests (12 ITs + 4 spec unit tests if added)
- Failsafe baseline now ~286 (was 274) ITs, 0 failures, 0 errors, 1 intentional benchmark skip
- pnpm typecheck @cia/back-office: 0 errors
- check-dto-drift.mjs: 0 errors
- check-api-wiring.sh: 0 errors

### Backlog reconciliation

- **Removed**: none. F7 stays open until slice γ ships.
- **Added**: none.

### Known follow-ups

- Slice β next: PDF generation + MinIO + download surfaces (V50).
- Slice γ after β: email transmission via Temporal (V51).
- Slice δ after γ: per-tenant email template override (V52).
```

- [ ] **Step 2: Final full-reactor verification**

```bash
cd /Users/razormvp/CoreInsurance/cia-backend
mvn install -DskipTests -pl cia-api -am
mvn -pl cia-api verify -DskipUnitTests=true

cd /Users/razormvp/CoreInsurance/cia-frontend
pnpm --filter @cia/back-office typecheck
node scripts/check-dto-drift.mjs
bash scripts/check-api-wiring.sh
```

Expected:
- `mvn verify` exit 0; failsafe summary `286 tests · 0 failures · 0 errors · 1 skipped`
- `pnpm typecheck`: 0 errors
- `check-dto-drift.mjs`: prints OK, exit 0
- `check-api-wiring.sh`: prints OK, exit 0

- [ ] **Step 3: Commit the log entry**

```bash
git add cia-log.md
git commit -m "$(cat <<'EOF'
docs(log): Session NNN — F7 slice α landed (visibility + reversal audit)

Closes the visibility half of F7. Failsafe baseline 286 ITs all green.
F7 backlog row stays open until slice γ (email) ships. No new backlog
rows surfaced by this slice.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: Push (only when user authorizes)**

Ask the user "push the N commits?" — only push after explicit confirmation.

```bash
git push origin main
```

Expected: `b55107c..NEWHEAD  main -> main` with N commits.

---

## Self-Review

### 1. Spec coverage check

| Spec requirement (from `2026-05-25-f7-receipt-payment-visibility-design.md` slice α section) | Task that implements it |
|---|---|
| `ReceiptListController.java` (new) | Task 9 |
| `PaymentListController.java` (new) | Task 10 |
| `ReceiptRepository.java` (add `JpaSpecificationExecutor`) | Task 5 |
| `PaymentRepository.java` (same) | Task 6 |
| `ReceiptSpecs.java` (new) | Task 5 |
| `PaymentSpecs.java` (new) | Task 6 |
| `ReceiptService.findAll(Specification, Pageable)` (new method) | Task 7 |
| `PaymentService.findAll(Specification, Pageable)` (new method) | Task 8 |
| Verify-and-fix `reverse()` audit log on Receipt | Tasks 1+2 |
| Verify-and-fix `reverse()` audit log on Payment | Tasks 3+4 |
| `ReceiptListItemResponse` projection DTO | Task 7 |
| `PaymentListItemResponse` projection DTO | Task 8 |
| `ReceiptListControllerIT` (5 tests) | Task 9 |
| `PaymentListControllerIT` (5 tests) | Task 10 |
| `ReceiptReverseAuditIT` (1 test) | Tasks 1+2 |
| `PaymentReverseAuditIT` (1 test) | Tasks 3+4 |
| Frontend api-client schemas + listReceipts/listPayments | Task 11 |
| `useReceipts.ts` / `usePayments.ts` hooks | Task 12 |
| `ReceiptsListSection.tsx` (new) | Task 13 |
| `PaymentsListSection.tsx` (new) | Task 14 |
| `ReceivablesTab.tsx` (wrap in Tabs) | Task 13 |
| `PayablesTab.tsx` (wrap in Tabs) | Task 14 |
| `DebitNoteDetailDialog.tsx` (nested receipts section) | Task 15 |
| `CreditNoteDetailDialog.tsx` (nested payments section) | Task 16 |
| `ReverseTransactionDialog.tsx` wired into all 4 surfaces | Tasks 13, 14, 15, 16 |
| Reversal audit columns shown everywhere | Tasks 13, 14, 15, 16 |
| CLAUDE.md Module 8 + Frontend Build Queue updates | Task 17 |
| internal-api.json `/receipts` + `/payments` | Task 17 |
| cia-log.md Session entry | Task 18 |

No gaps. Every spec α-requirement maps to a task.

### 2. Placeholder scan

- No "TBD", "TODO", "implement later" in any step.
- "Note on X" callouts reference specific files + line numbers for the executing agent to verify rather than imply work the plan punts on.
- Every code-changing step shows complete code.
- Every command step shows the exact command + expected output.

### 3. Type consistency check

- `ReceiptListItemResponse` (Java record, Task 7) and `ReceiptListItemResponseSchema` (zod schema, Task 11) share the same field names: `id`, `reference`, `debitNoteId`, `debitNoteNumber`, `policyNumber`, `customerName`, `amount`, `paymentMethod`, `paymentDate`, `status`, `reversedAt`, `reversedBy`, `reversalReason`, `createdAt`. DTO drift script (Task 11 Step 3) enforces.
- `PaymentListItemResponse` ditto for payment.
- `ReverseTarget` (frontend interface from existing `ReverseTransactionDialog.tsx`) is matched by Tasks 13, 14, 15, 16 — every site that builds a `ReverseTarget` uses identical field names: `type`, `id`, `parentId`, `reference`, `linkedRef`, `amount`, `method`, `date`.
- `ReverseSnapshot` is defined once in `ReceiptService` (Task 2) and separately in `PaymentService` (Task 4) — intentional since the records live in different files within the same package and don't need to be shared.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-05-25-f7-slice-alpha-visibility.md`.**

Two execution options:

1. **Subagent-Driven (recommended)** — Each task gets a fresh subagent with no prior conversation context; I review the work between tasks. Fast iteration, less context pollution, cleaner roll-back if a task goes wrong.
2. **Inline Execution** — Tasks run sequentially in this session via the `executing-plans` skill, with batched checkpoints for review.

Which approach?
