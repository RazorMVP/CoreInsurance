# F7-δ + R7 — Per-tenant notification template overrides — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let each tenant override the subject and body of the 4 transactional notification templates (receipt-email, payment-voucher-email, receipt-sms, payment-voucher-sms) via a logic-less Mustache template engine, with immediate save + audit + preview UX. Ship the SMS workflow + cancel-signal mechanics end-to-end against a logging-only provider stub.

**Architecture:** Multi-row `tenant_notification_template` table (V60) keyed on `(template_type, channel)`; permissive override model where `subject_template` and `body_template` are independently nullable and fall back to JAR defaults. Mustache engine renders both defaults and overrides; save-time variable allowlist validation prevents typos and SSTI. SMS infrastructure mirrors F7-γ's email shape (SPI + Temporal workflow + cancel signal + recipient resolver + REST endpoints).

**Tech Stack:** Spring Boot 3 + Java 21, PostgreSQL + Flyway (V60 + V61), Temporal (workflows on renamed `NOTIFICATIONS_QUEUE`), `mustache-java` 0.9.x (~80KB, no transitive deps), JUnit 5 + Testcontainers (cia-api), React + TanStack Query + Vite + Vitest (cia-frontend), shadcn/ui Sheet + textarea, hugeicons `Notification01Icon`.

**Spec:** `docs/superpowers/specs/2026-05-27-f7-delta-r7-tenant-notification-templates-design.md` (commit b55b08d).

**Migrations:** V60 (`tenant_notification_template`), V61 (SMS bookkeeping columns on receipts + payments).

**Estimated total: ~50 tasks across 15 phases. ~41 new cia-api ITs (baseline 358 → ~399). 3 new Vitest tests (count 2 → 5).**

---

## Phase 0 — Pre-work refactors (no behaviour change)

Three mechanical refactors that clear the field for the new code. None of these have natural failing-test TDD — the "test" is "the existing test suite still passes after each".

### Task 0.1: Refactor `EmailTemplateType` → `NotificationTemplateType` + add `NotificationChannel`

**Files:**
- Create: `cia-backend/cia-common/src/main/java/com/nubeero/cia/common/notification/NotificationTemplateType.java`
- Create: `cia-backend/cia-common/src/main/java/com/nubeero/cia/common/notification/NotificationChannel.java`
- Delete: `cia-backend/cia-common/src/main/java/com/nubeero/cia/common/email/EmailTemplateType.java`
- Delete: `cia-backend/cia-common/src/main/java/com/nubeero/cia/common/email/` (empty after deletion)
- Modify: every site that imports `com.nubeero.cia.common.email.EmailTemplateType` (~6 sites including `EmailBodyComposer`, `SendReceiptEmailWorkflowImpl`, `SendPaymentVoucherEmailWorkflowImpl`, 2 ITs)

- [ ] **Step 1: Create the new types**

```java
// cia-common/src/main/java/com/nubeero/cia/common/notification/NotificationTemplateType.java
package com.nubeero.cia.common.notification;

public enum NotificationTemplateType {
    RECEIPT,
    PAYMENT_VOUCHER
}
```

```java
// cia-common/src/main/java/com/nubeero/cia/common/notification/NotificationChannel.java
package com.nubeero.cia.common.notification;

public enum NotificationChannel {
    EMAIL,
    SMS
}
```

- [ ] **Step 2: Find all references to the old enum**

Run: `grep -rln 'EmailTemplateType' cia-backend/`
Expected: ~6–8 files listed.

- [ ] **Step 3: Update each site**

For each match, replace `EmailTemplateType.RECEIPT_EMAIL` → `NotificationTemplateType.RECEIPT` and `EmailTemplateType.PAYMENT_VOUCHER_EMAIL` → `NotificationTemplateType.PAYMENT_VOUCHER`. Update the import line from `com.nubeero.cia.common.email.EmailTemplateType` to `com.nubeero.cia.common.notification.NotificationTemplateType`.

Sites known from the explorer report:
- `cia-finance/src/main/java/com/nubeero/cia/finance/email/EmailBodyComposer.java` (lines 30–34, 36–40)
- `cia-finance/src/main/java/com/nubeero/cia/finance/email/SendReceiptEmailWorkflowImpl.java`
- `cia-finance/src/main/java/com/nubeero/cia/finance/email/SendPaymentVoucherEmailWorkflowImpl.java`
- Any IT under `cia-api/src/test/java/com/nubeero/cia/api/finance/email/` that references the constants.

Verify with another grep after editing: `grep -rln 'EmailTemplateType' cia-backend/` → should return zero matches.

- [ ] **Step 4: Delete the old enum file + empty package**

Run:
```bash
rm cia-backend/cia-common/src/main/java/com/nubeero/cia/common/email/EmailTemplateType.java
rmdir cia-backend/cia-common/src/main/java/com/nubeero/cia/common/email/ 2>/dev/null || true
```

- [ ] **Step 5: Verify compile**

Run: `mvn -pl cia-common,cia-finance compile -DskipTests -am`
Expected: `BUILD SUCCESS`. If any file still references `EmailTemplateType`, fix the import there.

- [ ] **Step 6: Run the existing email workflow ITs to verify behaviour preserved**

Run: `mvn -pl cia-api verify -Dit.test='SendReceiptEmailWorkflowIT,SendPaymentVoucherEmailWorkflowIT' -DskipUnitTests=true`
Expected: All existing email workflow tests pass with the new enum names.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(common): Task 0.1 — EmailTemplateType → NotificationTemplateType + NotificationChannel

Channel-neutral enum names ahead of F7-δ + R7 (per-tenant notification
template overrides). Pure rename — no behaviour change.

- New: com.nubeero.cia.common.notification.NotificationTemplateType
       { RECEIPT, PAYMENT_VOUCHER } (was RECEIPT_EMAIL / PAYMENT_VOUCHER_EMAIL)
- New: com.nubeero.cia.common.notification.NotificationChannel
       { EMAIL, SMS }
- Deleted: com.nubeero.cia.common.email package + EmailTemplateType

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 0.2: Rename `EmailPreflightException` → `NotificationPreflightException`

**Files:**
- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/email/EmailPreflightException.java` (rename + move)
- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/notification/NotificationPreflightException.java`
- Delete: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/email/EmailPreflightException.java`
- Modify: every site that imports or throws it (`ReceiptService`, `PaymentService`, controllers, ITs)

- [ ] **Step 1: Create the new class with the old behaviour**

```java
// cia-finance/src/main/java/com/nubeero/cia/finance/notification/NotificationPreflightException.java
package com.nubeero.cia.finance.notification;

import com.nubeero.cia.common.exception.CiaException;

public class NotificationPreflightException extends CiaException {
    public NotificationPreflightException(String errorCode, String message) {
        super(errorCode, message);
    }
}
```

The shape must match `EmailPreflightException` exactly — it already extends `CiaException` and is routed via `GlobalExceptionHandler.handleCiaException` to HTTP 422 + `{errorCode, message}`. The rename preserves that behaviour.

- [ ] **Step 2: Find all references**

Run: `grep -rln 'EmailPreflightException' cia-backend/`
Expected: ~10–15 sites (ReceiptService, PaymentService, controllers, ITs).

- [ ] **Step 3: Replace each reference**

For each match, replace `EmailPreflightException` → `NotificationPreflightException` and the import from `com.nubeero.cia.finance.email.EmailPreflightException` → `com.nubeero.cia.finance.notification.NotificationPreflightException`.

- [ ] **Step 4: Delete the old exception file**

```bash
rm cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/email/EmailPreflightException.java
```

- [ ] **Step 5: Verify compile + ITs still pass**

```bash
mvn -pl cia-finance compile -DskipTests
mvn -pl cia-api verify -Dit.test='ReceiptControllerEmailIT,PaymentControllerEmailIT,CancelEmailWorkflowIT,CancelEmailControllerIT' -DskipUnitTests=true
```

Expected: BUILD SUCCESS; all 4 ITs pass (existing F7-γ + F11 cancel ITs).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(finance): Task 0.2 — EmailPreflightException → NotificationPreflightException

Channel-neutral exception name ahead of F7-δ + R7. The new class will
also carry the new SMS preflight error codes
(RECEIPT_RECIPIENT_PHONE_UNRESOLVED etc.) introduced in later phases.

Routed via GlobalExceptionHandler.handleCiaException as before — pure
rename, no behaviour change.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 0.3: Rename `TemporalQueues.EMAIL_QUEUE` → `NOTIFICATIONS_QUEUE` + `EmailWorkerConfig` → `NotificationsWorkerConfig`

**Files:**
- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/temporal/TemporalQueues.java` (or wherever the constant lives — grep to find it)
- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/email/EmailWorkerConfig.java` → rename to `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/notification/NotificationsWorkerConfig.java`
- Modify: every site that references the old queue name (~5–7 sites)

- [ ] **Step 1: Find the queue constant location**

Run: `grep -rln 'EMAIL_QUEUE' cia-backend/`
Expected: One file with the constant definition + several use sites. Note the package path of `TemporalQueues`.

- [ ] **Step 2: Rename the constant + change the value**

In the file containing `EMAIL_QUEUE`:

```java
// BEFORE
public static final String EMAIL_QUEUE = "email-queue";

// AFTER
public static final String NOTIFICATIONS_QUEUE = "notifications-queue";
```

- [ ] **Step 3: Update all use sites**

For each remaining match from Step 1, replace `TemporalQueues.EMAIL_QUEUE` → `TemporalQueues.NOTIFICATIONS_QUEUE`.

Sites known to reference the queue: workflow `@WorkflowMethod` declarations, `WorkflowOptions.newBuilder().setTaskQueue(...)` calls, `EmailWorkerConfig.workerFactory.newWorker(...)` registration, `PdfDownloadLogRetentionWorkflow` schedule call (F11).

- [ ] **Step 4: Rename `EmailWorkerConfig` → `NotificationsWorkerConfig`**

```bash
mv cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/email/EmailWorkerConfig.java \
   cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/notification/NotificationsWorkerConfig.java
```

Update the class declaration line `public class EmailWorkerConfig` → `public class NotificationsWorkerConfig`. Update the `package` line to `package com.nubeero.cia.finance.notification;`. Update all `@Bean` method names if any reference "email" (e.g., `emailWorker` → `notificationsWorker`).

- [ ] **Step 5: Verify compile + Temporal ITs still pass**

```bash
mvn -pl cia-finance compile -DskipTests
mvn -pl cia-api verify -Dit.test='SendReceiptEmailWorkflowIT,SendPaymentVoucherEmailWorkflowIT,CancelEmailWorkflowIT,PdfDownloadLogRetentionWorkflowIT' -DskipUnitTests=true
```

Expected: BUILD SUCCESS + all 4 ITs pass. Workflow IDs are unique per workflow type, so the queue value change is mechanically safe.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(finance): Task 0.3 — EMAIL_QUEUE → NOTIFICATIONS_QUEUE + EmailWorkerConfig → NotificationsWorkerConfig

The queue already hosts non-email workflows (PdfDownloadLogRetention
from F11) and will gain SMS workflows in F7-δ + R7. Naming aligned with
the actual purpose: transactional notification dispatch.

Queue value changed from "email-queue" → "notifications-queue".
Workflow IDs stay unique per workflow type so this is a mechanically
safe rename.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 1 — V60 migration + entity foundations

### Task 1.1: V60 migration — `tenant_notification_template` table

**Files:**
- Create: `cia-backend/cia-api/src/main/resources/db/migration/V60__create_tenant_notification_template.sql`

- [ ] **Step 1: Write the migration**

```sql
-- cia-api/src/main/resources/db/migration/V60__create_tenant_notification_template.sql
-- F7-δ + R7 — Per-tenant notification template overrides.
-- Multi-row table keyed on (template_type, channel). Permissive override
-- model: subject_template and body_template are independently nullable
-- and fall back to JAR defaults when NULL. SMS rows must have NULL
-- subject_template (subjects don't apply to SMS).

CREATE TABLE tenant_notification_template (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    template_type    VARCHAR(40)  NOT NULL,   -- RECEIPT | PAYMENT_VOUCHER (extensible)
    channel          VARCHAR(20)  NOT NULL,   -- EMAIL | SMS
    subject_template TEXT,                    -- Mustache; NULL = use default; always NULL for SMS
    body_template    TEXT,                    -- Mustache; NULL = use default
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(255),
    updated_by       VARCHAR(255),
    deleted_at       TIMESTAMPTZ,

    CONSTRAINT ck_tnt_at_least_one_override
        CHECK (subject_template IS NOT NULL OR body_template IS NOT NULL),
    CONSTRAINT ck_tnt_sms_no_subject
        CHECK (channel = 'EMAIL' OR subject_template IS NULL)
);

-- One active override per (template_type, channel) per tenant
-- (schema-per-tenant; no tenant_id column needed).
CREATE UNIQUE INDEX uq_tenant_notification_template_type_channel
    ON tenant_notification_template (template_type, channel)
    WHERE deleted_at IS NULL;

-- For listing / lookup by type only
CREATE INDEX idx_tnt_type ON tenant_notification_template (template_type)
    WHERE deleted_at IS NULL;
```

- [ ] **Step 2: Update the Flyway IT target to 60**

Run: `grep -rln 'flyway.target\|flywayTarget' cia-backend/ | head -5`

Update the value from the current `59` (set in F11 T7) to `60` in the relevant config (usually `application-test.yml` or the parent pom property).

- [ ] **Step 3: Run a single existing IT to verify the migration applies cleanly**

Run: `mvn -pl cia-api verify -Dit.test='PdfDownloadLogControllerIT' -DskipUnitTests=true`
Expected: Test passes; in the Testcontainers DB the new table exists. The IT itself doesn't touch the new table — it just proves the migration applied successfully and didn't break the existing schema.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(setup): Task 1.1 — V60 tenant_notification_template table

Phase 1.1 of F7-δ + R7. Creates the multi-row override table keyed on
(template_type, channel), with partial UNIQUE on the active row, plus
the ck_tnt_at_least_one_override + ck_tnt_sms_no_subject CHECK
constraints from the spec.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 1.2: `TenantNotificationTemplate` JPA entity + repository

**Files:**
- Create: `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/notification/TenantNotificationTemplate.java`
- Create: `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/notification/TenantNotificationTemplateRepository.java`

- [ ] **Step 1: Write the entity**

```java
// cia-setup/src/main/java/com/nubeero/cia/setup/notification/TenantNotificationTemplate.java
package com.nubeero.cia.setup.notification;

import com.nubeero.cia.common.entity.BaseEntity;
import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

@Entity
@Table(name = "tenant_notification_template")
@SQLDelete(sql = "UPDATE tenant_notification_template SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantNotificationTemplate extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "template_type", nullable = false, length = 40)
    private NotificationTemplateType templateType;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(name = "subject_template", columnDefinition = "TEXT")
    private String subjectTemplate;

    @Column(name = "body_template", columnDefinition = "TEXT")
    private String bodyTemplate;
}
```

- [ ] **Step 2: Write the repository**

```java
// cia-setup/src/main/java/com/nubeero/cia/setup/notification/TenantNotificationTemplateRepository.java
package com.nubeero.cia.setup.notification;

import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantNotificationTemplateRepository
        extends JpaRepository<TenantNotificationTemplate, UUID> {

    Optional<TenantNotificationTemplate> findByTemplateTypeAndChannel(
            NotificationTemplateType templateType, NotificationChannel channel);

    List<TenantNotificationTemplate> findAllByOrderByTemplateTypeAscChannelAsc();

    boolean existsByTemplateTypeAndChannel(
            NotificationTemplateType templateType, NotificationChannel channel);
}
```

- [ ] **Step 3: Verify compile**

Run: `mvn -pl cia-setup compile -DskipTests -am`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(setup): Task 1.2 — TenantNotificationTemplate entity + repository

JPA entity for the V60 table. Soft-delete via @SQLDelete + @Where
clause filtering deleted_at IS NULL — matches the rest of the setup
module convention.

Repository surface:
  - findByTemplateTypeAndChannel(type, channel)  → composer lookup
  - findAllByOrderByTemplateTypeAscChannelAsc()  → list endpoint
  - existsByTemplateTypeAndChannel(...)          → create-conflict check

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 1.3: Smoke test the entity against Testcontainers

**Files:**
- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/setup/notification/TenantNotificationTemplateRepositoryIT.java`

- [ ] **Step 1: Write a focused repository IT**

```java
// cia-api/src/test/java/com/nubeero/cia/api/setup/notification/TenantNotificationTemplateRepositoryIT.java
package com.nubeero.cia.api.setup.notification;

import com.nubeero.cia.api.support.AbstractDataJpaIT;
import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import com.nubeero.cia.setup.notification.TenantNotificationTemplate;
import com.nubeero.cia.setup.notification.TenantNotificationTemplateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantNotificationTemplateRepositoryIT extends AbstractDataJpaIT {

    @Autowired
    TenantNotificationTemplateRepository repository;

    @Test
    void persistsAndRetrievesByTypeAndChannel() {
        repository.save(TenantNotificationTemplate.builder()
                .templateType(NotificationTemplateType.RECEIPT)
                .channel(NotificationChannel.EMAIL)
                .subjectTemplate("Receipt {{receiptNumber}} — paid")
                .bodyTemplate("Hi {{customerName}}")
                .build());

        var found = repository.findByTemplateTypeAndChannel(
                NotificationTemplateType.RECEIPT, NotificationChannel.EMAIL);

        assertThat(found).isPresent();
        assertThat(found.get().getSubjectTemplate()).contains("{{receiptNumber}}");
    }

    @Test
    void rejectsRowWithBothFieldsNull_atLeastOneOverride() {
        assertThatThrownBy(() -> {
            repository.saveAndFlush(TenantNotificationTemplate.builder()
                    .templateType(NotificationTemplateType.RECEIPT)
                    .channel(NotificationChannel.EMAIL)
                    .subjectTemplate(null)
                    .bodyTemplate(null)
                    .build());
        }).isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("ck_tnt_at_least_one_override");
    }

    @Test
    void rejectsSmsRowWithSubject_ckSmsNoSubject() {
        assertThatThrownBy(() -> {
            repository.saveAndFlush(TenantNotificationTemplate.builder()
                    .templateType(NotificationTemplateType.RECEIPT)
                    .channel(NotificationChannel.SMS)
                    .subjectTemplate("This subject should be rejected")
                    .bodyTemplate("Body")
                    .build());
        }).isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("ck_tnt_sms_no_subject");
    }
}
```

Note: `AbstractDataJpaIT` is the existing slice-test base in `cia-api/src/test/java/com/nubeero/cia/api/support/` — it sets up Testcontainers + `@DataJpaTest` + `@Import(CiaCommonAutoConfiguration.class)` per the CLAUDE.md testing-requirements section. If the class is named differently in the codebase, swap the import accordingly (grep for `extends Abstract.*DataJpaIT` to find existing usage).

- [ ] **Step 2: Run the IT — verify it passes**

```bash
mvn -pl cia-api verify -Dit.test='TenantNotificationTemplateRepositoryIT' -DskipUnitTests=true
```
Expected: 3/3 tests pass.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
test(api): Task 1.3 — TenantNotificationTemplateRepositoryIT (3 tests)

Smoke-tests the V60 schema + entity mapping against a real
PostgreSQL via Testcontainers:
  - persist + retrieve round-trip by (type, channel)
  - ck_tnt_at_least_one_override CHECK constraint fires when both
    subject + body are null
  - ck_tnt_sms_no_subject CHECK constraint fires for SMS row with
    a non-null subject

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 2 — Mustache renderer + allowlist + JAR default templates

### Task 2.1: Add `mustache-java` dependency to `cia-documents`

**Files:**
- Modify: `cia-backend/cia-documents/pom.xml`

- [ ] **Step 1: Add the dependency to cia-documents/pom.xml**

```xml
<!-- inside <dependencies> -->
<dependency>
    <groupId>com.github.spullara.mustache.java</groupId>
    <artifactId>compiler</artifactId>
    <version>0.9.14</version>
</dependency>
```

- [ ] **Step 2: Verify the dependency resolves**

```bash
mvn -pl cia-documents dependency:resolve -DskipTests
```
Expected: BUILD SUCCESS; output includes `com.github.spullara.mustache.java:compiler:jar:0.9.14`.

- [ ] **Step 3: Commit**

```bash
git add cia-backend/cia-documents/pom.xml
git commit -m "$(cat <<'EOF'
build(documents): Task 2.1 — add mustache-java compiler dependency

mustache-java 0.9.14 (~80KB, no transitive deps) — the logic-less
template engine for tenant-editable notification templates.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2.2: `MustacheTemplateRenderer` + unit test

**Files:**
- Create: `cia-backend/cia-documents/src/main/java/com/nubeero/cia/documents/notification/MustacheTemplateRenderer.java`
- Create: `cia-backend/cia-documents/src/test/java/com/nubeero/cia/documents/notification/MustacheTemplateRendererTest.java`

- [ ] **Step 1: Write the failing test**

```java
// cia-documents/src/test/java/com/nubeero/cia/documents/notification/MustacheTemplateRendererTest.java
package com.nubeero.cia.documents.notification;

import com.nubeero.cia.common.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MustacheTemplateRendererTest {

    private final MustacheTemplateRenderer renderer = new MustacheTemplateRenderer();

    @Test
    void substitutesSimpleVariables() {
        String out = renderer.render(
                "Hi {{customerName}}, your receipt {{receiptNumber}} is ready.",
                Map.of("customerName", "Acme Ltd", "receiptNumber", "REC-001"));
        assertThat(out).isEqualTo("Hi Acme Ltd, your receipt REC-001 is ready.");
    }

    @Test
    void rendersConditionalSectionWhenTruthy() {
        String out = renderer.render(
                "Hello{{#vip}} VIP{{/vip}} customer",
                Map.of("vip", true));
        assertThat(out).isEqualTo("Hello VIP customer");
    }

    @Test
    void omitsConditionalSectionWhenFalsy() {
        String out = renderer.render(
                "Hello{{#vip}} VIP{{/vip}} customer",
                Map.of("vip", false));
        assertThat(out).isEqualTo("Hello customer");
    }

    @Test
    void htmlEscapesByDefault() {
        String out = renderer.render(
                "Note: {{note}}",
                Map.of("note", "<script>alert('xss')</script>"));
        assertThat(out).isEqualTo("Note: &lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;");
    }

    @Test
    void triplebracesEscapeUnescaped() {
        String out = renderer.render(
                "Raw: {{{html}}}",
                Map.of("html", "<b>bold</b>"));
        assertThat(out).isEqualTo("Raw: <b>bold</b>");
    }

    @Test
    void filterByAllowlist_keepsAllowedKeys() {
        Map<String, Object> filtered = renderer.filterByAllowlist(
                Map.of("a", 1, "b", 2, "c", 3),
                Set.of("a", "c"));
        assertThat(filtered).containsOnlyKeys("a", "c");
    }

    @Test
    void filterByAllowlist_dropsDisallowedKeys() {
        Map<String, Object> filtered = renderer.filterByAllowlist(
                Map.of("safe", "ok", "leaky", "secret"),
                Set.of("safe"));
        assertThat(filtered).doesNotContainKey("leaky");
    }

    @Test
    void extractVariableNames_returnsValueAndSectionNames() {
        Set<String> names = renderer.extractVariableNames(
                "Hi {{name}}, your {{#hasReceipt}}receipt {{number}}{{/hasReceipt}} is here.");
        assertThat(names).containsExactlyInAnyOrder("name", "hasReceipt", "number");
    }

    @Test
    void extractVariableNames_throwsOnPartialReference() {
        assertThatThrownBy(() ->
                renderer.extractVariableNames("Hi {{>some-partial}}"))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("UNKNOWN_TEMPLATE_VARIABLE")
            .hasMessageContaining("partial");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -pl cia-documents test -Dtest='MustacheTemplateRendererTest'
```
Expected: FAIL with "MustacheTemplateRenderer not found".

- [ ] **Step 3: Write the implementation**

```java
// cia-documents/src/main/java/com/nubeero/cia/documents/notification/MustacheTemplateRenderer.java
package com.nubeero.cia.documents.notification;

import com.github.mustachejava.Code;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import com.github.mustachejava.codes.IterableCode;
import com.github.mustachejava.codes.PartialCode;
import com.github.mustachejava.codes.ValueCode;
import com.nubeero.cia.common.exception.BusinessRuleException;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Component
public class MustacheTemplateRenderer {

    private final MustacheFactory factory = new DefaultMustacheFactory();

    /**
     * Render a Mustache template against the supplied merge fields.
     * HTML-escapes {{var}} by default; {{{var}}} passes through unescaped.
     */
    public String render(String template, Map<String, Object> fields) {
        Mustache compiled = factory.compile(new StringReader(template), "inline");
        StringWriter writer = new StringWriter();
        compiled.execute(writer, fields);
        return writer.toString();
    }

    /**
     * Return a copy of the input map containing only keys that appear in the allowlist.
     * Defence-in-depth: even if a caller passes extra merge fields, they can't leak
     * into the rendered template.
     */
    public Map<String, Object> filterByAllowlist(Map<String, Object> fields, Set<String> allowlist) {
        Map<String, Object> filtered = new HashMap<>();
        for (var entry : fields.entrySet()) {
            if (allowlist.contains(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    /**
     * Parse the template and return the set of variable names it references.
     * Walks the AST extracting ValueCode + IterableCode (section) nodes.
     * Throws BusinessRuleException with code UNKNOWN_TEMPLATE_VARIABLE if the
     * template references a partial ({{>name}}) — partials are not allowed.
     */
    public Set<String> extractVariableNames(String template) {
        Mustache compiled = factory.compile(new StringReader(template), "inline");
        Set<String> names = new HashSet<>();
        walkCodes(compiled.getCodes(), names);
        return names;
    }

    private void walkCodes(Code[] codes, Set<String> names) {
        if (codes == null) return;
        for (Code code : codes) {
            if (code instanceof PartialCode) {
                throw new BusinessRuleException(
                        "UNKNOWN_TEMPLATE_VARIABLE",
                        "Mustache partials ({{>name}}) are not allowed in templates");
            }
            if (code instanceof ValueCode vc) {
                names.add(vc.getName());
            } else if (code instanceof IterableCode ic) {
                names.add(ic.getName());
                walkCodes(ic.getCodes(), names);
            }
        }
    }
}
```

Note: `ValueCode.getName()` and `IterableCode.getName()` are public on mustache-java 0.9.x. If the field is `protected` in the version pinned, use reflection or upgrade the dependency.

- [ ] **Step 4: Run the test — verify it passes**

```bash
mvn -pl cia-documents test -Dtest='MustacheTemplateRendererTest'
```
Expected: 9/9 tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(documents): Task 2.2 — MustacheTemplateRenderer + 9 unit tests

Logic-less template engine for the F7-δ + R7 notification framework.
Three methods:
  - render(template, fields) — Mustache execution with default HTML escaping
  - filterByAllowlist(fields, allowed) — drops disallowed keys (defence-in-depth)
  - extractVariableNames(template) — walks AST for save-time validation;
    throws UNKNOWN_TEMPLATE_VARIABLE on partial references ({{>name}})

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2.3: `NotificationVariables` (allowlist) + unit test

**Files:**
- Create: `cia-backend/cia-common/src/main/java/com/nubeero/cia/common/notification/NotificationVariables.java`
- Create: `cia-backend/cia-common/src/test/java/com/nubeero/cia/common/notification/NotificationVariablesTest.java`

- [ ] **Step 1: Write the failing test**

```java
// cia-common/src/test/java/com/nubeero/cia/common/notification/NotificationVariablesTest.java
package com.nubeero.cia.common.notification;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationVariablesTest {

    @Test
    void receiptEmailAllowlist() {
        var allowlist = NotificationVariables.allowlistFor(
                NotificationTemplateType.RECEIPT, NotificationChannel.EMAIL);
        assertThat(allowlist).containsExactlyInAnyOrder(
                "customerName", "amount", "paymentDate",
                "receiptNumber", "debitNoteNumber", "companyName");
    }

    @Test
    void paymentVoucherEmailAllowlist() {
        var allowlist = NotificationVariables.allowlistFor(
                NotificationTemplateType.PAYMENT_VOUCHER, NotificationChannel.EMAIL);
        assertThat(allowlist).containsExactlyInAnyOrder(
                "beneficiaryName", "amount", "paymentDate",
                "paymentNumber", "creditNoteNumber", "companyName");
    }

    @Test
    void receiptSmsAllowlist() {
        var allowlist = NotificationVariables.allowlistFor(
                NotificationTemplateType.RECEIPT, NotificationChannel.SMS);
        assertThat(allowlist).containsExactlyInAnyOrder(
                "customerName", "amount", "receiptNumber");
    }

    @Test
    void paymentVoucherSmsAllowlist() {
        var allowlist = NotificationVariables.allowlistFor(
                NotificationTemplateType.PAYMENT_VOUCHER, NotificationChannel.SMS);
        assertThat(allowlist).containsExactlyInAnyOrder(
                "beneficiaryName", "amount", "paymentNumber");
    }

    @Test
    void unknownCombinationThrows() {
        // No allowlist for SMS subject because SMS has no subjects, etc.
        // The current 4 combinations cover all valid pairs; any future expansion
        // must add an ALLOWLIST entry. Defensive coverage.
        for (NotificationTemplateType type : NotificationTemplateType.values()) {
            for (NotificationChannel channel : NotificationChannel.values()) {
                // Each defined combination must work
                assertThat(NotificationVariables.allowlistFor(type, channel))
                        .as("allowlist exists for " + type + "/" + channel)
                        .isNotNull();
            }
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -pl cia-common test -Dtest='NotificationVariablesTest'
```
Expected: FAIL with "NotificationVariables not found".

- [ ] **Step 3: Write the implementation**

```java
// cia-common/src/main/java/com/nubeero/cia/common/notification/NotificationVariables.java
package com.nubeero.cia.common.notification;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The variable allowlist for each (template_type, channel) combination.
 * Tenant-edited templates may only reference variables in this set; the
 * validation gate fires both at save time (NotificationTemplateService)
 * and at render time (MustacheTemplateRenderer with throw-on-missing).
 *
 * Adding a new template type later requires adding an enum value + an
 * ALLOWLIST entry here + a JAR default template file under
 * cia-documents/src/main/resources/templates/notifications/{channel}/.
 */
public final class NotificationVariables {

    private NotificationVariables() {}

    public record Key(NotificationTemplateType type, NotificationChannel channel) {}

    private static final Map<Key, Set<String>> ALLOWLIST = Map.of(
            new Key(NotificationTemplateType.RECEIPT, NotificationChannel.EMAIL),
                Set.of("customerName", "amount", "paymentDate",
                       "receiptNumber", "debitNoteNumber", "companyName"),

            new Key(NotificationTemplateType.PAYMENT_VOUCHER, NotificationChannel.EMAIL),
                Set.of("beneficiaryName", "amount", "paymentDate",
                       "paymentNumber", "creditNoteNumber", "companyName"),

            new Key(NotificationTemplateType.RECEIPT, NotificationChannel.SMS),
                Set.of("customerName", "amount", "receiptNumber"),

            new Key(NotificationTemplateType.PAYMENT_VOUCHER, NotificationChannel.SMS),
                Set.of("beneficiaryName", "amount", "paymentNumber")
    );

    public static Set<String> allowlistFor(NotificationTemplateType type, NotificationChannel channel) {
        return Optional.ofNullable(ALLOWLIST.get(new Key(type, channel)))
                .orElseThrow(() -> new IllegalStateException(
                        "No allowlist registered for " + type + "/" + channel));
    }
}
```

- [ ] **Step 4: Run the test — verify it passes**

```bash
mvn -pl cia-common test -Dtest='NotificationVariablesTest'
```
Expected: 5/5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(common): Task 2.3 — NotificationVariables allowlist + 5 unit tests

The per-(type, channel) variable allowlist. Drives save-time
validation in NotificationTemplateService and render-time merge-field
filtering in NotificationComposer.

Adding a new template type later = add enum value + ALLOWLIST entry
+ drop JAR default templates. No schema migration.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2.4: Migrate JAR default templates to Mustache + add SMS templates

**Files:**
- Delete: `cia-backend/cia-documents/src/main/resources/templates/email/receipt-default.html`
- Delete: `cia-backend/cia-documents/src/main/resources/templates/email/payment-voucher-default.html`
- Create: `cia-backend/cia-documents/src/main/resources/templates/notifications/email/receipt.subject`
- Create: `cia-backend/cia-documents/src/main/resources/templates/notifications/email/receipt.html`
- Create: `cia-backend/cia-documents/src/main/resources/templates/notifications/email/payment-voucher.subject`
- Create: `cia-backend/cia-documents/src/main/resources/templates/notifications/email/payment-voucher.html`
- Create: `cia-backend/cia-documents/src/main/resources/templates/notifications/sms/receipt.txt`
- Create: `cia-backend/cia-documents/src/main/resources/templates/notifications/sms/payment-voucher.txt`

- [ ] **Step 1: Read the existing email templates so the migration preserves content**

```bash
cat cia-backend/cia-documents/src/main/resources/templates/email/receipt-default.html
cat cia-backend/cia-documents/src/main/resources/templates/email/payment-voucher-default.html
```

- [ ] **Step 2: Create the migrated + new files**

Create `notifications/email/receipt.subject` (one line, no trailing newline):
```
Receipt {{receiptNumber}} — payment received
```

Create `notifications/email/receipt.html` — preserve the structure of the old `receipt-default.html` but swap `${var}` → `{{var}}`. If the old file used Thymeleaf-specific attributes (`th:text`), strip them to plain `{{var}}`. Body shape:

```html
<!DOCTYPE html>
<html>
<body style="font-family: Helvetica, Arial, sans-serif; line-height: 1.5;">
  <p>Hi {{customerName}},</p>
  <p>We received your payment of {{amount}} on {{paymentDate}}.</p>
  <p>
    <strong>Receipt:</strong> {{receiptNumber}}<br/>
    <strong>Debit note:</strong> {{debitNoteNumber}}
  </p>
  <p>Regards,<br/>{{companyName}}</p>
</body>
</html>
```

Create `notifications/email/payment-voucher.subject`:
```
Payment voucher {{paymentNumber}}
```

Create `notifications/email/payment-voucher.html`:
```html
<!DOCTYPE html>
<html>
<body style="font-family: Helvetica, Arial, sans-serif; line-height: 1.5;">
  <p>Hi {{beneficiaryName}},</p>
  <p>Your payment of {{amount}} has been processed on {{paymentDate}}.</p>
  <p>
    <strong>Payment voucher:</strong> {{paymentNumber}}<br/>
    <strong>Credit note:</strong> {{creditNoteNumber}}
  </p>
  <p>Regards,<br/>{{companyName}}</p>
</body>
</html>
```

Create `notifications/sms/receipt.txt` (no trailing newline, single GSM7 segment at typical values):
```
Hi {{customerName}}, we received your payment of {{amount}}. Receipt: {{receiptNumber}}.
```

Create `notifications/sms/payment-voucher.txt`:
```
Hi {{beneficiaryName}}, payment of {{amount}} processed. Voucher: {{paymentNumber}}.
```

- [ ] **Step 3: Delete the old Thymeleaf templates**

```bash
rm cia-backend/cia-documents/src/main/resources/templates/email/receipt-default.html
rm cia-backend/cia-documents/src/main/resources/templates/email/payment-voucher-default.html
rmdir cia-backend/cia-documents/src/main/resources/templates/email/ 2>/dev/null || true
```

- [ ] **Step 4: Verify the file tree**

```bash
find cia-backend/cia-documents/src/main/resources/templates/notifications -type f | sort
```
Expected:
```
cia-backend/cia-documents/src/main/resources/templates/notifications/email/payment-voucher.html
cia-backend/cia-documents/src/main/resources/templates/notifications/email/payment-voucher.subject
cia-backend/cia-documents/src/main/resources/templates/notifications/email/receipt.html
cia-backend/cia-documents/src/main/resources/templates/notifications/email/receipt.subject
cia-backend/cia-documents/src/main/resources/templates/notifications/sms/payment-voucher.txt
cia-backend/cia-documents/src/main/resources/templates/notifications/sms/receipt.txt
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(documents): Task 2.4 — migrate JAR templates to Mustache + add SMS

- Email: \${var} → {{var}}; renamed to drop -default suffix
       templates/notifications/email/{receipt,payment-voucher}.{subject,html}
- SMS:  new defaults, single-segment at typical values
       templates/notifications/sms/{receipt,payment-voucher}.txt
- Deleted: templates/email/ (old location)

Subject lines now templated (previously hardcoded in EmailBodyComposer);
the renderer treats them as full Mustache templates so tenants can put
{{customerName}} or {{receiptNumber}} in the subject.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2.5: `DefaultTemplateLoader` + unit test

**Files:**
- Create: `cia-backend/cia-documents/src/main/java/com/nubeero/cia/documents/notification/DefaultTemplateLoader.java`
- Create: `cia-backend/cia-documents/src/test/java/com/nubeero/cia/documents/notification/DefaultTemplateLoaderTest.java`

- [ ] **Step 1: Write the failing test**

```java
// cia-documents/src/test/java/com/nubeero/cia/documents/notification/DefaultTemplateLoaderTest.java
package com.nubeero.cia.documents.notification;

import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultTemplateLoaderTest {

    private final DefaultTemplateLoader loader = new DefaultTemplateLoader();

    @Test
    void loadsReceiptEmailSubject() {
        String subject = loader.subjectFor(NotificationTemplateType.RECEIPT, NotificationChannel.EMAIL);
        assertThat(subject).contains("{{receiptNumber}}");
    }

    @Test
    void loadsReceiptEmailBody() {
        String body = loader.bodyFor(NotificationTemplateType.RECEIPT, NotificationChannel.EMAIL);
        assertThat(body).contains("{{customerName}}").contains("<html");
    }

    @Test
    void loadsPaymentVoucherEmailSubject() {
        String subject = loader.subjectFor(NotificationTemplateType.PAYMENT_VOUCHER, NotificationChannel.EMAIL);
        assertThat(subject).contains("{{paymentNumber}}");
    }

    @Test
    void loadsReceiptSmsBody() {
        String body = loader.bodyFor(NotificationTemplateType.RECEIPT, NotificationChannel.SMS);
        assertThat(body).contains("{{customerName}}").contains("{{receiptNumber}}");
    }

    @Test
    void smsHasNoSubject() {
        String subject = loader.subjectFor(NotificationTemplateType.RECEIPT, NotificationChannel.SMS);
        assertThat(subject).isNull();
    }

    @Test
    void missingFileThrows() {
        // If a JAR file is somehow missing, fail loudly rather than silently
        // serving an empty template (which would render a blank email).
        DefaultTemplateLoader brokenLoader = new DefaultTemplateLoader() {
            @Override
            protected String classpathPath(NotificationTemplateType t, NotificationChannel c, String ext) {
                return "/nonexistent/template.txt";
            }
        };
        assertThatThrownBy(() -> brokenLoader.bodyFor(
                NotificationTemplateType.RECEIPT, NotificationChannel.EMAIL))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nonexistent");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -pl cia-documents test -Dtest='DefaultTemplateLoaderTest'
```
Expected: FAIL with "DefaultTemplateLoader not found".

- [ ] **Step 3: Write the implementation**

```java
// cia-documents/src/main/java/com/nubeero/cia/documents/notification/DefaultTemplateLoader.java
package com.nubeero.cia.documents.notification;

import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads the JAR-default Mustache template content for a given
 * (template_type, channel) pair. The renderer falls back to these when
 * the per-tenant override row is missing for the corresponding field.
 *
 * Files live under templates/notifications/{channel-lc}/{type-kebab-case}.{subject|html|txt}
 *  - notifications/email/receipt.subject (single line)
 *  - notifications/email/receipt.html
 *  - notifications/email/payment-voucher.subject
 *  - notifications/email/payment-voucher.html
 *  - notifications/sms/receipt.txt
 *  - notifications/sms/payment-voucher.txt
 *
 * SMS has no subject; subjectFor(..., SMS) returns null by contract.
 */
@Component
public class DefaultTemplateLoader {

    public String subjectFor(NotificationTemplateType type, NotificationChannel channel) {
        if (channel == NotificationChannel.SMS) {
            return null;
        }
        return readResource(classpathPath(type, channel, "subject")).trim();
    }

    public String bodyFor(NotificationTemplateType type, NotificationChannel channel) {
        String ext = (channel == NotificationChannel.EMAIL) ? "html" : "txt";
        return readResource(classpathPath(type, channel, ext));
    }

    protected String classpathPath(NotificationTemplateType type, NotificationChannel channel, String ext) {
        String typeKebab = type.name().toLowerCase().replace('_', '-');
        String channelLc = channel.name().toLowerCase();
        return "/templates/notifications/" + channelLc + "/" + typeKebab + "." + ext;
    }

    private String readResource(String classpathPath) {
        try (InputStream in = getClass().getResourceAsStream(classpathPath)) {
            if (in == null) {
                throw new IllegalStateException("Missing template resource: " + classpathPath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read template resource: " + classpathPath, e);
        }
    }
}
```

- [ ] **Step 4: Run the test — verify it passes**

```bash
mvn -pl cia-documents test -Dtest='DefaultTemplateLoaderTest'
```
Expected: 6/6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(documents): Task 2.5 — DefaultTemplateLoader + 6 unit tests

Loads JAR-default Mustache template content from
/templates/notifications/{channel-lc}/{type-kebab}.{subject|html|txt}.
Returns null for SMS subject by contract (SMS has no subject).
Throws IllegalStateException if a resource file is missing — fail
loudly rather than silently serving an empty template.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 3 — `NotificationComposer` (replaces `EmailBodyComposer`)

### Task 3.1: `NotificationComposer` + IT

**Files:**
- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/notification/NotificationComposer.java`
- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/notification/ComposedMessage.java`
- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/notification/NotificationComposerIT.java`
- Delete (later, in Task 3.2): `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/email/EmailBodyComposer.java`

- [ ] **Step 1: Write the `ComposedMessage` value type**

```java
// cia-finance/src/main/java/com/nubeero/cia/finance/notification/ComposedMessage.java
package com.nubeero.cia.finance.notification;

/**
 * Result of NotificationComposer.compose(). {@code subject} is null for SMS.
 */
public record ComposedMessage(String subject, String body) {}
```

- [ ] **Step 2: Write the failing IT**

```java
// cia-api/src/test/java/com/nubeero/cia/api/finance/notification/NotificationComposerIT.java
package com.nubeero.cia.api.finance.notification;

import com.nubeero.cia.api.support.AbstractApiIT;
import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import com.nubeero.cia.finance.notification.ComposedMessage;
import com.nubeero.cia.finance.notification.NotificationComposer;
import com.nubeero.cia.setup.notification.TenantNotificationTemplate;
import com.nubeero.cia.setup.notification.TenantNotificationTemplateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationComposerIT extends AbstractApiIT {

    @Autowired NotificationComposer composer;
    @Autowired TenantNotificationTemplateRepository templateRepo;

    @Test
    void noOverride_usesJarDefault() {
        ComposedMessage msg = composer.compose(
                NotificationTemplateType.RECEIPT, NotificationChannel.EMAIL,
                Map.of("customerName", "Acme Ltd",
                       "amount", "₦450,000.00",
                       "paymentDate", "2026-05-27",
                       "receiptNumber", "REC-001",
                       "debitNoteNumber", "DN-001",
                       "companyName", "Tenant Insurance Plc"));
        assertThat(msg.subject()).isEqualTo("Receipt REC-001 — payment received");
        assertThat(msg.body()).contains("Hi Acme Ltd");
    }

    @Test
    void fullOverride_usesDbValues() {
        templateRepo.save(TenantNotificationTemplate.builder()
                .templateType(NotificationTemplateType.RECEIPT)
                .channel(NotificationChannel.EMAIL)
                .subjectTemplate("Custom subject for {{receiptNumber}}")
                .bodyTemplate("Custom body for {{customerName}}")
                .build());

        ComposedMessage msg = composer.compose(
                NotificationTemplateType.RECEIPT, NotificationChannel.EMAIL,
                Map.of("customerName", "Acme Ltd",
                       "amount", "₦100",
                       "paymentDate", "2026-05-27",
                       "receiptNumber", "REC-001",
                       "debitNoteNumber", "DN-001",
                       "companyName", "Tenant Insurance Plc"));
        assertThat(msg.subject()).isEqualTo("Custom subject for REC-001");
        assertThat(msg.body()).isEqualTo("Custom body for Acme Ltd");
    }

    @Test
    void subjectOnlyOverride_subjectFromDb_bodyFromJar() {
        templateRepo.save(TenantNotificationTemplate.builder()
                .templateType(NotificationTemplateType.RECEIPT)
                .channel(NotificationChannel.EMAIL)
                .subjectTemplate("Just the subject {{receiptNumber}}")
                .bodyTemplate(null)
                .build());

        ComposedMessage msg = composer.compose(
                NotificationTemplateType.RECEIPT, NotificationChannel.EMAIL,
                Map.of("customerName", "Acme Ltd",
                       "amount", "₦100",
                       "paymentDate", "2026-05-27",
                       "receiptNumber", "REC-001",
                       "debitNoteNumber", "DN-001",
                       "companyName", "Tenant Insurance Plc"));
        assertThat(msg.subject()).isEqualTo("Just the subject REC-001");
        assertThat(msg.body()).contains("Hi Acme Ltd");  // default
    }

    @Test
    void bodyOnlyOverride_subjectFromJar_bodyFromDb() {
        templateRepo.save(TenantNotificationTemplate.builder()
                .templateType(NotificationTemplateType.RECEIPT)
                .channel(NotificationChannel.EMAIL)
                .subjectTemplate(null)
                .bodyTemplate("Body only {{customerName}}")
                .build());

        ComposedMessage msg = composer.compose(
                NotificationTemplateType.RECEIPT, NotificationChannel.EMAIL,
                Map.of("customerName", "Acme Ltd",
                       "amount", "₦100",
                       "paymentDate", "2026-05-27",
                       "receiptNumber", "REC-001",
                       "debitNoteNumber", "DN-001",
                       "companyName", "Tenant Insurance Plc"));
        assertThat(msg.subject()).isEqualTo("Receipt REC-001 — payment received");  // default
        assertThat(msg.body()).isEqualTo("Body only Acme Ltd");
    }

    @Test
    void smsChannel_subjectIsNull() {
        ComposedMessage msg = composer.compose(
                NotificationTemplateType.RECEIPT, NotificationChannel.SMS,
                Map.of("customerName", "Acme Ltd",
                       "amount", "₦100",
                       "receiptNumber", "REC-001"));
        assertThat(msg.subject()).isNull();
        assertThat(msg.body()).contains("Acme Ltd").contains("REC-001");
    }

    @Test
    void extraMergeFields_droppedByAllowlistFilter() {
        // Caller passes a "secret" field; it must not appear in rendered output.
        templateRepo.save(TenantNotificationTemplate.builder()
                .templateType(NotificationTemplateType.RECEIPT)
                .channel(NotificationChannel.SMS)
                .subjectTemplate(null)
                .bodyTemplate("Customer: {{customerName}}; Receipt: {{receiptNumber}}")
                .build());

        ComposedMessage msg = composer.compose(
                NotificationTemplateType.RECEIPT, NotificationChannel.SMS,
                Map.of("customerName", "Acme Ltd",
                       "amount", "₦100",
                       "receiptNumber", "REC-001",
                       "secretField", "should-not-appear"));
        assertThat(msg.body()).doesNotContain("should-not-appear");
    }

    @Test
    void mustacheConditionalSection_renders() {
        templateRepo.save(TenantNotificationTemplate.builder()
                .templateType(NotificationTemplateType.PAYMENT_VOUCHER)
                .channel(NotificationChannel.EMAIL)
                .subjectTemplate(null)
                .bodyTemplate("Hi{{#beneficiaryName}} {{beneficiaryName}}{{/beneficiaryName}}!")
                .build());

        ComposedMessage msg = composer.compose(
                NotificationTemplateType.PAYMENT_VOUCHER, NotificationChannel.EMAIL,
                Map.of("beneficiaryName", "Bob",
                       "amount", "₦100",
                       "paymentDate", "2026-05-27",
                       "paymentNumber", "PAY-001",
                       "creditNoteNumber", "CN-001",
                       "companyName", "Tenant Insurance Plc"));
        assertThat(msg.body()).isEqualTo("Hi Bob!");
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

```bash
mvn -pl cia-api verify -Dit.test='NotificationComposerIT' -DskipUnitTests=true
```
Expected: FAIL with "NotificationComposer not found".

- [ ] **Step 4: Write the implementation**

```java
// cia-finance/src/main/java/com/nubeero/cia/finance/notification/NotificationComposer.java
package com.nubeero.cia.finance.notification;

import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import com.nubeero.cia.common.notification.NotificationVariables;
import com.nubeero.cia.documents.notification.DefaultTemplateLoader;
import com.nubeero.cia.documents.notification.MustacheTemplateRenderer;
import com.nubeero.cia.setup.notification.TenantNotificationTemplate;
import com.nubeero.cia.setup.notification.TenantNotificationTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * Composes a (subject, body) message for a given notification by:
 *  1. Looking up a per-tenant override row (Optional)
 *  2. For each field (subject, body), using the override if present, else the JAR default
 *  3. Filtering the merge fields to the variable allowlist for the (type, channel)
 *  4. Rendering via Mustache
 *
 * Replaces the legacy EmailBodyComposer (which is deleted in the next task).
 */
@Component
@RequiredArgsConstructor
public class NotificationComposer {

    private final TenantNotificationTemplateRepository repo;
    private final MustacheTemplateRenderer renderer;
    private final DefaultTemplateLoader defaults;

    @Transactional(readOnly = true)
    public ComposedMessage compose(NotificationTemplateType type,
                                   NotificationChannel channel,
                                   Map<String, Object> mergeFields) {
        Optional<TenantNotificationTemplate> override = repo.findByTemplateTypeAndChannel(type, channel);

        String subjectTemplate = override.map(TenantNotificationTemplate::getSubjectTemplate)
                .filter(s -> s != null && !s.isBlank())
                .orElseGet(() -> defaults.subjectFor(type, channel));   // null for SMS

        String bodyTemplate = override.map(TenantNotificationTemplate::getBodyTemplate)
                .filter(s -> s != null && !s.isBlank())
                .orElseGet(() -> defaults.bodyFor(type, channel));

        var allowlist = NotificationVariables.allowlistFor(type, channel);
        Map<String, Object> filtered = renderer.filterByAllowlist(mergeFields, allowlist);

        String renderedSubject = (subjectTemplate == null) ? null : renderer.render(subjectTemplate, filtered);
        String renderedBody = renderer.render(bodyTemplate, filtered);

        return new ComposedMessage(renderedSubject, renderedBody);
    }
}
```

- [ ] **Step 5: Run the IT — verify it passes**

```bash
mvn -pl cia-api verify -Dit.test='NotificationComposerIT' -DskipUnitTests=true
```
Expected: 7/7 tests pass.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(finance): Task 3.1 — NotificationComposer + 7 ITs

Replaces the legacy EmailBodyComposer with a channel-aware composer
backed by the new DB-override → JAR-default fallback chain. Renders
subject + body via MustacheTemplateRenderer; filters merge fields by
the NotificationVariables allowlist before render.

7 ITs cover: no override, full override, subject-only override,
body-only override, SMS channel (subject null), allowlist filter
drops extra fields, Mustache conditional sections.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3.2: Replace `EmailBodyComposer` use sites with `NotificationComposer`

**Files:**
- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/email/SendReceiptEmailActivitiesImpl.java` (and Payment counterpart) — replace `emailBodyComposer.compose(...)` calls
- Delete: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/email/EmailBodyComposer.java`
- Delete: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/email/EmailContent.java` (if it was the return type — replace usage with `ComposedMessage`)

- [ ] **Step 1: Find all callers of `EmailBodyComposer`**

```bash
grep -rln 'EmailBodyComposer\|EmailContent' cia-backend/
```

Expected: ~2–4 sites (the two activity impls + the composer itself + EmailContent if it's separate).

- [ ] **Step 2: Update each caller to use `NotificationComposer.compose(...)`**

Replace the pattern:

```java
// BEFORE
EmailContent content = emailBodyComposer.compose(EmailTemplateType.RECEIPT_EMAIL, mergeFields);
// USES content.subject() / content.body()
```

with:

```java
// AFTER
import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import com.nubeero.cia.finance.notification.ComposedMessage;
import com.nubeero.cia.finance.notification.NotificationComposer;

// inject NotificationComposer notificationComposer
ComposedMessage content = notificationComposer.compose(
        NotificationTemplateType.RECEIPT, NotificationChannel.EMAIL, mergeFields);
// USES content.subject() / content.body() — same access pattern
```

Repeat for the payment-voucher activity impl with `NotificationTemplateType.PAYMENT_VOUCHER`.

- [ ] **Step 3: Delete the legacy `EmailBodyComposer` + `EmailContent`**

```bash
rm cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/email/EmailBodyComposer.java
rm cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/email/EmailContent.java
```

- [ ] **Step 4: Compile + run the existing email workflow ITs to verify behaviour preserved**

```bash
mvn -pl cia-finance compile -DskipTests
mvn -pl cia-api verify -Dit.test='SendReceiptEmailWorkflowIT,SendPaymentVoucherEmailWorkflowIT' -DskipUnitTests=true
```
Expected: BUILD SUCCESS + all email ITs pass (now using NotificationComposer under the hood).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(finance): Task 3.2 — replace EmailBodyComposer with NotificationComposer

The two email activity impls (SendReceiptEmailActivitiesImpl,
SendPaymentVoucherEmailActivitiesImpl) now call NotificationComposer
with explicit (type, channel = EMAIL) arguments instead of the legacy
type-coupled EmailBodyComposer.

EmailBodyComposer + EmailContent deleted — replaced by NotificationComposer
+ ComposedMessage.

Existing email workflow ITs continue to pass unchanged (the composer is
swapped, the rest of the pipeline is identical).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 4 — Setup CRUD: REST surface for the editor

### Task 4.1: DTOs + `NotificationTemplateService` (with save-time allowlist validation)

**Files:**

- Create: `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/notification/dto/NotificationTemplateRequest.java`
- Create: `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/notification/dto/NotificationTemplateResponse.java`
- Create: `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/notification/dto/NotificationTemplatePreviewRequest.java`
- Create: `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/notification/dto/NotificationTemplatePreviewResponse.java`
- Create: `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/notification/dto/NotificationTemplateDefaultsResponse.java`
- Create: `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/notification/dto/NotificationTemplateVariablesResponse.java`
- Create: `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/notification/NotificationTemplateService.java`

- [ ] **Step 1: Write the DTOs**

```java
// cia-setup/src/main/java/com/nubeero/cia/setup/notification/dto/NotificationTemplateRequest.java
package com.nubeero.cia.setup.notification.dto;

import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record NotificationTemplateRequest(
        @NotNull NotificationTemplateType templateType,
        @NotNull NotificationChannel channel,
        @Size(max = 500) String subjectTemplate,    // nullable
        @Size(max = 1000) String bodyTemplate       // nullable; service-level CHECK ensures at-least-one-non-null
) {}
```

```java
// cia-setup/src/main/java/com/nubeero/cia/setup/notification/dto/NotificationTemplateResponse.java
package com.nubeero.cia.setup.notification.dto;

import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import com.nubeero.cia.setup.notification.TenantNotificationTemplate;

import java.time.Instant;
import java.util.UUID;

public record NotificationTemplateResponse(
        UUID id,
        NotificationTemplateType templateType,
        NotificationChannel channel,
        String subjectTemplate,
        String bodyTemplate,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
    public static NotificationTemplateResponse from(TenantNotificationTemplate entity) {
        return new NotificationTemplateResponse(
                entity.getId(),
                entity.getTemplateType(),
                entity.getChannel(),
                entity.getSubjectTemplate(),
                entity.getBodyTemplate(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getCreatedBy(),
                entity.getUpdatedBy());
    }
}
```

```java
// cia-setup/src/main/java/com/nubeero/cia/setup/notification/dto/NotificationTemplatePreviewRequest.java
package com.nubeero.cia.setup.notification.dto;

import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record NotificationTemplatePreviewRequest(
        @NotNull NotificationTemplateType templateType,
        @NotNull NotificationChannel channel,
        String subjectTemplate,
        String bodyTemplate,
        @NotNull Map<String, Object> sampleValues
) {}
```

```java
// cia-setup/src/main/java/com/nubeero/cia/setup/notification/dto/NotificationTemplatePreviewResponse.java
package com.nubeero.cia.setup.notification.dto;

public record NotificationTemplatePreviewResponse(String subject, String body) {}
```

```java
// cia-setup/src/main/java/com/nubeero/cia/setup/notification/dto/NotificationTemplateDefaultsResponse.java
package com.nubeero.cia.setup.notification.dto;

import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import java.util.List;

public record NotificationTemplateDefaultsResponse(List<Entry> defaults) {
    public record Entry(
            NotificationTemplateType templateType,
            NotificationChannel channel,
            String subjectTemplate,    // null for SMS
            String bodyTemplate
    ) {}
}
```

```java
// cia-setup/src/main/java/com/nubeero/cia/setup/notification/dto/NotificationTemplateVariablesResponse.java
package com.nubeero.cia.setup.notification.dto;

import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import java.util.List;
import java.util.Set;

public record NotificationTemplateVariablesResponse(List<Entry> variables) {
    public record Entry(
            NotificationTemplateType templateType,
            NotificationChannel channel,
            Set<String> allowedVariables
    ) {}
}
```

- [ ] **Step 2: Write the service**

```java
// cia-setup/src/main/java/com/nubeero/cia/setup/notification/NotificationTemplateService.java
package com.nubeero.cia.setup.notification;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.NotFoundException;
import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import com.nubeero.cia.common.notification.NotificationVariables;
import com.nubeero.cia.documents.notification.DefaultTemplateLoader;
import com.nubeero.cia.documents.notification.MustacheTemplateRenderer;
import com.nubeero.cia.setup.notification.dto.NotificationTemplateDefaultsResponse;
import com.nubeero.cia.setup.notification.dto.NotificationTemplatePreviewRequest;
import com.nubeero.cia.setup.notification.dto.NotificationTemplatePreviewResponse;
import com.nubeero.cia.setup.notification.dto.NotificationTemplateRequest;
import com.nubeero.cia.setup.notification.dto.NotificationTemplateResponse;
import com.nubeero.cia.setup.notification.dto.NotificationTemplateVariablesResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationTemplateService {

    private final TenantNotificationTemplateRepository repo;
    private final MustacheTemplateRenderer renderer;
    private final DefaultTemplateLoader defaults;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<NotificationTemplateResponse> listOverrides() {
        return repo.findAllByOrderByTemplateTypeAscChannelAsc().stream()
                .map(NotificationTemplateResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public NotificationTemplateDefaultsResponse listDefaults() {
        List<NotificationTemplateDefaultsResponse.Entry> entries = Arrays
                .stream(NotificationTemplateType.values())
                .flatMap(type -> Arrays.stream(NotificationChannel.values())
                        .map(channel -> new NotificationTemplateDefaultsResponse.Entry(
                                type, channel,
                                defaults.subjectFor(type, channel),
                                defaults.bodyFor(type, channel))))
                .toList();
        return new NotificationTemplateDefaultsResponse(entries);
    }

    @Transactional(readOnly = true)
    public NotificationTemplateVariablesResponse listAllowedVariables() {
        List<NotificationTemplateVariablesResponse.Entry> entries = Arrays
                .stream(NotificationTemplateType.values())
                .flatMap(type -> Arrays.stream(NotificationChannel.values())
                        .map(channel -> new NotificationTemplateVariablesResponse.Entry(
                                type, channel,
                                NotificationVariables.allowlistFor(type, channel))))
                .toList();
        return new NotificationTemplateVariablesResponse(entries);
    }

    @Transactional
    public NotificationTemplateResponse create(NotificationTemplateRequest req) {
        validateRequest(req);
        if (repo.existsByTemplateTypeAndChannel(req.templateType(), req.channel())) {
            throw new BusinessRuleException(
                    "TEMPLATE_TYPE_CHANNEL_CONFLICT",
                    "An override already exists for " + req.templateType() + "/" + req.channel());
        }
        TenantNotificationTemplate entity = TenantNotificationTemplate.builder()
                .templateType(req.templateType())
                .channel(req.channel())
                .subjectTemplate(req.subjectTemplate())
                .bodyTemplate(req.bodyTemplate())
                .build();
        repo.save(entity);
        audit.log("TenantNotificationTemplate", entity.getId().toString(),
                AuditAction.CREATE, null, NotificationTemplateResponse.from(entity));
        return NotificationTemplateResponse.from(entity);
    }

    @Transactional
    public NotificationTemplateResponse update(UUID id, NotificationTemplateRequest req) {
        validateRequest(req);
        TenantNotificationTemplate entity = repo.findById(id)
                .orElseThrow(() -> new NotFoundException("TenantNotificationTemplate", id.toString()));
        var before = NotificationTemplateResponse.from(entity);

        entity.setSubjectTemplate(req.subjectTemplate());
        entity.setBodyTemplate(req.bodyTemplate());
        // templateType + channel are immutable on update — the unique-row identity

        repo.save(entity);
        audit.log("TenantNotificationTemplate", entity.getId().toString(),
                AuditAction.UPDATE, before, NotificationTemplateResponse.from(entity));
        return NotificationTemplateResponse.from(entity);
    }

    @Transactional
    public void delete(UUID id) {
        TenantNotificationTemplate entity = repo.findById(id)
                .orElseThrow(() -> new NotFoundException("TenantNotificationTemplate", id.toString()));
        var before = NotificationTemplateResponse.from(entity);
        repo.delete(entity);   // @SQLDelete sets deleted_at
        audit.log("TenantNotificationTemplate", id.toString(),
                AuditAction.DELETE, before, null);
    }

    @Transactional(readOnly = true)
    public NotificationTemplatePreviewResponse preview(NotificationTemplatePreviewRequest req) {
        // Use the request's templates if provided; otherwise the JAR defaults.
        String subjectTemplate = req.subjectTemplate() != null && !req.subjectTemplate().isBlank()
                ? req.subjectTemplate()
                : defaults.subjectFor(req.templateType(), req.channel());
        String bodyTemplate = req.bodyTemplate() != null && !req.bodyTemplate().isBlank()
                ? req.bodyTemplate()
                : defaults.bodyFor(req.templateType(), req.channel());

        // Validate just-in-case the caller skipped the save endpoint and posted directly to preview
        if (subjectTemplate != null) validateAgainstAllowlist(subjectTemplate, req.templateType(), req.channel());
        validateAgainstAllowlist(bodyTemplate, req.templateType(), req.channel());

        Set<String> allowlist = NotificationVariables.allowlistFor(req.templateType(), req.channel());
        var filtered = renderer.filterByAllowlist(req.sampleValues(), allowlist);

        String renderedSubject = subjectTemplate == null ? null : renderer.render(subjectTemplate, filtered);
        String renderedBody = renderer.render(bodyTemplate, filtered);
        return new NotificationTemplatePreviewResponse(renderedSubject, renderedBody);
    }

    private void validateRequest(NotificationTemplateRequest req) {
        if ((req.subjectTemplate() == null || req.subjectTemplate().isBlank())
                && (req.bodyTemplate() == null || req.bodyTemplate().isBlank())) {
            throw new BusinessRuleException(
                    "EMPTY_OVERRIDE",
                    "At least one of subjectTemplate or bodyTemplate must be provided");
        }
        if (req.channel() == NotificationChannel.SMS
                && req.subjectTemplate() != null
                && !req.subjectTemplate().isBlank()) {
            throw new BusinessRuleException(
                    "SMS_SUBJECT_NOT_ALLOWED",
                    "SMS templates may not specify a subject");
        }
        if (req.bodyTemplate() != null && req.bodyTemplate().length() > 1000) {
            throw new BusinessRuleException(
                    "TEMPLATE_TOO_LONG",
                    "bodyTemplate must be at most 1000 characters");
        }
        if (req.subjectTemplate() != null) {
            validateAgainstAllowlist(req.subjectTemplate(), req.templateType(), req.channel());
        }
        if (req.bodyTemplate() != null) {
            validateAgainstAllowlist(req.bodyTemplate(), req.templateType(), req.channel());
        }
    }

    private void validateAgainstAllowlist(String template,
                                          NotificationTemplateType type,
                                          NotificationChannel channel) {
        Set<String> allowlist = NotificationVariables.allowlistFor(type, channel);
        Set<String> referenced = renderer.extractVariableNames(template);
        for (String name : referenced) {
            if (!allowlist.contains(name)) {
                throw new BusinessRuleException(
                        "UNKNOWN_TEMPLATE_VARIABLE",
                        "Template references unknown variable: " + name);
            }
        }
    }
}
```

- [ ] **Step 3: Verify compile**

```bash
mvn -pl cia-setup compile -DskipTests -am
```
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(setup): Task 4.1 — NotificationTemplateService + DTOs

CRUD service for TenantNotificationTemplate with:
  - save-time variable allowlist validation
  - SMS-subject-not-allowed validation
  - empty-override (both fields null) validation
  - template length cap (1000 chars)
  - TEMPLATE_TYPE_CHANNEL_CONFLICT on duplicate create
  - preview endpoint that renders against sample values
  - listDefaults() + listAllowedVariables() for editor UI hydration
  - AuditService.log for every CREATE/UPDATE/DELETE

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4.2: `NotificationTemplateController` with 7 endpoints

**Files:**

- Create: `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/notification/NotificationTemplateController.java`

- [ ] **Step 1: Write the controller**

```java
// cia-setup/src/main/java/com/nubeero/cia/setup/notification/NotificationTemplateController.java
package com.nubeero.cia.setup.notification;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.setup.notification.dto.NotificationTemplateDefaultsResponse;
import com.nubeero.cia.setup.notification.dto.NotificationTemplatePreviewRequest;
import com.nubeero.cia.setup.notification.dto.NotificationTemplatePreviewResponse;
import com.nubeero.cia.setup.notification.dto.NotificationTemplateRequest;
import com.nubeero.cia.setup.notification.dto.NotificationTemplateResponse;
import com.nubeero.cia.setup.notification.dto.NotificationTemplateVariablesResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/setup/notification-templates")
@RequiredArgsConstructor
public class NotificationTemplateController {

    private final NotificationTemplateService service;

    @GetMapping
    @PreAuthorize("hasAuthority('notification_templates:view')")
    public ResponseEntity<ApiResponse<List<NotificationTemplateResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success(service.listOverrides()));
    }

    @GetMapping("/defaults")
    @PreAuthorize("hasAuthority('notification_templates:view')")
    public ResponseEntity<ApiResponse<NotificationTemplateDefaultsResponse>> defaults() {
        return ResponseEntity.ok(ApiResponse.success(service.listDefaults()));
    }

    @GetMapping("/variables")
    @PreAuthorize("hasAuthority('notification_templates:view')")
    public ResponseEntity<ApiResponse<NotificationTemplateVariablesResponse>> variables() {
        return ResponseEntity.ok(ApiResponse.success(service.listAllowedVariables()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('notification_templates:update')")
    public ResponseEntity<ApiResponse<NotificationTemplateResponse>> create(
            @Valid @RequestBody NotificationTemplateRequest req) {
        return new ResponseEntity<>(ApiResponse.success(service.create(req)), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('notification_templates:update')")
    public ResponseEntity<ApiResponse<NotificationTemplateResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody NotificationTemplateRequest req) {
        return ResponseEntity.ok(ApiResponse.success(service.update(id, req)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('notification_templates:update')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('notification_templates:view')")
    public ResponseEntity<ApiResponse<NotificationTemplatePreviewResponse>> preview(
            @Valid @RequestBody NotificationTemplatePreviewRequest req) {
        return ResponseEntity.ok(ApiResponse.success(service.preview(req)));
    }
}
```

- [ ] **Step 2: Verify compile**

```bash
mvn -pl cia-setup compile -DskipTests -am
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/notification/NotificationTemplateController.java
git commit -m "$(cat <<'EOF'
feat(setup): Task 4.2 — NotificationTemplateController (7 endpoints)

Setup REST surface for the notification template editor:
  GET    /api/v1/setup/notification-templates              → list overrides
  GET    /api/v1/setup/notification-templates/defaults     → all JAR defaults
  GET    /api/v1/setup/notification-templates/variables    → allowlist
  POST   /api/v1/setup/notification-templates              → create override
  PUT    /api/v1/setup/notification-templates/{id}         → update
  DELETE /api/v1/setup/notification-templates/{id}         → reset to default
  POST   /api/v1/setup/notification-templates/preview      → render with samples

Reads gated on notification_templates:view; mutations on
notification_templates:update.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4.3: `NotificationTemplateControllerIT` (11 tests)

**Files:**

- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/setup/notification/NotificationTemplateControllerIT.java`

- [ ] **Step 1: Write the IT**

```java
// cia-api/src/test/java/com/nubeero/cia/api/setup/notification/NotificationTemplateControllerIT.java
package com.nubeero.cia.api.setup.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.api.support.AbstractApiIT;
import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import com.nubeero.cia.setup.notification.TenantNotificationTemplate;
import com.nubeero.cia.setup.notification.TenantNotificationTemplateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WithMockUser(authorities = {"notification_templates:view", "notification_templates:update"})
class NotificationTemplateControllerIT extends AbstractApiIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantNotificationTemplateRepository repo;

    @Test
    void listEmpty_returnsEmptyArray() throws Exception {
        mvc.perform(get("/api/v1/setup/notification-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void listPopulated_returnsRows() throws Exception {
        repo.save(TenantNotificationTemplate.builder()
                .templateType(NotificationTemplateType.RECEIPT)
                .channel(NotificationChannel.EMAIL)
                .subjectTemplate("Custom {{receiptNumber}}")
                .build());
        mvc.perform(get("/api/v1/setup/notification-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].subjectTemplate", is("Custom {{receiptNumber}}")));
    }

    @Test
    void getDefaults_returnsAllFourTemplates() throws Exception {
        mvc.perform(get("/api/v1/setup/notification-templates/defaults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaults", hasSize(4)));
    }

    @Test
    void getVariables_returnsAllowlistsForFourCombinations() throws Exception {
        mvc.perform(get("/api/v1/setup/notification-templates/variables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.variables", hasSize(4)))
                .andExpect(jsonPath("$.data.variables[?(@.templateType=='RECEIPT' && @.channel=='EMAIL')].allowedVariables",
                        hasItem(hasItem("customerName"))));
    }

    @Test
    void createValid_201() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "templateType", "RECEIPT",
                "channel", "EMAIL",
                "subjectTemplate", "Hi {{customerName}}",
                "bodyTemplate", "<p>Receipt {{receiptNumber}}</p>"));
        mvc.perform(post("/api/v1/setup/notification-templates")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id", notNullValue()));
    }

    @Test
    void createUnknownVariable_400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "templateType", "RECEIPT",
                "channel", "EMAIL",
                "bodyTemplate", "Hi {{notAValidVariable}}"));
        mvc.perform(post("/api/v1/setup/notification-templates")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code", is("UNKNOWN_TEMPLATE_VARIABLE")));
    }

    @Test
    void createDuplicate_409() throws Exception {
        repo.save(TenantNotificationTemplate.builder()
                .templateType(NotificationTemplateType.RECEIPT)
                .channel(NotificationChannel.EMAIL)
                .bodyTemplate("Body {{customerName}}")
                .build());
        String body = objectMapper.writeValueAsString(Map.of(
                "templateType", "RECEIPT",
                "channel", "EMAIL",
                "bodyTemplate", "Different {{customerName}}"));
        mvc.perform(post("/api/v1/setup/notification-templates")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("TEMPLATE_TYPE_CHANNEL_CONFLICT")));
    }

    @Test
    void createBothNull_400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "templateType", "RECEIPT",
                "channel", "EMAIL"));
        // subjectTemplate + bodyTemplate both absent → service-level EMPTY_OVERRIDE
        mvc.perform(post("/api/v1/setup/notification-templates")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code", is("EMPTY_OVERRIDE")));
    }

    @Test
    void updateExisting_200() throws Exception {
        TenantNotificationTemplate existing = repo.save(TenantNotificationTemplate.builder()
                .templateType(NotificationTemplateType.RECEIPT)
                .channel(NotificationChannel.EMAIL)
                .bodyTemplate("v1 {{customerName}}")
                .build());
        String body = objectMapper.writeValueAsString(Map.of(
                "templateType", "RECEIPT",
                "channel", "EMAIL",
                "bodyTemplate", "v2 {{customerName}}"));
        mvc.perform(put("/api/v1/setup/notification-templates/" + existing.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bodyTemplate", containsString("v2")));
    }

    @Test
    void deleteResetsToDefault_204() throws Exception {
        TenantNotificationTemplate existing = repo.save(TenantNotificationTemplate.builder()
                .templateType(NotificationTemplateType.RECEIPT)
                .channel(NotificationChannel.EMAIL)
                .bodyTemplate("Override {{customerName}}")
                .build());
        mvc.perform(delete("/api/v1/setup/notification-templates/" + existing.getId()))
                .andExpect(status().isNoContent());
        // Subsequent list shows no rows (soft-deleted)
        mvc.perform(get("/api/v1/setup/notification-templates"))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void previewHappy_returnsRenderedSubjectAndBody() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "templateType", "RECEIPT",
                "channel", "EMAIL",
                "subjectTemplate", "Receipt {{receiptNumber}}",
                "bodyTemplate", "Hi {{customerName}}",
                "sampleValues", Map.of(
                        "customerName", "Acme Ltd",
                        "amount", "₦450,000",
                        "paymentDate", "2026-05-27",
                        "receiptNumber", "REC-001",
                        "debitNoteNumber", "DN-001",
                        "companyName", "Tenant Insurance Plc")));
        mvc.perform(post("/api/v1/setup/notification-templates/preview")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subject", is("Receipt REC-001")))
                .andExpect(jsonPath("$.data.body", is("Hi Acme Ltd")));
    }

    @Test
    @WithMockUser(authorities = {"notification_templates:view"})   // no :update authority
    void createWithoutAuthority_403() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "templateType", "RECEIPT",
                "channel", "EMAIL",
                "bodyTemplate", "Body {{customerName}}"));
        mvc.perform(post("/api/v1/setup/notification-templates")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run the IT — verify it passes**

```bash
mvn -pl cia-api verify -Dit.test='NotificationTemplateControllerIT' -DskipUnitTests=true
```
Expected: 12/12 tests pass (11 happy paths + 1 authority check).

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
test(api): Task 4.3 — NotificationTemplateControllerIT (12 tests)

Full CRUD coverage for the notification template editor endpoints.
Verifies happy paths, allowlist enforcement (UNKNOWN_TEMPLATE_VARIABLE),
conflict detection (TEMPLATE_TYPE_CHANNEL_CONFLICT), empty-override
guard (EMPTY_OVERRIDE), authority gates (403 without :update),
preview rendering, and reset-to-default soft delete.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 5 — SMS SPI infrastructure

### Task 5.1: Delete legacy `SmsNotificationService` stub

**Files:**

- Delete: `cia-backend/cia-notifications/src/main/java/com/nubeero/cia/notifications/impl/SmsNotificationService.java`

- [ ] **Step 1: Verify no production callers reference the stub**

```bash
grep -rn 'SmsNotificationService' cia-backend/ --include='*.java'
```
Expected: zero matches outside the file itself. If matches exist, refactor them in Task 5.2 against the new `SmsService` SPI; otherwise delete the file.

- [ ] **Step 2: Delete the file**

```bash
rm cia-backend/cia-notifications/src/main/java/com/nubeero/cia/notifications/impl/SmsNotificationService.java
```

- [ ] **Step 3: Verify compile**

```bash
mvn -pl cia-notifications compile -DskipTests
```
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
chore(notifications): Task 5.1 — delete legacy SmsNotificationService stub

The stub-class SmsNotificationService implementing the broader
NotificationService interface had no production callers. Removed
ahead of the new dedicated SmsService SPI (Task 5.2).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5.2: `SmsService` SPI + `SmsMessage` + `LoggingSmsService` + unit test

**Files:**

- Create: `cia-backend/cia-notifications/src/main/java/com/nubeero/cia/notifications/sms/SmsService.java`
- Create: `cia-backend/cia-notifications/src/main/java/com/nubeero/cia/notifications/sms/SmsMessage.java`
- Create: `cia-backend/cia-notifications/src/main/java/com/nubeero/cia/notifications/sms/LoggingSmsService.java`
- Create: `cia-backend/cia-notifications/src/test/java/com/nubeero/cia/notifications/sms/LoggingSmsServiceTest.java`

- [ ] **Step 1: Write the SPI + value type**

```java
// cia-notifications/src/main/java/com/nubeero/cia/notifications/sms/SmsService.java
package com.nubeero.cia.notifications.sms;

public interface SmsService {
    void sendSms(SmsMessage message);
}
```

```java
// cia-notifications/src/main/java/com/nubeero/cia/notifications/sms/SmsMessage.java
package com.nubeero.cia.notifications.sms;

public record SmsMessage(String toPhone, String body) {}
```

- [ ] **Step 2: Write the failing test**

```java
// cia-notifications/src/test/java/com/nubeero/cia/notifications/sms/LoggingSmsServiceTest.java
package com.nubeero.cia.notifications.sms;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingSmsServiceTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(LoggingSmsService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void logsToAndBodyLength() {
        new LoggingSmsService().sendSms(new SmsMessage("+2349012345678", "Hello world"));
        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getFormattedMessage())
                .contains("+2349012345678")
                .contains("11"); // body length
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

```bash
mvn -pl cia-notifications test -Dtest='LoggingSmsServiceTest'
```
Expected: FAIL with "LoggingSmsService not found".

- [ ] **Step 4: Write the impl**

```java
// cia-notifications/src/main/java/com/nubeero/cia/notifications/sms/LoggingSmsService.java
package com.nubeero.cia.notifications.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Logging-only SMS provider. Default when no other provider is wired.
 * Production prod-impls (Termii / Twilio) will replace this via
 * cia.notifications.sms.provider property in their respective backlog
 * pickups (R7-termii-prod / R7-twilio-prod).
 */
@Service
@ConditionalOnProperty(
        name = "cia.notifications.sms.provider",
        havingValue = "logging",
        matchIfMissing = true)
@Slf4j
public class LoggingSmsService implements SmsService {

    @Override
    public void sendSms(SmsMessage message) {
        log.info("[SMS STUB] to={} bodyLength={}",
                message.toPhone(),
                message.body() == null ? 0 : message.body().length());
    }
}
```

- [ ] **Step 5: Run the test — verify it passes**

```bash
mvn -pl cia-notifications test -Dtest='LoggingSmsServiceTest'
```
Expected: 1/1 test passes.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(notifications): Task 5.2 — SmsService SPI + LoggingSmsService

New SPI mirroring EmailService:
  - SmsService.sendSms(SmsMessage)
  - SmsMessage(toPhone, body) value type
  - LoggingSmsService (default, matchIfMissing=true)

Provider gating via cia.notifications.sms.provider. Future prod impls
(R7-termii-prod, R7-twilio-prod) layer in additional @ConditionalOnProperty
beans with havingValue = "termii" / "twilio".

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 6 — V61 migration + projection field additions

### Task 6.1: V61 migration + entity columns

**Files:**

- Create: `cia-backend/cia-api/src/main/resources/db/migration/V61__add_sms_audit_columns.sql`
- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/Receipt.java` (add 2 fields)
- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/Payment.java` (add 2 fields)

- [ ] **Step 1: Write the migration**

```sql
-- cia-api/src/main/resources/db/migration/V61__add_sms_audit_columns.sql
-- F7-δ + R7 — SMS delivery audit columns on receipts + payments.
-- Populated by SMS workflow activities on successful delivery.
-- Mirrors V57's email_sent_at / email_sent_to.

ALTER TABLE receipts ADD COLUMN sms_sent_at TIMESTAMPTZ;
ALTER TABLE receipts ADD COLUMN sms_sent_to VARCHAR(50);

ALTER TABLE payments ADD COLUMN sms_sent_at TIMESTAMPTZ;
ALTER TABLE payments ADD COLUMN sms_sent_to VARCHAR(50);
```

- [ ] **Step 2: Update the Flyway IT target to 61**

Bump from 60 → 61 in the test config (same location as Task 1.1 Step 2).

- [ ] **Step 3: Add the JPA fields to `Receipt`**

```java
// in Receipt.java, alongside existing email_sent_* fields:
@Column(name = "sms_sent_at")
private Instant smsSentAt;

@Column(name = "sms_sent_to", length = 50)
private String smsSentTo;
```

- [ ] **Step 4: Add the same fields to `Payment`**

Same shape — copy the field declarations into `Payment.java`.

- [ ] **Step 5: Verify compile + existing receipt/payment ITs still pass**

```bash
mvn -pl cia-finance compile -DskipTests
mvn -pl cia-api verify -Dit.test='ReceiptControllerIT,PaymentControllerIT' -DskipUnitTests=true
```
Expected: BUILD SUCCESS + existing ITs unchanged.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(finance): Task 6.1 — V61 + sms_sent_{at,to} on Receipt + Payment

Mirrors V57's email_sent_* columns. Populated by the SMS workflow
activities (Tasks 8.x) on successful delivery.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6.2: ListItemResponse projections gain `recipientPhone` + `smsSentAt` + `smsSentTo`

**Files:**

- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptListItemResponse.java`
- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/PaymentListItemResponse.java`
- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptService.java` (`toListItem` method)
- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/PaymentService.java` (`toListItem` method)

- [ ] **Step 1: Add the 3 nullable fields to both projection records**

```java
// ReceiptListItemResponse — add to the existing record (after recipientEmail/emailSentAt/emailSentTo):
String recipientPhone,
Instant smsSentAt,
String smsSentTo
```

```java
// PaymentListItemResponse — same 3 fields added in the same position
String recipientPhone,
Instant smsSentAt,
String smsSentTo
```

- [ ] **Step 2: Populate them in `ReceiptService.toListItem`**

Following the existing `recipientEmail` resolution pattern from F7-γ T27 — one extra JDBC lookup per row keyed on `dn.customerId`:

```java
// In ReceiptService.toListItem(Receipt r), after the recipientEmail block:
String recipientPhone = jdbc.queryForObject(
        "SELECT phone FROM customers WHERE id = ?",
        String.class, r.getDebitNote().getCustomerId());
// returns null if no row OR null phone — both acceptable
```

Then pass `recipientPhone, r.getSmsSentAt(), r.getSmsSentTo()` to the projection constructor.

- [ ] **Step 3: Populate them in `PaymentService.toListItem` via `BeneficiaryPhoneResolverDispatcher`**

```java
// In PaymentService.toListItem(Payment p), after the recipientEmail block:
String recipientPhone = phoneResolverDispatcher
        .resolve(p.getCreditNote()).orElse(null);
```

(The dispatcher is created in Phase 7 — Task 6.2 commits the field-passing skeleton; the populated value will be null until Phase 7 lands. The IT in Phase 9 catches the wiring.)

- [ ] **Step 4: Compile + run existing list-item ITs**

```bash
mvn -pl cia-finance compile -DskipTests
mvn -pl cia-api verify -Dit.test='ReceiptListControllerIT,PaymentListControllerIT,ReceiptPdfListItemIT,PaymentPdfListItemIT' -DskipUnitTests=true
```
Expected: BUILD SUCCESS + all 4 existing ITs pass (they don't assert the new fields, so the projection changes are backwards-compatible).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(finance): Task 6.2 — recipientPhone + smsSentAt + smsSentTo on list projections

ReceiptListItemResponse and PaymentListItemResponse gain 3 new nullable
fields each. Receipt projection resolves recipientPhone via direct
customers.phone JDBC; Payment projection delegates to the
BeneficiaryPhoneResolverDispatcher (created in Phase 7 — value is null
until that lands).

N+1 caveat: same one-extra-lookup-per-row as F7-γ's recipientEmail.
Acceptable for v1; batch resolver is a follow-up if perf becomes a concern.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 7 — Phone resolver SPI + 4 impls

### Task 7.1: `BeneficiaryPhoneResolver` SPI + `BeneficiaryPhoneResolverDispatcher`

**Files:**

- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/sms/BeneficiaryPhoneResolver.java`
- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/sms/BeneficiaryPhoneResolverDispatcher.java`

- [ ] **Step 1: Write the SPI**

```java
// cia-finance/src/main/java/com/nubeero/cia/finance/sms/BeneficiaryPhoneResolver.java
package com.nubeero.cia.finance.sms;

import com.nubeero.cia.finance.CreditNote;
import java.util.Optional;

public interface BeneficiaryPhoneResolver {
    /** Returns empty if the entity is missing or phone is null/blank. */
    Optional<String> resolve(CreditNote creditNote);
}
```

- [ ] **Step 2: Write the dispatcher**

```java
// cia-finance/src/main/java/com/nubeero/cia/finance/sms/BeneficiaryPhoneResolverDispatcher.java
package com.nubeero.cia.finance.sms;

import com.nubeero.cia.finance.CreditNote;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Routes a CreditNote to its type-specific BeneficiaryPhoneResolver.
 * Bean lookup by name: "<TYPE>-phone" (e.g., "CLAIM-phone"), mirroring
 * the F7-γ BeneficiaryEmailResolverDispatcher convention.
 *
 * Fail-closed: types without a registered resolver, or resolvers that
 * return empty, both surface as Optional.empty() at the service layer.
 * The service then throws NotificationPreflightException with code
 * PAYMENT_RECIPIENT_PHONE_UNRESOLVED → HTTP 422.
 */
@Component
@RequiredArgsConstructor
public class BeneficiaryPhoneResolverDispatcher {

    private final ApplicationContext applicationContext;

    public Optional<String> resolve(CreditNote creditNote) {
        if (creditNote == null || creditNote.getEntityType() == null) {
            return Optional.empty();
        }
        String beanName = creditNote.getEntityType().name() + "-phone";
        if (!applicationContext.containsBean(beanName)) {
            return Optional.empty();
        }
        BeneficiaryPhoneResolver resolver = (BeneficiaryPhoneResolver)
                applicationContext.getBean(beanName);
        return resolver.resolve(creditNote);
    }
}
```

- [ ] **Step 3: Verify compile**

```bash
mvn -pl cia-finance compile -DskipTests -am
```
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(finance): Task 7.1 — BeneficiaryPhoneResolver SPI + Dispatcher

Mirrors the F7-γ BeneficiaryEmailResolver pattern. Dispatcher does a
bean lookup by name "<TYPE>-phone" (e.g., "CLAIM-phone"). Fail-closed:
unmapped entity types or empty resolver results surface as
Optional.empty() and become 422 PAYMENT_RECIPIENT_PHONE_UNRESOLVED
at the service layer.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7.2: 4 phone resolver impls (CLAIM / COMMISSION / REINSURANCE / ENDORSEMENT)

**Files:**

- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/sms/ClaimDvBeneficiaryPhoneResolver.java`
- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/sms/CommissionBeneficiaryPhoneResolver.java`
- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/sms/EndorsementRefundBeneficiaryPhoneResolver.java`
- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/sms/FacOutwardBeneficiaryPhoneResolver.java`

These follow the same shape as the F7-γ email resolvers at `cia-finance/src/main/java/com/nubeero/cia/finance/email/` — read the existing class for `ClaimDvBeneficiaryEmailResolver.java` to confirm the exact entity-loading pattern, then write the phone variant.

- [ ] **Step 1: Write the 4 impls**

```java
// cia-finance/src/main/java/com/nubeero/cia/finance/sms/ClaimDvBeneficiaryPhoneResolver.java
package com.nubeero.cia.finance.sms;

import com.nubeero.cia.claims.Claim;
import com.nubeero.cia.claims.ClaimRepository;
import com.nubeero.cia.customer.CustomerRepository;
import com.nubeero.cia.finance.CreditNote;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component("CLAIM-phone")
@RequiredArgsConstructor
public class ClaimDvBeneficiaryPhoneResolver implements BeneficiaryPhoneResolver {

    private final ClaimRepository claimRepo;
    private final CustomerRepository customerRepo;

    @Override
    public Optional<String> resolve(CreditNote creditNote) {
        return claimRepo.findById(creditNote.getEntityId())
                .map(Claim::getCustomerId)
                .flatMap(customerRepo::findById)
                .map(c -> c.getPhone())
                .filter(p -> p != null && !p.isBlank());
    }
}
```

```java
// cia-finance/src/main/java/com/nubeero/cia/finance/sms/CommissionBeneficiaryPhoneResolver.java
package com.nubeero.cia.finance.sms;

import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.policy.PolicyRepository;
import com.nubeero.cia.setup.org.broker.BrokerRepository;
import com.nubeero.cia.setup.org.agent.AgentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component("COMMISSION-phone")
@RequiredArgsConstructor
public class CommissionBeneficiaryPhoneResolver implements BeneficiaryPhoneResolver {

    private final PolicyRepository policyRepo;
    private final BrokerRepository brokerRepo;
    private final AgentRepository agentRepo;

    @Override
    public Optional<String> resolve(CreditNote creditNote) {
        // entityId on a COMMISSION credit note points to the policy
        return policyRepo.findById(creditNote.getEntityId()).flatMap(policy -> {
            // Mirror the F7-γ CommissionBeneficiaryEmailResolver behaviour:
            // prefer broker phone; fall back to agent phone; null otherwise.
            if (policy.getBrokerId() != null) {
                Optional<String> brokerPhone = brokerRepo.findById(policy.getBrokerId())
                        .map(b -> b.getPhone())
                        .filter(p -> p != null && !p.isBlank());
                if (brokerPhone.isPresent()) return brokerPhone;
            }
            if (policy.getAgentId() != null) {
                return agentRepo.findById(policy.getAgentId())
                        .map(a -> a.getPhone())
                        .filter(p -> p != null && !p.isBlank());
            }
            return Optional.empty();
        });
    }
}
```

```java
// cia-finance/src/main/java/com/nubeero/cia/finance/sms/EndorsementRefundBeneficiaryPhoneResolver.java
package com.nubeero.cia.finance.sms;

import com.nubeero.cia.customer.CustomerRepository;
import com.nubeero.cia.endorsement.EndorsementRepository;
import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.policy.PolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component("ENDORSEMENT-phone")
@RequiredArgsConstructor
public class EndorsementRefundBeneficiaryPhoneResolver implements BeneficiaryPhoneResolver {

    private final EndorsementRepository endorsementRepo;
    private final PolicyRepository policyRepo;
    private final CustomerRepository customerRepo;

    @Override
    public Optional<String> resolve(CreditNote creditNote) {
        return endorsementRepo.findById(creditNote.getEntityId())
                .map(e -> e.getPolicyId())
                .flatMap(policyRepo::findById)
                .map(p -> p.getCustomerId())
                .flatMap(customerRepo::findById)
                .map(c -> c.getPhone())
                .filter(p -> p != null && !p.isBlank());
    }
}
```

```java
// cia-finance/src/main/java/com/nubeero/cia/finance/sms/FacOutwardBeneficiaryPhoneResolver.java
package com.nubeero.cia.finance.sms;

import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.reinsurance.RiFacCoverRepository;
import com.nubeero.cia.setup.org.reinsurer.ReinsuranceCompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component("REINSURANCE-phone")
@RequiredArgsConstructor
public class FacOutwardBeneficiaryPhoneResolver implements BeneficiaryPhoneResolver {

    private final RiFacCoverRepository facCoverRepo;
    private final ReinsuranceCompanyRepository reinsurerRepo;

    @Override
    public Optional<String> resolve(CreditNote creditNote) {
        return facCoverRepo.findById(creditNote.getEntityId())
                .map(cover -> cover.getReinsuranceCompanyId())
                .flatMap(reinsurerRepo::findById)
                .map(r -> r.getPhone())
                .filter(p -> p != null && !p.isBlank());
    }
}
```

**Important:** if any of the imported repos / methods don't exist exactly as named, grep for the F7-γ equivalent (`grep -rn "ClaimDvBeneficiaryEmailResolver\|CommissionBeneficiaryEmailResolver" cia-finance/src/main/java/`) and mirror that file's imports + lookup chain. The shape is mechanical; only swap `getEmail()` → `getPhone()`.

- [ ] **Step 2: Verify compile**

```bash
mvn -pl cia-finance compile -DskipTests -am
```
Expected: BUILD SUCCESS. If any repo/entity field doesn't compile, mirror the existing email resolver in the sibling `email/` directory.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(finance): Task 7.2 — 4 BeneficiaryPhoneResolver impls

Mirror the F7-γ BeneficiaryEmailResolver lookup chains:
  - CLAIM-phone        → ClaimDvBeneficiaryPhoneResolver
  - COMMISSION-phone   → CommissionBeneficiaryPhoneResolver (broker → agent)
  - ENDORSEMENT-phone  → EndorsementRefundBeneficiaryPhoneResolver
  - REINSURANCE-phone  → FacOutwardBeneficiaryPhoneResolver

Each impl filters blank phones at the source (Optional.filter) so
the dispatcher's Optional.empty() return matches the email pattern.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 8 — SMS workflows + activities + worker registration

### Task 8.1: `SendReceiptSmsWorkflow` interface + impl

**Files:**

- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/sms/SendReceiptSmsWorkflow.java`
- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/sms/SendReceiptSmsWorkflowImpl.java`

Mirror `SendReceiptEmailWorkflow.java` + `Impl.java` in `cia-finance/.../email/` — same retry policy, same cancel-signal shape, same activity dispatch pattern.

- [ ] **Step 1: Write the workflow interface**

```java
// cia-finance/src/main/java/com/nubeero/cia/finance/sms/SendReceiptSmsWorkflow.java
package com.nubeero.cia.finance.sms;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.UUID;

@WorkflowInterface
public interface SendReceiptSmsWorkflow {

    @WorkflowMethod
    void send(String tenantId, UUID receiptId, String requestedBy);

    @SignalMethod
    void cancel();
}
```

- [ ] **Step 2: Write the workflow impl**

```java
// cia-finance/src/main/java/com/nubeero/cia/finance/sms/SendReceiptSmsWorkflowImpl.java
package com.nubeero.cia.finance.sms;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.UUID;

public class SendReceiptSmsWorkflowImpl implements SendReceiptSmsWorkflow {

    private boolean cancelled = false;

    private final SmsActivities activities = Workflow.newActivityStub(
            SmsActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofMinutes(5))
                            .setMaximumInterval(Duration.ofHours(1))
                            .setBackoffCoefficient(2.0)
                            // No setMaximumAttempts — retry indefinitely on transient errors
                            .setDoNotRetry(
                                    "RECEIPT_NOT_FOUND",
                                    "RECEIPT_RECIPIENT_PHONE_UNRESOLVED")
                            .build())
                    .build());

    @Override
    public void send(String tenantId, UUID receiptId, String requestedBy) {
        if (cancelled) return;
        activities.deliverReceiptSms(tenantId, receiptId, requestedBy);
    }

    @Override
    public void cancel() {
        this.cancelled = true;
    }
}
```

- [ ] **Step 3: Verify compile (won't fully resolve until `SmsActivities` is defined in Task 8.3)**

For now, just check the file syntactically — `SmsActivities` is referenced ahead of definition. The compile will pass after 8.3.

- [ ] **Step 4: Commit (deferred — bundle with 8.2 + 8.3 for a clean compile)**

Don't commit yet; the SmsActivities reference is unresolved. Continue to 8.2.

---

### Task 8.2: `SendPaymentVoucherSmsWorkflow` interface + impl

**Files:**

- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/sms/SendPaymentVoucherSmsWorkflow.java`
- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/sms/SendPaymentVoucherSmsWorkflowImpl.java`

- [ ] **Step 1: Write the workflow + impl (mirror 8.1)**

```java
// cia-finance/src/main/java/com/nubeero/cia/finance/sms/SendPaymentVoucherSmsWorkflow.java
package com.nubeero.cia.finance.sms;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.UUID;

@WorkflowInterface
public interface SendPaymentVoucherSmsWorkflow {

    @WorkflowMethod
    void send(String tenantId, UUID paymentId, String requestedBy);

    @SignalMethod
    void cancel();
}
```

```java
// cia-finance/src/main/java/com/nubeero/cia/finance/sms/SendPaymentVoucherSmsWorkflowImpl.java
package com.nubeero.cia.finance.sms;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.UUID;

public class SendPaymentVoucherSmsWorkflowImpl implements SendPaymentVoucherSmsWorkflow {

    private boolean cancelled = false;

    private final SmsActivities activities = Workflow.newActivityStub(
            SmsActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofMinutes(5))
                            .setMaximumInterval(Duration.ofHours(1))
                            .setBackoffCoefficient(2.0)
                            .setDoNotRetry(
                                    "PAYMENT_NOT_FOUND",
                                    "PAYMENT_RECIPIENT_PHONE_UNRESOLVED")
                            .build())
                    .build());

    @Override
    public void send(String tenantId, UUID paymentId, String requestedBy) {
        if (cancelled) return;
        activities.deliverPaymentVoucherSms(tenantId, paymentId, requestedBy);
    }

    @Override
    public void cancel() {
        this.cancelled = true;
    }
}
```

- [ ] **Step 2: Don't commit yet (still pending SmsActivities)**

---

### Task 8.3: `SmsActivities` interface + impl

**Files:**

- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/sms/SmsActivities.java`
- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/sms/SmsActivitiesImpl.java`

- [ ] **Step 1: Write the activities interface**

```java
// cia-finance/src/main/java/com/nubeero/cia/finance/sms/SmsActivities.java
package com.nubeero.cia.finance.sms;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.UUID;

@ActivityInterface
public interface SmsActivities {

    @ActivityMethod
    void deliverReceiptSms(String tenantId, UUID receiptId, String requestedBy);

    @ActivityMethod
    void deliverPaymentVoucherSms(String tenantId, UUID paymentId, String requestedBy);
}
```

- [ ] **Step 2: Write the activities impl**

```java
// cia-finance/src/main/java/com/nubeero/cia/finance/sms/SmsActivitiesImpl.java
package com.nubeero.cia.finance.sms;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import com.nubeero.cia.finance.Payment;
import com.nubeero.cia.finance.PaymentRepository;
import com.nubeero.cia.finance.Receipt;
import com.nubeero.cia.finance.ReceiptRepository;
import com.nubeero.cia.finance.notification.ComposedMessage;
import com.nubeero.cia.finance.notification.NotificationComposer;
import com.nubeero.cia.notifications.sms.SmsMessage;
import com.nubeero.cia.notifications.sms.SmsService;
import io.temporal.failure.ApplicationFailure;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SmsActivitiesImpl implements SmsActivities {

    private final ReceiptRepository receiptRepo;
    private final PaymentRepository paymentRepo;
    private final NotificationComposer composer;
    private final SmsService smsService;
    private final AuditService audit;
    private final BeneficiaryPhoneResolverDispatcher phoneDispatcher;
    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public void deliverReceiptSms(String tenantId, UUID receiptId, String requestedBy) {
        Receipt receipt = receiptRepo.findById(receiptId)
                .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                        "Receipt not found", "RECEIPT_NOT_FOUND", receiptId));

        // Re-resolve phone at activity-entry time in case it changed mid-queue.
        String toPhone = Optional.ofNullable(jdbc.queryForObject(
                "SELECT phone FROM customers WHERE id = ?",
                String.class, receipt.getDebitNote().getCustomerId()))
            .filter(p -> p != null && !p.isBlank())
            .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                    "Customer phone unavailable", "RECEIPT_RECIPIENT_PHONE_UNRESOLVED", receiptId));

        Map<String, Object> mergeFields = Map.of(
                "customerName", receipt.getDebitNote().getCustomerName(),
                "amount", "₦" + receipt.getAmount().toPlainString(),
                "receiptNumber", receipt.getReference()
        );

        ComposedMessage msg = composer.compose(
                NotificationTemplateType.RECEIPT, NotificationChannel.SMS, mergeFields);

        smsService.sendSms(new SmsMessage(toPhone, msg.body()));

        receipt.setSmsSentAt(Instant.now());
        receipt.setSmsSentTo(toPhone);
        receiptRepo.save(receipt);

        audit.log("Receipt", receiptId.toString(), AuditAction.SEND, null, Map.of(
                "channel", "SMS",
                "recipient", maskPhone(toPhone),
                "requestedBy", requestedBy
        ));
    }

    @Override
    @Transactional
    public void deliverPaymentVoucherSms(String tenantId, UUID paymentId, String requestedBy) {
        Payment payment = paymentRepo.findById(paymentId)
                .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                        "Payment not found", "PAYMENT_NOT_FOUND", paymentId));

        String toPhone = phoneDispatcher.resolve(payment.getCreditNote())
                .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                        "Beneficiary phone unavailable", "PAYMENT_RECIPIENT_PHONE_UNRESOLVED", paymentId));

        Map<String, Object> mergeFields = Map.of(
                "beneficiaryName", payment.getCreditNote().getBeneficiaryName(),
                "amount", "₦" + payment.getAmount().toPlainString(),
                "paymentNumber", payment.getReference()
        );

        ComposedMessage msg = composer.compose(
                NotificationTemplateType.PAYMENT_VOUCHER, NotificationChannel.SMS, mergeFields);

        smsService.sendSms(new SmsMessage(toPhone, msg.body()));

        payment.setSmsSentAt(Instant.now());
        payment.setSmsSentTo(toPhone);
        paymentRepo.save(payment);

        audit.log("Payment", paymentId.toString(), AuditAction.SEND, null, Map.of(
                "channel", "SMS",
                "recipient", maskPhone(toPhone),
                "requestedBy", requestedBy
        ));
    }

    /** Mask middle digits: +234 *** *** 5678. Defensive PII reduction in audit_log. */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 8) return phone;
        int n = phone.length();
        return phone.substring(0, Math.min(4, n))
                + " *** *** "
                + phone.substring(n - 4);
    }
}
```

- [ ] **Step 3: Verify compile**

```bash
mvn -pl cia-finance compile -DskipTests -am
```
Expected: BUILD SUCCESS. Tasks 8.1 + 8.2 + 8.3 now form a complete compile unit.

- [ ] **Step 4: Commit 8.1 + 8.2 + 8.3 together**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(finance): Tasks 8.1-8.3 — SMS workflows + activities

Mirror of F7-γ SendReceiptEmailWorkflow + SendPaymentVoucherEmailWorkflow:
  - 2 @WorkflowInterface + 2 impls with @SignalMethod cancel + boolean
    cancelled pre-dispatch check
  - SmsActivities interface + impl with @Transactional deliver methods
  - Audit-after-success: AuditAction.SEND row written exactly once on
    successful smsService.sendSms() return
  - Phone re-resolution at activity-entry time (in case it changed mid-queue)
  - Retry policy: 5min → 2× → 1hr, no max attempts
  - Non-retryable codes: RECEIPT_NOT_FOUND, RECEIPT_RECIPIENT_PHONE_UNRESOLVED
    (and PAYMENT_* equivalents)
  - PII masking in audit_log: +234 *** *** 5678

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8.4: Register SMS workflows on `NotificationsWorkerConfig`

**Files:**

- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/notification/NotificationsWorkerConfig.java` (the renamed worker config from Task 0.3)

- [ ] **Step 1: Add SMS workflow + activity registration**

Inside the existing `@PostConstruct workerSetup()` method, alongside the email workflow registrations:

```java
// Add to NotificationsWorkerConfig.workerSetup():
Worker worker = workerFactory.newWorker(TemporalQueues.NOTIFICATIONS_QUEUE);

// EXISTING (don't remove):
worker.registerWorkflowImplementationTypes(
        SendReceiptEmailWorkflowImpl.class,
        SendPaymentVoucherEmailWorkflowImpl.class,
        SendReceiptSmsWorkflowImpl.class,        // NEW
        SendPaymentVoucherSmsWorkflowImpl.class, // NEW
        PdfDownloadLogRetentionWorkflowImpl.class
);

worker.registerActivitiesImplementations(
        sendReceiptEmailActivitiesImpl,
        sendPaymentVoucherEmailActivitiesImpl,
        smsActivitiesImpl,                       // NEW
        pdfDownloadLogRetentionActivitiesImpl
);
```

Add the corresponding `@Autowired SmsActivitiesImpl smsActivitiesImpl;` field at the class level.

- [ ] **Step 2: Verify compile**

```bash
mvn -pl cia-finance compile -DskipTests
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/notification/NotificationsWorkerConfig.java
git commit -m "$(cat <<'EOF'
feat(finance): Task 8.4 — register SMS workflows + activities on NOTIFICATIONS_QUEUE

SendReceiptSmsWorkflowImpl + SendPaymentVoucherSmsWorkflowImpl now run
on the same NOTIFICATIONS_QUEUE worker as email + F11 PDF retention.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8.5: `SendReceiptSmsWorkflowIT` (5 tests)

**Files:**

- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/sms/SendReceiptSmsWorkflowIT.java`

- [ ] **Step 1: Write the IT (mirror F7-γ SendReceiptEmailWorkflowIT shape with TestWorkflowEnvironment + simulated clock)**

Read the existing `cia-api/src/test/java/com/nubeero/cia/api/finance/email/SendReceiptEmailWorkflowIT.java` for the canonical setup pattern. Mirror it with SMS replacements:

```java
// cia-api/src/test/java/com/nubeero/cia/api/finance/sms/SendReceiptSmsWorkflowIT.java
package com.nubeero.cia.api.finance.sms;

import com.nubeero.cia.api.support.AbstractTemporalIT;
import com.nubeero.cia.finance.sms.SendReceiptSmsWorkflow;
import com.nubeero.cia.finance.sms.SmsActivitiesImpl;
import com.nubeero.cia.finance.sms.SendReceiptSmsWorkflowImpl;
import com.nubeero.cia.notifications.sms.SmsMessage;
import com.nubeero.cia.notifications.sms.SmsService;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SendReceiptSmsWorkflowIT extends AbstractTemporalIT {

    @Autowired SmsActivitiesImpl smsActivitiesImpl;
    @Autowired JdbcTemplate jdbc;
    @MockBean SmsService smsService;

    private TestWorkflowEnvironment env;
    private WorkflowClient client;

    @BeforeEach
    void setup() {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker("notifications-queue");
        worker.registerWorkflowImplementationTypes(SendReceiptSmsWorkflowImpl.class);
        worker.registerActivitiesImplementations(smsActivitiesImpl);
        env.start();
        client = env.getWorkflowClient();
    }

    @AfterEach
    void teardown() { env.close(); }

    @Test
    void happyPath_sendsSmsAndWritesAudit() {
        UUID receiptId = seedReceiptWithCustomerPhone("+2349012345678");

        SendReceiptSmsWorkflow workflow = client.newWorkflowStub(
                SendReceiptSmsWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId("send-receipt-sms-" + receiptId)
                        .setTaskQueue("notifications-queue")
                        .build());

        workflow.send("test-tenant", receiptId, "alice");

        verify(smsService).sendSms(any(SmsMessage.class));
        // Audit row exists with action=SEND, channel=SMS
        Long auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type='Receipt' AND entity_id=? AND action='SEND'",
                Long.class, receiptId);
        assertThat(auditCount).isEqualTo(1L);
    }

    @Test
    void cancelBeforeDispatch_skipsSms() throws Exception {
        UUID receiptId = seedReceiptWithCustomerPhone("+2349012345678");

        var stub = client.newUntypedWorkflowStub(
                "SendReceiptSmsWorkflow",
                WorkflowOptions.newBuilder()
                        .setWorkflowId("send-receipt-sms-" + receiptId)
                        .setTaskQueue("notifications-queue")
                        .build());
        stub.signalWithStart(
                "cancel", new Object[]{},
                new Object[]{"test-tenant", receiptId, "alice"});
        stub.getResult(Void.class);

        verify(smsService, never()).sendSms(any());
        Long sendAuditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type='Receipt' AND entity_id=? AND action='SEND'",
                Long.class, receiptId);
        assertThat(sendAuditCount).isZero();
    }

    @Test
    void nonRetryablePhoneUnresolved_failsWithoutAudit() {
        UUID receiptId = seedReceiptWithCustomerPhone(null);   // no phone

        SendReceiptSmsWorkflow workflow = client.newWorkflowStub(
                SendReceiptSmsWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId("send-receipt-sms-" + receiptId)
                        .setTaskQueue("notifications-queue")
                        .build());

        try {
            workflow.send("test-tenant", receiptId, "alice");
        } catch (Exception expected) {
            // ApplicationFailure with code RECEIPT_RECIPIENT_PHONE_UNRESOLVED
        }
        verify(smsService, never()).sendSms(any());
        Long auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type='Receipt' AND entity_id=? AND action='SEND'",
                Long.class, receiptId);
        assertThat(auditCount).isZero();
    }

    @Test
    void retryableErrorThenSuccess_writesExactlyOneAudit() {
        UUID receiptId = seedReceiptWithCustomerPhone("+2349012345678");
        doThrow(new RuntimeException("transient SMTP failure"))
                .doNothing()
                .when(smsService).sendSms(any());

        SendReceiptSmsWorkflow workflow = client.newWorkflowStub(
                SendReceiptSmsWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId("send-receipt-sms-" + receiptId)
                        .setTaskQueue("notifications-queue")
                        .build());

        // Advance simulated clock past the 5-minute retry interval
        env.sleep(Duration.ofMinutes(6));
        workflow.send("test-tenant", receiptId, "alice");

        verify(smsService, times(2)).sendSms(any());   // first failed, second succeeded
        Long auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type='Receipt' AND entity_id=? AND action='SEND'",
                Long.class, receiptId);
        assertThat(auditCount).isEqualTo(1L);   // exactly one
    }

    @Test
    void preflightReceiptNotFound_failsCleanly() {
        UUID bogusId = UUID.randomUUID();

        SendReceiptSmsWorkflow workflow = client.newWorkflowStub(
                SendReceiptSmsWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId("send-receipt-sms-" + bogusId)
                        .setTaskQueue("notifications-queue")
                        .build());

        try { workflow.send("test-tenant", bogusId, "alice"); } catch (Exception expected) { }
        verify(smsService, never()).sendSms(any());
    }

    /** Helper: insert a Customer + DebitNote + Receipt row, with the customer's phone set as supplied. */
    private UUID seedReceiptWithCustomerPhone(String phone) {
        UUID customerId = UUID.randomUUID();
        UUID dnId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        jdbc.update("INSERT INTO customers(id, first_name, last_name, phone, email, created_at, updated_at) " +
                    "VALUES(?, 'Acme', 'Ltd', ?, 'acme@example.com', NOW(), NOW())",
                customerId, phone);
        // ... seed debit_notes(...) and receipts(...) with appropriate FKs.
        // (Mirror the existing F7-γ SendReceiptEmailWorkflowIT seeding helper exactly.)
        return receiptId;
    }
}
```

Important: the `seedReceiptWithCustomerPhone` helper must mirror the existing F7-γ seeder. Read `SendReceiptEmailWorkflowIT.java` for the exact INSERT chain — it's likely 6–10 lines of JDBC seeding the customer + debit_note + receipt rows.

- [ ] **Step 2: Run the IT — verify it passes**

```bash
mvn -pl cia-api verify -Dit.test='SendReceiptSmsWorkflowIT' -DskipUnitTests=true
```
Expected: 5/5 tests pass.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
test(api): Task 8.5 — SendReceiptSmsWorkflowIT (5 tests)

Mirrors F7-γ's email workflow IT shape with TestWorkflowEnvironment +
simulated clock. Covers: happy path, cancel-before-dispatch,
non-retryable phone-unresolved, retryable error then success
(audit idempotency), and missing-receipt preflight.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8.6: `SendPaymentVoucherSmsWorkflowIT` (6 tests)

**Files:**

- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/sms/SendPaymentVoucherSmsWorkflowIT.java`

- [ ] **Step 1: Write the IT — mirror 8.5 with the 4 dispatcher types**

Same shape as 8.5 but with 4 happy-path variants (CLAIM, COMMISSION, REINSURANCE, ENDORSEMENT credit-note seeding) + cancel + non-retryable.

Read the existing F7-γ `SendPaymentVoucherEmailWorkflowIT.java` for the exact 4-type seeding pattern. Mirror it; swap `email_sent_to` → `sms_sent_to` and `EmailService` mock → `SmsService` mock.

- [ ] **Step 2: Run + Commit**

```bash
mvn -pl cia-api verify -Dit.test='SendPaymentVoucherSmsWorkflowIT' -DskipUnitTests=true
# Expected: 6/6 pass
git add -A
git commit -m "$(cat <<'EOF'
test(api): Task 8.6 — SendPaymentVoucherSmsWorkflowIT (6 tests)

CLAIM / COMMISSION / REINSURANCE / ENDORSEMENT happy paths + cancel +
non-retryable phone-unresolved. Mirrors F7-γ payment-voucher email IT
exactly with SMS swap.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 9 — Receipt + Payment service `requestSms` / `cancelSms` + REST endpoints + ITs

### Task 9.1: `ReceiptService.requestSms` + `cancelSms`

**Files:**

- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptService.java` — add 2 methods + 1 constructor dep

- [ ] **Step 1: Add a `JdbcTemplate` field if not already present + add the methods**

```java
// In ReceiptService.java, alongside the existing requestEmail / cancelEmail methods:

import com.nubeero.cia.finance.sms.SendReceiptSmsWorkflow;
import com.nubeero.cia.finance.notification.NotificationPreflightException;
import com.nubeero.cia.common.audit.AuditAction;

// Constructor gains: WorkflowClient (already injected for email), JdbcTemplate (likely already)

public String requestSms(UUID receiptId) {
    Receipt receipt = receiptRepo.findById(receiptId)
            .orElseThrow(() -> new NotFoundException("Receipt", receiptId.toString()));

    // No PDF gate — SMS doesn't depend on PDF existing.

    String phone = jdbc.queryForObject(
            "SELECT phone FROM customers WHERE id = ?",
            String.class, receipt.getDebitNote().getCustomerId());
    if (phone == null || phone.isBlank()) {
        throw new NotificationPreflightException(
                "RECEIPT_RECIPIENT_PHONE_UNRESOLVED",
                "Customer has no phone on file");
    }

    String workflowId = "send-receipt-sms-" + receiptId;
    SendReceiptSmsWorkflow workflow = workflowClient.newWorkflowStub(
            SendReceiptSmsWorkflow.class,
            WorkflowOptions.newBuilder()
                    .setWorkflowId(workflowId)
                    .setTaskQueue(TemporalQueues.NOTIFICATIONS_QUEUE)
                    .build());
    WorkflowClient.start(workflow::send, tenantId(), receiptId, currentUser());
    return workflowId;
}

public Map<String, Object> cancelSms(UUID receiptId) {
    String workflowId = "send-receipt-sms-" + receiptId;
    try {
        SendReceiptSmsWorkflow workflow = workflowClient.newWorkflowStub(
                SendReceiptSmsWorkflow.class, workflowId);
        workflow.cancel();
    } catch (Exception e) {
        throw new NotificationPreflightException(
                "WORKFLOW_NOT_FOUND",
                "No SMS workflow found for receipt " + receiptId);
    }
    audit.log("Receipt", receiptId.toString(), AuditAction.CANCEL, null, Map.of(
            "workflowId", workflowId,
            "cancelledBy", currentUser(),
            "channel", "SMS"
    ));
    return Map.of("cancelled", true);
}
```

- [ ] **Step 2: Verify compile**

```bash
mvn -pl cia-finance compile -DskipTests
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptService.java
git commit -m "$(cat <<'EOF'
feat(finance): Task 9.1 — ReceiptService.requestSms + cancelSms

Mirrors requestEmail / cancelEmail from F7-γ + F11:
  - requestSms: preflight via customers.phone JDBC, throws
    NotificationPreflightException(RECEIPT_RECIPIENT_PHONE_UNRESOLVED)
    on null/blank phone. Starts SendReceiptSmsWorkflow on
    NOTIFICATIONS_QUEUE; returns workflowId.
  - cancelSms: signals the workflow by id; throws WORKFLOW_NOT_FOUND
    if Temporal can't find it; writes AuditAction.CANCEL.
  - No PDF gate (SMS doesn't depend on PDF existing).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9.2: `PaymentService.requestSms` + `cancelSms`

**Files:**

- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/PaymentService.java`

- [ ] **Step 1: Add the methods (mirror 9.1 but using `BeneficiaryPhoneResolverDispatcher`)**

```java
// PaymentService.java — alongside requestEmail / cancelEmail

import com.nubeero.cia.finance.sms.SendPaymentVoucherSmsWorkflow;
import com.nubeero.cia.finance.sms.BeneficiaryPhoneResolverDispatcher;
import com.nubeero.cia.finance.notification.NotificationPreflightException;
import com.nubeero.cia.common.audit.AuditAction;

// Constructor gains: BeneficiaryPhoneResolverDispatcher phoneDispatcher

public String requestSms(UUID paymentId) {
    Payment payment = paymentRepo.findById(paymentId)
            .orElseThrow(() -> new NotFoundException("Payment", paymentId.toString()));

    String phone = phoneDispatcher.resolve(payment.getCreditNote())
            .filter(p -> p != null && !p.isBlank())
            .orElseThrow(() -> new NotificationPreflightException(
                    "PAYMENT_RECIPIENT_PHONE_UNRESOLVED",
                    "Beneficiary has no phone on file"));

    String workflowId = "send-payment-voucher-sms-" + paymentId;
    SendPaymentVoucherSmsWorkflow workflow = workflowClient.newWorkflowStub(
            SendPaymentVoucherSmsWorkflow.class,
            WorkflowOptions.newBuilder()
                    .setWorkflowId(workflowId)
                    .setTaskQueue(TemporalQueues.NOTIFICATIONS_QUEUE)
                    .build());
    WorkflowClient.start(workflow::send, tenantId(), paymentId, currentUser());
    return workflowId;
}

public Map<String, Object> cancelSms(UUID paymentId) {
    String workflowId = "send-payment-voucher-sms-" + paymentId;
    try {
        SendPaymentVoucherSmsWorkflow workflow = workflowClient.newWorkflowStub(
                SendPaymentVoucherSmsWorkflow.class, workflowId);
        workflow.cancel();
    } catch (Exception e) {
        throw new NotificationPreflightException(
                "WORKFLOW_NOT_FOUND",
                "No SMS workflow found for payment " + paymentId);
    }
    audit.log("Payment", paymentId.toString(), AuditAction.CANCEL, null, Map.of(
            "workflowId", workflowId,
            "cancelledBy", currentUser(),
            "channel", "SMS"
    ));
    return Map.of("cancelled", true);
}
```

- [ ] **Step 2: Verify compile + commit**

```bash
mvn -pl cia-finance compile -DskipTests
git add cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/PaymentService.java
git commit -m "$(cat <<'EOF'
feat(finance): Task 9.2 — PaymentService.requestSms + cancelSms

Mirrors 9.1 with BeneficiaryPhoneResolverDispatcher in place of direct
customer JDBC. Error code on missing phone:
PAYMENT_RECIPIENT_PHONE_UNRESOLVED → 422.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9.3: `ReceiptController` + `PaymentController` — 4 new endpoints

**Files:**

- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptController.java` (add 2 endpoints)
- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/PaymentController.java` (add 2 endpoints)

- [ ] **Step 1: Add endpoints to `ReceiptController`**

```java
// Inside ReceiptController, alongside existing /email and /email/cancel endpoints:

@PostMapping("/debit-notes/{dnId}/receipts/{id}/sms")
@PreAuthorize("hasAuthority('FINANCE_UPDATE')")
public ResponseEntity<ApiResponse<Map<String, Object>>> requestSms(
        @PathVariable UUID dnId,
        @PathVariable UUID id) {
    String workflowId = receiptService.requestSms(id);
    return new ResponseEntity<>(
            ApiResponse.success(Map.of("workflowId", workflowId)),
            HttpStatus.ACCEPTED);
}

@PostMapping("/debit-notes/{dnId}/receipts/{id}/sms/cancel")
@PreAuthorize("hasAuthority('FINANCE_UPDATE')")
public ResponseEntity<ApiResponse<Map<String, Object>>> cancelSms(
        @PathVariable UUID dnId,
        @PathVariable UUID id) {
    return new ResponseEntity<>(
            ApiResponse.success(receiptService.cancelSms(id)),
            HttpStatus.ACCEPTED);
}
```

- [ ] **Step 2: Mirror for `PaymentController` (replace `/debit-notes/{dnId}/receipts/{id}` with `/credit-notes/{cnId}/payments/{id}`)**

- [ ] **Step 3: Compile + commit**

```bash
mvn -pl cia-finance compile -DskipTests
git add -A
git commit -m "$(cat <<'EOF'
feat(finance): Task 9.3 — 4 SMS REST endpoints on Receipt + Payment controllers

POST /api/v1/debit-notes/{dnId}/receipts/{id}/sms          (FINANCE_UPDATE)
POST /api/v1/debit-notes/{dnId}/receipts/{id}/sms/cancel   (FINANCE_UPDATE)
POST /api/v1/credit-notes/{cnId}/payments/{id}/sms         (FINANCE_UPDATE)
POST /api/v1/credit-notes/{cnId}/payments/{id}/sms/cancel  (FINANCE_UPDATE)

All return 202 + { workflowId } or { cancelled: true } on success;
422 + { errorCode, message } on preflight failure.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9.4: `ReceiptControllerSmsIT` (4 tests)

**Files:**

- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/sms/ReceiptControllerSmsIT.java`

- [ ] **Step 1: Write the IT — mirror existing `ReceiptControllerEmailIT` shape**

```java
// cia-api/src/test/java/com/nubeero/cia/api/finance/sms/ReceiptControllerSmsIT.java
package com.nubeero.cia.api.finance.sms;

import com.nubeero.cia.api.support.AbstractApiIT;
import com.nubeero.cia.finance.sms.SendReceiptSmsWorkflow;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WithMockUser(authorities = {"FINANCE_UPDATE"})
class ReceiptControllerSmsIT extends AbstractApiIT {

    @Autowired MockMvc mvc;
    @MockBean WorkflowClient workflowClient;

    @BeforeEach
    void stubWorkflowClient() {
        SendReceiptSmsWorkflow stub = Mockito.mock(SendReceiptSmsWorkflow.class);
        Mockito.when(workflowClient.newWorkflowStub(Mockito.eq(SendReceiptSmsWorkflow.class), Mockito.anyString()))
                .thenReturn(stub);
    }

    @Test
    void happyPath_returns202WithWorkflowId() throws Exception {
        UUID receiptId = seedReceiptWithCustomerPhone("+2349012345678");
        UUID dnId = receiptDebitNoteId(receiptId);
        mvc.perform(post("/api/v1/debit-notes/{dnId}/receipts/{id}/sms", dnId, receiptId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.workflowId", startsWith("send-receipt-sms-")));
    }

    @Test
    void phoneUnresolved_returns422() throws Exception {
        UUID receiptId = seedReceiptWithCustomerPhone(null);
        UUID dnId = receiptDebitNoteId(receiptId);
        mvc.perform(post("/api/v1/debit-notes/{dnId}/receipts/{id}/sms", dnId, receiptId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code", is("RECEIPT_RECIPIENT_PHONE_UNRESOLVED")));
    }

    @Test
    void unknownReceipt_returns422() throws Exception {
        UUID bogusId = UUID.randomUUID();
        UUID dnId = UUID.randomUUID();
        mvc.perform(post("/api/v1/debit-notes/{dnId}/receipts/{id}/sms", dnId, bogusId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code", anyOf(is("RECEIPT_NOT_FOUND"), is("NOT_FOUND"))));
    }

    @Test
    @WithMockUser(authorities = {"FINANCE_VIEW"})    // no FINANCE_UPDATE
    void withoutAuthority_returns403() throws Exception {
        UUID receiptId = seedReceiptWithCustomerPhone("+2349012345678");
        UUID dnId = receiptDebitNoteId(receiptId);
        mvc.perform(post("/api/v1/debit-notes/{dnId}/receipts/{id}/sms", dnId, receiptId))
                .andExpect(status().isForbidden());
    }

    // Helpers — same as Task 8.5 helper, mirror the F7-γ ReceiptControllerEmailIT seeder
    private UUID seedReceiptWithCustomerPhone(String phone) { /* see Task 8.5 */ return UUID.randomUUID(); }
    private UUID receiptDebitNoteId(UUID receiptId) { /* JDBC SELECT */ return UUID.randomUUID(); }
}
```

- [ ] **Step 2: Run + Commit**

```bash
mvn -pl cia-api verify -Dit.test='ReceiptControllerSmsIT' -DskipUnitTests=true
# Expected: 4/4 pass
git add -A
git commit -m "$(cat <<'EOF'
test(api): Task 9.4 — ReceiptControllerSmsIT (4 tests)

POST /sms happy 202 + workflowId; 422 PHONE_UNRESOLVED; 422 NOT_FOUND;
403 without FINANCE_UPDATE.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9.5: `PaymentControllerSmsIT` (4 tests)

**Files:**

- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/sms/PaymentControllerSmsIT.java`

- [ ] **Step 1: Write the IT — mirror 9.4 with COMMISSION credit-note seeding**

Same shape as 9.4. Use the existing `PaymentControllerEmailIT` as the reference for the seeding helper. Error code: `PAYMENT_RECIPIENT_PHONE_UNRESOLVED`.

- [ ] **Step 2: Run + Commit**

```bash
mvn -pl cia-api verify -Dit.test='PaymentControllerSmsIT' -DskipUnitTests=true
git add -A
git commit -m "$(cat <<'EOF'
test(api): Task 9.5 — PaymentControllerSmsIT (4 tests)

POST /sms happy 202 + workflowId; 422 PAYMENT_RECIPIENT_PHONE_UNRESOLVED;
422 PAYMENT_NOT_FOUND; 403 without FINANCE_UPDATE.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 10 — Cancel SMS ITs

### Task 10.1: `CancelSmsWorkflowIT` (2 tests)

**Files:**

- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/sms/CancelSmsWorkflowIT.java`

- [ ] **Step 1: Write the IT — mirror F11's `CancelEmailWorkflowIT`**

```java
// cia-api/src/test/java/com/nubeero/cia/api/finance/sms/CancelSmsWorkflowIT.java
// Pattern: signalWithStart deterministic cancellation test.
// See F11's CancelEmailWorkflowIT for the canonical structure;
// this is the SMS variant.

package com.nubeero.cia.api.finance.sms;

import com.nubeero.cia.api.support.AbstractTemporalIT;
import com.nubeero.cia.finance.ReceiptService;
import com.nubeero.cia.notifications.sms.SmsService;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CancelSmsWorkflowIT extends AbstractTemporalIT {

    @Autowired ReceiptService receiptService;
    @MockBean SmsService smsService;

    @Test
    void cancelBeforeStart_noSmsAndNoSendAudit() {
        UUID receiptId = seedReceiptWithCustomerPhone("+2349012345678");
        WorkflowStub stub = client.newUntypedWorkflowStub(
                "SendReceiptSmsWorkflow",
                WorkflowOptions.newBuilder()
                        .setWorkflowId("send-receipt-sms-" + receiptId)
                        .setTaskQueue("notifications-queue")
                        .build());
        stub.signalWithStart("cancel", new Object[]{}, new Object[]{"test-tenant", receiptId, "alice"});
        stub.getResult(Void.class);

        verify(smsService, never()).sendSms(org.mockito.ArgumentMatchers.any());
        // Audit table should have CANCEL but not SEND
        Long sendCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type='Receipt' AND entity_id=? AND action='SEND'",
                Long.class, receiptId);
        assertThat(sendCount).isZero();
    }

    @Test
    void cancelForUnknownWorkflow_throwsWorkflowNotFound() {
        UUID neverStarted = UUID.randomUUID();
        assertThatThrownBy(() -> receiptService.cancelSms(neverStarted))
            .hasMessageContaining("WORKFLOW_NOT_FOUND");
    }

    // helper — mirror Task 8.5 / 9.4
    private UUID seedReceiptWithCustomerPhone(String phone) { /* ... */ return UUID.randomUUID(); }
}
```

- [ ] **Step 2: Run + Commit**

```bash
mvn -pl cia-api verify -Dit.test='CancelSmsWorkflowIT' -DskipUnitTests=true
# Expected: 2/2 pass
git add -A
git commit -m "$(cat <<'EOF'
test(api): Task 10.1 — CancelSmsWorkflowIT (2 tests)

signalWithStart deterministic test of the SMS cancel signal:
  - cancel before activity dispatch → no SmsService.sendSms invocation,
    no SEND audit row
  - cancel for unknown workflow → WORKFLOW_NOT_FOUND surfaced via
    NotificationPreflightException

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10.2: `CancelSmsControllerIT` (2 tests)

**Files:**

- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/sms/CancelSmsControllerIT.java`

- [ ] **Step 1: Write the IT**

```java
// cia-api/src/test/java/com/nubeero/cia/api/finance/sms/CancelSmsControllerIT.java
package com.nubeero.cia.api.finance.sms;

import com.nubeero.cia.api.support.AbstractApiIT;
import com.nubeero.cia.finance.sms.SendReceiptSmsWorkflow;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WithMockUser(authorities = {"FINANCE_UPDATE"})
class CancelSmsControllerIT extends AbstractApiIT {

    @Autowired MockMvc mvc;
    @MockBean WorkflowClient workflowClient;

    @BeforeEach
    void stubClient() {
        Mockito.when(workflowClient.newWorkflowStub(Mockito.eq(SendReceiptSmsWorkflow.class), Mockito.anyString()))
                .thenReturn(Mockito.mock(SendReceiptSmsWorkflow.class));
    }

    @Test
    void cancelHappy_202AndAuditRow() throws Exception {
        UUID receiptId = seedReceipt();
        UUID dnId = receiptDebitNoteId(receiptId);
        mvc.perform(post("/api/v1/debit-notes/{dnId}/receipts/{id}/sms/cancel", dnId, receiptId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.cancelled", is(true)));
        // Verify CANCEL audit row
        Long cancelCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type='Receipt' AND entity_id=? AND action='CANCEL'",
                Long.class, receiptId);
        assertThat(cancelCount).isEqualTo(1L);
    }

    @Test
    @WithMockUser(authorities = {"FINANCE_VIEW"})
    void withoutAuthority_403() throws Exception {
        UUID receiptId = seedReceipt();
        UUID dnId = receiptDebitNoteId(receiptId);
        mvc.perform(post("/api/v1/debit-notes/{dnId}/receipts/{id}/sms/cancel", dnId, receiptId))
                .andExpect(status().isForbidden());
    }

    private UUID seedReceipt() { return UUID.randomUUID(); }
    private UUID receiptDebitNoteId(UUID receiptId) { return UUID.randomUUID(); }
}
```

- [ ] **Step 2: Run + Commit**

```bash
mvn -pl cia-api verify -Dit.test='CancelSmsControllerIT' -DskipUnitTests=true
git add -A
git commit -m "$(cat <<'EOF'
test(api): Task 10.2 — CancelSmsControllerIT (2 tests)

POST /sms/cancel happy path → 202 + CANCEL audit row;
403 without FINANCE_UPDATE.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 11 — Frontend api-client schemas + fetchers

### Task 11.1: `NotificationTemplate*` zod schemas + fetchers in `setup.ts`

**Files:**

- Modify: `cia-frontend/packages/api-client/src/modules/setup.ts`

- [ ] **Step 1: Add schemas at the top of the Enums section + fetchers**

```typescript
// In cia-frontend/packages/api-client/src/modules/setup.ts

// === Notification template enums ===
export const NotificationTemplateTypeSchema = z.enum(['RECEIPT', 'PAYMENT_VOUCHER']);
export type NotificationTemplateType = z.infer<typeof NotificationTemplateTypeSchema>;

export const NotificationChannelSchema = z.enum(['EMAIL', 'SMS']);
export type NotificationChannel = z.infer<typeof NotificationChannelSchema>;

// === Notification template DTOs ===
export const NotificationTemplateResponseSchema = z.object({
  id: z.string().uuid(),
  templateType: NotificationTemplateTypeSchema,
  channel: NotificationChannelSchema,
  subjectTemplate: z.string().nullable(),
  bodyTemplate: z.string().nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
  createdBy: z.string().nullable(),
  updatedBy: z.string().nullable(),
});
export type NotificationTemplateResponse = z.infer<typeof NotificationTemplateResponseSchema>;

export const NotificationTemplateRequestSchema = z.object({
  templateType: NotificationTemplateTypeSchema,
  channel: NotificationChannelSchema,
  subjectTemplate: z.string().max(500).nullable().optional(),
  bodyTemplate: z.string().max(1000).nullable().optional(),
});
export type NotificationTemplateRequest = z.infer<typeof NotificationTemplateRequestSchema>;

export const NotificationTemplateDefaultsResponseSchema = z.object({
  defaults: z.array(z.object({
    templateType: NotificationTemplateTypeSchema,
    channel: NotificationChannelSchema,
    subjectTemplate: z.string().nullable(),
    bodyTemplate: z.string(),
  })),
});
export type NotificationTemplateDefaultsResponse = z.infer<typeof NotificationTemplateDefaultsResponseSchema>;

export const NotificationTemplateVariablesResponseSchema = z.object({
  variables: z.array(z.object({
    templateType: NotificationTemplateTypeSchema,
    channel: NotificationChannelSchema,
    allowedVariables: z.array(z.string()),
  })),
});
export type NotificationTemplateVariablesResponse = z.infer<typeof NotificationTemplateVariablesResponseSchema>;

export const NotificationTemplatePreviewRequestSchema = z.object({
  templateType: NotificationTemplateTypeSchema,
  channel: NotificationChannelSchema,
  subjectTemplate: z.string().nullable(),
  bodyTemplate: z.string().nullable(),
  sampleValues: z.record(z.string(), z.unknown()),
});
export type NotificationTemplatePreviewRequest = z.infer<typeof NotificationTemplatePreviewRequestSchema>;

export const NotificationTemplatePreviewResponseSchema = z.object({
  subject: z.string().nullable(),
  body: z.string(),
});
export type NotificationTemplatePreviewResponse = z.infer<typeof NotificationTemplatePreviewResponseSchema>;

// === Fetchers ===
export async function listNotificationTemplates() {
  return validatedList(
    '/api/v1/setup/notification-templates',
    NotificationTemplateResponseSchema);
}

export async function getNotificationTemplateDefaults() {
  return validatedGet(
    '/api/v1/setup/notification-templates/defaults',
    NotificationTemplateDefaultsResponseSchema);
}

export async function getNotificationTemplateVariables() {
  return validatedGet(
    '/api/v1/setup/notification-templates/variables',
    NotificationTemplateVariablesResponseSchema);
}

export async function createNotificationTemplate(req: NotificationTemplateRequest) {
  return validatedPost(
    '/api/v1/setup/notification-templates',
    req,
    NotificationTemplateResponseSchema);
}

export async function updateNotificationTemplate(id: string, req: NotificationTemplateRequest) {
  return validatedPut(
    `/api/v1/setup/notification-templates/${id}`,
    req,
    NotificationTemplateResponseSchema);
}

export async function deleteNotificationTemplate(id: string) {
  return apiClient.delete(`/api/v1/setup/notification-templates/${id}`);
}

export async function previewNotificationTemplate(req: NotificationTemplatePreviewRequest) {
  return validatedPost(
    '/api/v1/setup/notification-templates/preview',
    req,
    NotificationTemplatePreviewResponseSchema);
}
```

- [ ] **Step 2: Run typecheck + DTO drift check**

```bash
pnpm --filter @cia/api-client typecheck
node cia-frontend/scripts/check-dto-drift.mjs
```
Expected: clean.

- [ ] **Step 3: Commit**

```bash
git add cia-frontend/packages/api-client/src/modules/setup.ts
git commit -m "$(cat <<'EOF'
feat(api-client): Task 11.1 — NotificationTemplate schemas + 7 fetchers

zod schemas for the new Setup endpoints. Mirror the backend DTOs
exactly so check-dto-drift.mjs stays clean.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11.2: Finance api-client — projection field additions + SMS fetchers

**Files:**

- Modify: `cia-frontend/packages/api-client/src/modules/finance.ts`

- [ ] **Step 1: Add the 3 new nullable fields to both list-item schemas**

In `ReceiptListItemResponseSchema` and `PaymentListItemResponseSchema`, after the existing `emailSentTo` field, add:

```typescript
recipientPhone: z.string().nullable(),
smsSentAt: z.string().nullable(),
smsSentTo: z.string().nullable(),
```

- [ ] **Step 2: Add SMS fetchers (mirror existing emailReceipt / cancelReceiptEmail shape)**

```typescript
export async function smsReceipt(dnId: string, receiptId: string) {
  return validatedPost(
    `/api/v1/debit-notes/${dnId}/receipts/${receiptId}/sms`,
    {},
    EmailWorkflowResponseSchema);    // same { workflowId } envelope
}

export async function cancelReceiptSms(dnId: string, receiptId: string) {
  return validatedPost(
    `/api/v1/debit-notes/${dnId}/receipts/${receiptId}/sms/cancel`,
    {},
    EmailCancelResponseSchema);
}

export async function smsPayment(cnId: string, paymentId: string) {
  return validatedPost(
    `/api/v1/credit-notes/${cnId}/payments/${paymentId}/sms`,
    {},
    EmailWorkflowResponseSchema);
}

export async function cancelPaymentSms(cnId: string, paymentId: string) {
  return validatedPost(
    `/api/v1/credit-notes/${cnId}/payments/${paymentId}/sms/cancel`,
    {},
    EmailCancelResponseSchema);
}
```

- [ ] **Step 3: Run typecheck + drift check + commit**

```bash
pnpm --filter @cia/api-client typecheck
node cia-frontend/scripts/check-dto-drift.mjs
git add cia-frontend/packages/api-client/src/modules/finance.ts
git commit -m "$(cat <<'EOF'
feat(api-client): Task 11.2 — SMS fetchers + recipientPhone/smsSentAt/smsSentTo

Receipt + Payment list-item schemas gain 3 nullable fields each.
4 new fetchers mirror the email shape exactly.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 12 — Frontend Setup UI: Notification Templates page + editor + sidebar

### Task 12.1: `useNotificationTemplates` hook bundle

**Files:**

- Create: `cia-frontend/apps/back-office/src/modules/setup/hooks/useNotificationTemplates.ts`

- [ ] **Step 1: Write the hooks bundle (mirror the F7-γ useReceipts hook shape)**

Six hooks: `useNotificationTemplates` (list), `useNotificationTemplateDefaults` (staleTime: Infinity), `useNotificationTemplateVariables` (staleTime: Infinity), `useSaveNotificationTemplate` (create-or-update by presence of `id`), `useResetNotificationTemplate` (delete), `usePreviewNotificationTemplate` (mutation). The save mutation maps `UNKNOWN_TEMPLATE_VARIABLE` + `TEMPLATE_TYPE_CHANNEL_CONFLICT` error codes to specific toast copy; otherwise falls back to `errors[0].message`. Invalidate queryKey `['setup', 'notification-templates']` on success.

Use the shape established in F7-γ `cia-frontend/apps/back-office/src/modules/finance/hooks/useReceipts.ts` for the toast + invalidate pattern — same `onError` discriminated on `err.response.data.errors[0].code`.

- [ ] **Step 2: Run typecheck + commit**

```bash
pnpm --filter @cia/back-office typecheck
git add cia-frontend/apps/back-office/src/modules/setup/hooks/useNotificationTemplates.ts
git commit -m "$(cat <<'EOF'
feat(back-office): Task 12.1 — useNotificationTemplates hook bundle

Six hooks (list / defaults / variables / save / reset / preview).
Save mutation maps error codes to user-friendly toast copy.
Defaults + variables marked staleTime:Infinity (JAR-frozen).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12.2: `NotificationTemplatesPage` (4-row list view)

**Files:**

- Create: `cia-frontend/apps/back-office/src/modules/setup/pages/notifications/NotificationTemplatesPage.tsx`

- [ ] **Step 1: Write the page**

A `PageHeader` + a 4-row table. Each row has columns:
- Template (label: `Receipt` / `Payment Voucher`)
- Channel (label: `Email` / `SMS`)
- State badge (`● Overridden` filled / `○ Default` outline)
- Last edited timestamp

Row is clickable; click sets the `editing` state to `{ templateType, channel, override }` which opens the editor sheet. Override lookup: join the 4-cell grid of `(type, channel)` combinations against the `useNotificationTemplates()` query result.

Shape mirrors the existing `Organisations` tab pattern (`cia-frontend/apps/back-office/src/modules/setup/pages/organisations/*.tsx`) — open existing files for the exact JSX idiom.

- [ ] **Step 2: Don't commit yet — bundle with 12.3 (the editor sheet depends on this page's state plumbing)**

---

### Task 12.3: `NotificationTemplateEditorSheet` — split-pane editor with live preview

**Files:**

- Create: `cia-frontend/apps/back-office/src/modules/setup/pages/notifications/NotificationTemplateEditorSheet.tsx`

- [ ] **Step 1: Write the editor sheet — wide right-edge Sheet (sm:max-w-[60vw])**

Layout (grid-cols-2):

**Left pane (editor):**
- Subject `<Input>` (monospaced, hidden when channel === 'SMS')
- Body `<Textarea>` (monospaced, min-h-[300px])
- If channel === 'SMS': character + segment counter below textarea (`{chars} chars · {segments} segment{s}` — computed against `previewMut.data?.data.body` length)
- "Available variables" list with per-variable `[Insert]` button (writes `{{varName}}` at current cursor position via `textareaRef.current.selectionStart`)

**Right pane (preview):**
- For EMAIL: render the subject as a `<div className="font-medium text-sm">`, then a `<Separator>`, then the body. The body is rendered inside a **sandboxed iframe** to prevent self-XSS — see Step 2.
- For SMS: render the body as `<pre className="whitespace-pre-wrap text-sm">{previewMut.data?.data.body}</pre>` — pure text rendering, no HTML interpretation.

**Footer:**
- Reset to default button (destructive, opens `ConfirmDeleteDialog`, disabled when no existing override)
- Cancel + Save & activate buttons on the right

State plumbing:
- `subjectTemplate` / `bodyTemplate` `useState` strings
- `useEffect` initialises them from `existingOverride` if present, else from `defaultsEntry`
- Debounced preview: `useEffect` with 200ms `setTimeout` firing `previewMut.mutate({...})` on each change
- `isOverridden` derived from comparing current state vs defaults — drives the state badge in the header

Save handler computes which fields actually differ from defaults and sends only those (null for unchanged) so a tenant who edits only body doesn't get a phantom subject override.

- [ ] **Step 2: Sandboxed-iframe preview pattern (HTML email preview without XSS risk)**

Instead of `dangerouslySetInnerHTML` (which executes any `<script>` the tenant admin might type while editing — self-XSS in the admin's own browser), render the HTML body inside a sandboxed iframe:

```typescript
// Inside the right preview pane, for channel === 'EMAIL':
<iframe
  title="Email preview"
  className="w-full min-h-[400px] border-0 bg-white"
  srcDoc={previewMut.data?.data.body ?? ''}
  sandbox=""    // empty string = ALL restrictions enforced (no scripts, no forms, no top-nav, no same-origin)
/>
```

The empty `sandbox=""` attribute means:
- No JavaScript execution inside the iframe (defeats any `<script>` the tenant types)
- No form submission, no popups, no navigation, no plugin embedding
- The iframe gets a unique origin (no `same-origin`) so it can't even read the parent document's cookies/storage

`srcDoc` lets us inline the HTML directly without serving via blob URL or data URL. This is the standard pattern for email-preview UIs.

For SMS bodies, no iframe needed — `<pre className="whitespace-pre-wrap">` renders the plain text safely (React's default escaping prevents any HTML interpretation).

- [ ] **Step 3: Typecheck + commit 12.2 + 12.3 together**

```bash
pnpm --filter @cia/back-office typecheck
git add cia-frontend/apps/back-office/src/modules/setup/pages/notifications/
git commit -m "$(cat <<'EOF'
feat(back-office): Tasks 12.2 + 12.3 — Notification Templates list + editor

NotificationTemplatesPage: 4-row list (template × channel) with state
badge + last-edited timestamp; clickable rows open the editor sheet.

NotificationTemplateEditorSheet: ~60vw right-edge Sheet, split-pane:
  - Left: subject input (email only) + body textarea + variable
    picker with [Insert] action; SMS char/segment counter below
    the body textarea
  - Right: live-preview pane. For EMAIL, renders via sandboxed iframe
    (srcDoc + sandbox="") — defeats self-XSS if a tenant admin types
    <script> while editing; iframe has unique origin + no JS.
    For SMS, renders as <pre> plain text (React's default escaping).
  - Footer: Reset to default (destructive, opens ConfirmDeleteDialog),
    Cancel, Save & activate

Preview re-renders on every keystroke (200ms debounced) via the
backend POST /preview endpoint — single source of truth so what the
editor shows is exactly what customers will receive.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12.4: Sidebar nav entry + route

**Files:**

- Modify: `cia-frontend/apps/back-office/src/app/router.tsx` (add `/setup/notification-templates` route)
- Modify: `cia-frontend/apps/back-office/src/app/layout/Sidebar.tsx` (add nav entry under Setup)

- [ ] **Step 1: Add the route**

In `router.tsx`, find the existing setup module routes and add a lazy-loaded `NotificationTemplatesPage` route. Follow the existing pattern used by the other Setup pages.

- [ ] **Step 2: Add the Sidebar nav entry**

In `Sidebar.tsx`, find the Setup nav group and add a new `NavLink` to `/setup/notification-templates` with `Notification01Icon` from `@hugeicons/core-free-icons`. Mirror the existing nav entries in shape.

- [ ] **Step 3: Compile + commit**

```bash
pnpm --filter @cia/back-office typecheck
git add cia-frontend/apps/back-office/src/app/router.tsx \
        cia-frontend/apps/back-office/src/app/layout/Sidebar.tsx
git commit -m "$(cat <<'EOF'
feat(back-office): Task 12.4 — Notification Templates route + Sidebar entry

Adds /setup/notification-templates route + Sidebar nav entry under the
Setup group with Notification01Icon from hugeicons.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 13 — Frontend Finance UI: SmsConfirmDialog + 4 surface updates

### Task 13.1: `SmsConfirmDialog` + `formatPhone` util + SMS hooks

**Files:**

- Create: `cia-frontend/apps/back-office/src/modules/finance/utils/formatPhone.ts`
- Create: `cia-frontend/apps/back-office/src/modules/finance/pages/SmsConfirmDialog.tsx`
- Modify: `cia-frontend/apps/back-office/src/modules/finance/hooks/useReceipts.ts` (add `useSendReceiptSms` + `useCancelReceiptSms`)
- Modify: `cia-frontend/apps/back-office/src/modules/finance/hooks/usePayments.ts` (add `useSendPaymentSms` + `useCancelPaymentSms`)

- [ ] **Step 1: Write `formatPhone(raw)` — light Nigerian E.164 grouping**

```typescript
// cia-frontend/apps/back-office/src/modules/finance/utils/formatPhone.ts
export function formatPhone(raw: string | null): string {
  if (!raw) return '—';
  const m = raw.match(/^\+234(\d{3})(\d{3})(\d{4})$/);
  if (m) return `+234 ${m[1]} ${m[2]} ${m[3]}`;
  return raw;
}
```

- [ ] **Step 2: Write `SmsConfirmDialog` — mirror of `EmailConfirmDialog`**

Props: `open`, `onOpenChange`, `recipientPhone: string | null`, `documentLabel: string`, `isPending: boolean`, `onConfirm: () => void`.

JSX shape: a `<Dialog>` with `<DialogTitle>Send SMS</DialogTitle>`, a description line showing `Send {documentLabel} via SMS to <strong>{formatPhone(recipientPhone)}</strong>. The message is queued and delivered in the background.`, footer with Cancel + Send buttons. Send disabled while `isPending` OR when `recipientPhone === null`.

Read `cia-frontend/apps/back-office/src/modules/finance/pages/EmailConfirmDialog.tsx` for the canonical shape — copy that file structure, swap email→sms wording.

- [ ] **Step 3: Add `useSendReceiptSms` + `useCancelReceiptSms` to `useReceipts.ts`**

Mirror of existing `useEmailReceipt` / `useCancelReceiptEmail` from F7-γ. Mutation function calls `smsReceipt(dnId, receiptId)`. On success: invalidate `['receipts']` + `toast.success('SMS queued')`. On error: discriminate `err.response.data.errors[0].code`:
- `RECEIPT_RECIPIENT_PHONE_UNRESOLVED` → `toast.error('No phone number on file for this customer')`
- `RECEIPT_NOT_FOUND` → `toast.error('Receipt not found')`
- `WORKFLOW_NOT_FOUND` (cancel only) → `toast.error('No pending SMS to cancel — it may have already completed.')`
- Otherwise → `toast.error(errors[0]?.message ?? 'Failed to queue SMS')`

Mirror in `usePayments.ts` for `useSendPaymentSms` + `useCancelPaymentSms` with `PAYMENT_*` error codes.

- [ ] **Step 4: Compile + commit**

```bash
pnpm --filter @cia/back-office typecheck
git add cia-frontend/apps/back-office/src/modules/finance/
git commit -m "$(cat <<'EOF'
feat(back-office): Task 13.1 — SmsConfirmDialog + formatPhone + 4 SMS hooks

Mirror of F7-γ's EmailConfirmDialog + useEmailReceipt/usePayment +
useCancelReceiptEmail/usePayment. formatPhone util does light Nigerian
E.164 grouping; raw passthrough for non-Nigerian numbers.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 13.2: Wire SMS row-action into `ReceiptsListSection`

**Files:**

- Modify: `cia-frontend/apps/back-office/src/modules/finance/pages/receivables/ReceiptsListSection.tsx`

- [ ] **Step 1: Add SMS button + dialog state alongside the existing Email button**

The pattern matches F7-γ's email wiring exactly — see the existing Email button block in this file as the canonical reference. New code adds:

- `useSendReceiptSms` hook usage
- `smsTarget` `useState` slot of shape `{ dnId, receiptId, reference, recipientPhone }`
- New outline `<Button>SMS</Button>` between Email and Download in the per-row actions column, gated on `r.pdfPath && r.recipientPhone`
- New "Last SMS'd …" badge below the existing "Last emailed …" line
- New `<SmsConfirmDialog>` mounted at the component bottom

- [ ] **Step 2: Typecheck + commit**

```bash
pnpm --filter @cia/back-office typecheck
git add cia-frontend/apps/back-office/src/modules/finance/pages/receivables/ReceiptsListSection.tsx
git commit -m "$(cat <<'EOF'
feat(back-office): Task 13.2 — SMS row-action on ReceiptsListSection

New SMS button between Email and Download on each receipt row.
Gated on pdfPath !== null && recipientPhone !== null.
Opens SmsConfirmDialog → useSendReceiptSms mutation.
"Last SMS'd" badge below the existing "Last emailed" line.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 13.3: Wire SMS into `PaymentsListSection` + 2 nested detail dialogs

**Files:**

- Modify: `cia-frontend/apps/back-office/src/modules/finance/pages/payables/PaymentsListSection.tsx`
- Modify: `cia-frontend/apps/back-office/src/modules/finance/pages/receivables/DebitNoteDetailDialog.tsx`
- Modify: `cia-frontend/apps/back-office/src/modules/finance/pages/payables/CreditNoteDetailDialog.tsx`

- [ ] **Step 1: Apply Task 13.2's pattern to all 3 surfaces**

Mechanical mirror of 13.2:

- `PaymentsListSection` — swap `useSendReceiptSms` → `useSendPaymentSms`; `r.debitNoteId` → `p.creditNoteId`
- `DebitNoteDetailDialog` — the nested receipts list inside the dialog gets per-row SMS button; mount the dialog inside the existing dialog
- `CreditNoteDetailDialog` — same for nested payments

- [ ] **Step 2: Typecheck + commit**

```bash
pnpm --filter @cia/back-office typecheck
git add cia-frontend/apps/back-office/src/modules/finance/pages/
git commit -m "$(cat <<'EOF'
feat(back-office): Task 13.3 — SMS row-actions on PaymentsListSection + 2 detail dialogs

Mirror of 13.2 across the remaining 3 finance surfaces:
  - PaymentsListSection (flat payments list)
  - DebitNoteDetailDialog (nested receipts per DN)
  - CreditNoteDetailDialog (nested payments per CN)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 14 — Frontend Vitest tests (3 tests)

### Task 14.1: `useNotificationTemplates.test.ts`

**Files:**

- Create: `cia-frontend/apps/back-office/src/modules/setup/hooks/useNotificationTemplates.test.ts`

- [ ] **Step 1: Write the test**

Mirror the F11 `useRecentDownloads.test.ts` shape (`vi.mock('@cia/api-client')` + `QueryClientProvider` wrapper + `// allow-mock:` comments to satisfy `check-api-wiring.sh`).

```typescript
// cia-frontend/apps/back-office/src/modules/setup/hooks/useNotificationTemplates.test.ts
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import React from 'react';
import { useNotificationTemplates } from './useNotificationTemplates';

vi.mock('@cia/api-client', () => ({
  listNotificationTemplates: vi.fn(),
}));

// eslint-disable-next-line import/first
import { listNotificationTemplates } from '@cia/api-client';

function wrapper({ children }: { children: React.ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return React.createElement(QueryClientProvider, { client: queryClient }, children);
}

describe('useNotificationTemplates', () => {
  beforeEach(() => { vi.clearAllMocks(); });

  it('returns the envelope from listNotificationTemplates', async () => {
    // allow-mock: Vitest fixture for hook test
    const mockReturn = {
      data: [{
        id: 'abc', templateType: 'RECEIPT', channel: 'EMAIL',
        subjectTemplate: 'Custom {{receiptNumber}}', bodyTemplate: null,
        createdAt: '2026-05-27T10:00:00Z', updatedAt: '2026-05-27T10:00:00Z',
        createdBy: 'alice', updatedBy: 'alice',
      }],
      meta: { total: 1, page: 0, size: 20 },
    };
    (listNotificationTemplates as ReturnType<typeof vi.fn>).mockResolvedValueOnce(mockReturn);

    const { result } = renderHook(() => useNotificationTemplates(), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data?.data).toHaveLength(1);
    expect(result.current.data?.data?.[0].subjectTemplate).toBe('Custom {{receiptNumber}}');
  });
});
```

- [ ] **Step 2: Run + commit**

```bash
pnpm --filter @cia/back-office test
git add cia-frontend/apps/back-office/src/modules/setup/hooks/useNotificationTemplates.test.ts
git commit -m "$(cat <<'EOF'
test(back-office): Task 14.1 — useNotificationTemplates Vitest

Verifies the list query returns the validatedList envelope.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 14.2: `useSendReceiptSms.test.ts`

**Files:**

- Create: `cia-frontend/apps/back-office/src/modules/finance/hooks/useSendReceiptSms.test.ts`

- [ ] **Step 1: Write the test (happy path + 422 error-code mapping)**

```typescript
// cia-frontend/apps/back-office/src/modules/finance/hooks/useSendReceiptSms.test.ts
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import React from 'react';
import { useSendReceiptSms } from './useReceipts';

vi.mock('@cia/api-client', () => ({
  smsReceipt: vi.fn(),
}));
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

// eslint-disable-next-line import/first
import { smsReceipt } from '@cia/api-client';
// eslint-disable-next-line import/first
import { toast } from 'sonner';

function wrapper({ children }: { children: React.ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return React.createElement(QueryClientProvider, { client: queryClient }, children);
}

describe('useSendReceiptSms', () => {
  beforeEach(() => { vi.clearAllMocks(); });

  it('happy path: calls smsReceipt + shows success toast', async () => {
    // allow-mock: Vitest fixture for hook test
    (smsReceipt as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      data: { workflowId: 'send-receipt-sms-xyz' } });

    const { result } = renderHook(() => useSendReceiptSms(), { wrapper });
    result.current.mutate({ dnId: 'dn-1', receiptId: 'rec-1' });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(smsReceipt).toHaveBeenCalledWith('dn-1', 'rec-1');
    expect(toast.success).toHaveBeenCalledWith('SMS queued');
  });

  it('maps RECEIPT_RECIPIENT_PHONE_UNRESOLVED to specific error toast', async () => {
    // allow-mock: Vitest fixture for hook test
    (smsReceipt as ReturnType<typeof vi.fn>).mockRejectedValueOnce({
      response: { data: { errors: [{
        code: 'RECEIPT_RECIPIENT_PHONE_UNRESOLVED', message: 'No phone' }] } },
    });

    const { result } = renderHook(() => useSendReceiptSms(), { wrapper });
    result.current.mutate({ dnId: 'dn-1', receiptId: 'rec-1' });
    await waitFor(() => expect(result.current.isError).toBe(true));

    expect(toast.error).toHaveBeenCalledWith('No phone number on file for this customer');
  });
});
```

- [ ] **Step 2: Run + commit**

```bash
pnpm --filter @cia/back-office test
git add cia-frontend/apps/back-office/src/modules/finance/hooks/useSendReceiptSms.test.ts
git commit -m "$(cat <<'EOF'
test(back-office): Task 14.2 — useSendReceiptSms Vitest (2 tests)

Happy path + RECEIPT_RECIPIENT_PHONE_UNRESOLVED error-code mapping.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 14.3: `NotificationTemplateEditorSheet.test.tsx`

**Files:**

- Create: `cia-frontend/apps/back-office/src/modules/setup/pages/notifications/NotificationTemplateEditorSheet.test.tsx`

- [ ] **Step 1: Write the test (variable picker insert behaviour)**

The test mocks all 5 hooks the editor sheet uses (defaults, variables, preview, save, reset), renders the sheet with `open={true}` for the (RECEIPT, EMAIL) combination, finds the `[Insert]` button for `customerName`, clicks it, asserts the body textarea now contains `{{customerName}}`.

Use the F11 `BulkEmailSheet.test.tsx` mocking pattern as the canonical reference (`vi.mock('../../hooks/...')` for hook mocks).

- [ ] **Step 2: Run + commit**

```bash
pnpm --filter @cia/back-office test
git add cia-frontend/apps/back-office/src/modules/setup/pages/notifications/NotificationTemplateEditorSheet.test.tsx
git commit -m "$(cat <<'EOF'
test(back-office): Task 14.3 — NotificationTemplateEditorSheet Vitest

Variable-picker insert behaviour: clicking [Insert] writes
{{variableName}} into the body textarea.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 15 — Docs + log + final verify + push authorization

### Task 15.1: CLAUDE.md updates

**Files:**

- Modify: `CLAUDE.md`

- [ ] **Step 1: Add a new Development Standards bullet — Notification template framework**

After the existing "Email transmission via Temporal (F7 slice γ)" bullet, insert:

```markdown
- **Notification template framework (cia-documents + cia-setup, F7-δ + R7)** — per-tenant notification templates render through `NotificationComposer` (cia-finance) which composes (a) the override row from `tenant_notification_template` if present, (b) the JAR-default content from `cia-documents/src/main/resources/templates/notifications/{channel}/{type-kebab}.{subject|html|txt}` otherwise. Engine: Mustache (`com.github.spullara.mustache.java:compiler`) — logic-less by design = no SSTI possible. Subject + body fall back independently; `subject_template IS NULL` for an EMAIL row means "use default subject", `body_template IS NULL` means "use default body". `ck_tnt_at_least_one_override` CHECK enforces no all-null rows. SMS rows MUST have NULL subject (`ck_tnt_sms_no_subject`). **Variable allowlist enforcement is two-gated:** save-time validation walks the Mustache AST in `NotificationTemplateService.save` and throws `UNKNOWN_TEMPLATE_VARIABLE` for any `{{var}}` or `{{#section}}` reference outside the per-(type, channel) allowlist (also rejects `{{>partial}}` — partials disabled). Render-time defence-in-depth filters merge fields to the allowlist before render. Adding a new template type = add `NotificationTemplateType` enum value + `NotificationVariables` allowlist entry + JAR default files. **No schema migration.** Renderer HTML-escapes `{{var}}` by default; `{{{var}}}` passes through unescaped. Frontend editor preview renders email HTML inside a sandboxed iframe (`sandbox=""` + `srcDoc`) to defeat self-XSS while the admin is typing.
```

- [ ] **Step 2: Add a new bullet — SMS transmission via Temporal**

```markdown
- **SMS transmission via Temporal (R7)** — mirrors F7-γ email shape. `SmsService` SPI in `cia-notifications.sms` (3 impls supported: `logging` default + future `termii` + `twilio`, gated by `cia.notifications.sms.provider`); `SendReceiptSmsWorkflow` + `SendPaymentVoucherSmsWorkflow` with `@SignalMethod void cancel()` + boolean `cancelled` pre-dispatch check; `SmsActivities.deliver*` methods `@Transactional` for lazy-proxy access; **audit-after-success idempotency** (exactly one SEND audit row per workflow); retry policy `5min → 2× → 1hr, no max attempts`; non-retryable codes `RECEIPT_NOT_FOUND` / `RECEIPT_RECIPIENT_PHONE_UNRESOLVED` (and PAYMENT_* equivalents); `BeneficiaryPhoneResolver` SPI + dispatcher uses `<TYPE>-phone` bean-name convention (mirror of `<TYPE>-email`); `recipientPhone` resolved at projection-build time for list-item DTOs (same N+1 caveat as F7-γ `recipientEmail`); phone PII masked in `audit_log.new_value` as `+234 *** *** 5678`; V61 adds `sms_sent_at` + `sms_sent_to` columns to `receipts` + `payments` (mirrors V57). No PDF gate in preflight — SMS doesn't depend on PDF existing. `NotificationPreflightException` (renamed from `EmailPreflightException`) carries both email + SMS preflight error codes through `GlobalExceptionHandler.handleCiaException` → HTTP 422. `TemporalQueues.NOTIFICATIONS_QUEUE` (renamed from `EMAIL_QUEUE`) hosts all 4 send workflows + the F11 retention workflow.
```

- [ ] **Step 3: Update the Module 1 row in the module-summary table**

Append to the Module 1 right-most column:

```markdown
+ Notification Templates page (F7-δ + R7) — 4-row editor for receipt/payment-voucher email + SMS templates with split-pane subject/body editor + live preview (sandboxed iframe for HTML email; `<pre>` for SMS) + variable picker; reset-to-default via shared `ConfirmDeleteDialog`. Backed by `tenant_notification_template` (V60).
```

- [ ] **Step 4: Update the Module 8 row**

Append:

```markdown
**R7 — SMS dispatch.** Mirror of F7-γ email path. New `SmsService` SPI (cia-notifications.sms) with `LoggingSmsService` impl (Termii / Twilio prod impls in backlog rows `R7-termii-prod` / `R7-twilio-prod`). Two new Temporal workflows on the renamed `NOTIFICATIONS_QUEUE`. `BeneficiaryPhoneResolver` SPI + dispatcher + 4 impls (`<TYPE>-phone` bean naming). New `requestSms(UUID)` / `cancelSms(UUID)` on `ReceiptService` + `PaymentService`. 4 new REST endpoints under FINANCE_UPDATE. V61 adds `sms_sent_at` + `sms_sent_to` columns. Frontend: SMS row-actions on all 4 finance surfaces + `SmsConfirmDialog` (mirror of F7-γ); "Last SMS'd" badges; `formatPhone` util does Nigerian E.164 grouping. Bulk SMS UI deferred to a future slice.
```

- [ ] **Step 5: Add 2 env vars to the Environment Variables table**

```markdown
| `CIA_NOTIFICATIONS_SMS_PROVIDER` | Active `SmsService` impl: `logging` / `termii` / `twilio`. Default `logging` (matchIfMissing=true). | env |
| `CIA_NOTIFICATIONS_SMS_FROM` | SMS sender ID. Max 11 chars for GSM7 alphanumeric. Default `CIA Insurance`. | env |
```

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md
git commit -m "$(cat <<'EOF'
docs(claude): Task 15.1 — F7-δ + R7 development standards + module rows

- 2 new Development Standards bullets (notification template framework
  + SMS transmission via Temporal)
- Module 1 row gains Notification Templates page entry
- Module 8 row gains R7 SMS dispatch entry
- Env vars table gains CIA_NOTIFICATIONS_SMS_PROVIDER + ..._FROM

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 15.2: Regenerate `internal-api.json`

**Files:**

- Modify: `docs-site/static/internal-api.json`

- [ ] **Step 1: Find the existing regen mechanism**

```bash
grep -rn 'internal-api.json' Makefile cia-backend/ docs-site/ 2>/dev/null | head -5
```

Likely there's a Makefile target or a script. Use whichever the project already provides.

- [ ] **Step 2: Regenerate (typical command — verify with Step 1)**

```bash
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run -pl cia-api -Dspring-boot.run.profiles=dev &
SPRINGBOOT_PID=$!
until curl -sf http://localhost:8090/actuator/health > /dev/null; do sleep 2; done
curl -s http://localhost:8090/v3/api-docs > docs-site/static/internal-api.json
kill $SPRINGBOOT_PID
```

- [ ] **Step 3: Verify path + schema counts**

```bash
jq '.paths | length' docs-site/static/internal-api.json
jq '.components.schemas | length' docs-site/static/internal-api.json
```

Expected vs F11 baseline (264 paths + 288 schemas): +11 paths → ~275 total; ~+10 schemas → ~298 total.

- [ ] **Step 4: Commit**

```bash
git add docs-site/static/internal-api.json
git commit -m "$(cat <<'EOF'
docs(api): Task 15.2 — regenerate internal-api.json for F7-δ + R7

+11 paths (7 Setup template endpoints + 4 Finance SMS endpoints)
~+10 schemas (NotificationTemplate Request/Response/Defaults/
Variables/Preview Request/Preview Response + 2 enums + supporting types)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 15.3: Session entry in `cia-log.md` + backlog reconciliation

**Files:**

- Modify: `cia-log.md`

- [ ] **Step 1: Drain `F7-δ` and `R7` backlog rows**

In the canonical backlog table at the top of `cia-log.md`, delete the rows for `F7-δ` and `R7`. Keep `R7-termii-prod` and `R7-twilio-prod` (added Session 133).

- [ ] **Step 2: Add the Session 134 entry**

Insert immediately after the backlog table (before the existing Session 132 entry):

```markdown
## 2026-05-27 — Session 134 (`main`): F7-δ + R7 — per-tenant notification templates (slice complete)

Bundles F7-δ (per-tenant email template override) + R7 (per-tenant SMS template override + SMS-receipt option). Spec at `docs/superpowers/specs/2026-05-27-f7-delta-r7-tenant-notification-templates-design.md` (committed Session 133 brainstorm, b55b08d). Plan at `docs/superpowers/plans/2026-05-27-f7-delta-r7-tenant-notification-templates-implementation.md`. Executed under `superpowers:subagent-driven-development`.

### What landed

[Implementation summary — fill in per-phase highlights when execution completes]

### Final baseline

`mvn -pl cia-api verify -DskipUnitTests=true` — **~399/0/0/1** (up from 358 at slice start; +~41 cia-api ITs). Frontend: `pnpm typecheck` clean; `pnpm test` 5/5 passing; `check-dto-drift.mjs` clean; `check-api-wiring.sh` clean.

### Slice complete

F7-δ + R7 ships ~50 tasks across 15 phases. Drains `F7-δ` + `R7` from the backlog. `R7-termii-prod` and `R7-twilio-prod` remain — pickup when a tenant signs up needing real SMS delivery.

### Known follow-ups

- **Backlog rows drained:** `F7-δ`, `R7` (both P3).
- **Backlog rows unchanged:** `R7-termii-prod`, `R7-twilio-prod` (Session 133).
- **All other prior rows unchanged.**

---
```

- [ ] **Step 3: Commit**

```bash
git add cia-log.md
git commit -m "$(cat <<'EOF'
docs(log): Task 15.3 — Session 134 entry + drain F7-δ + R7 backlog rows

Slice-complete entry for F7-δ + R7 (per-tenant notification templates).
Drains the two original backlog rows. R7-termii-prod / R7-twilio-prod
remain — pending prod-impl pickup.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 15.4: Final full-stack verify + push authorization

- [ ] **Step 1: Final backend verify**

```bash
mvn -pl cia-api verify -DskipUnitTests=true
```
Expected: ~399 tests, 0 failures, 0 errors, 1 intentional benchmark skip.

- [ ] **Step 2: Final frontend verify**

```bash
pnpm --filter @cia/back-office typecheck
pnpm --filter @cia/back-office test
bash cia-frontend/scripts/check-api-wiring.sh
node cia-frontend/scripts/check-dto-drift.mjs
```
Expected: clean; Vitest 5/5 passing.

- [ ] **Step 3: Request push authorization from the user**

Per CLAUDE.md "NEVER push without explicit ask" — do not auto-push. Summarize the slice (commit count, baseline, drained backlog rows) and wait for `Yes, push all N commits` authorization.

If authorized:

```bash
git log --oneline origin/main..HEAD | wc -l   # expected: ~50
git push origin main
```

- [ ] **Step 4: Final TodoWrite cleanup**

Mark every phase complete. Report shipped status.

---

## Plan summary

- **50 tasks** across 15 phases.
- **2 Flyway migrations**: V60 (`tenant_notification_template`), V61 (sms_sent_at / sms_sent_to on receipts + payments).
- **~41 new cia-api ITs**. Baseline 358 → ~399.
- **3 new Vitest tests** (back-office app). Count 2 → 5.
- **11 new REST endpoints** (7 Setup CRUD + 4 Finance SMS).
- **~10 new schemas** in `internal-api.json`.
- **Drains backlog**: `F7-δ`, `R7`. **Adds backlog (already filed Session 133)**: `R7-termii-prod`, `R7-twilio-prod`.

## Open implementation notes (for subagent context-clearing per task)

- The `seedReceiptWithCustomerPhone` JDBC helper in Phases 8 / 9 / 10 ITs MUST mirror the existing F7-γ `SendReceiptEmailWorkflowIT` seeder shape — read that file first before writing the SMS variant.
- The Mustache AST walking in `MustacheTemplateRenderer.extractVariableNames` relies on `ValueCode.getName()` and `IterableCode.getName()` being public on mustache-java 0.9.x. If they're protected on the pinned version, either upgrade to 0.9.14+ or use reflection.
- Phase 7 phone resolver impls reference `customer.getPhone()`, `broker.getPhone()`, etc. If any of those getter names differ in the entities, use the F7-γ email-resolver sibling as the canonical reference for the existing entity API.
- Task 8.4 worker registration assumes the existing `NotificationsWorkerConfig` (renamed in Task 0.3) uses `worker.registerWorkflowImplementationTypes(...)` + `worker.registerActivitiesImplementations(...)`. If the existing code uses a different registration pattern, mirror that pattern.
- The editor preview iframe uses `sandbox=""` (empty string = all restrictions). This is more restrictive than the default — but exactly what we want when the iframe's body is tenant-supplied content that may include a malicious `<script>` tag.




