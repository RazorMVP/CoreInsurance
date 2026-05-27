# F7-δ + R7 — Per-tenant notification template overrides (design spec)

**Status:** Approved by user 2026-05-27 (brainstorm sessions Q1–Q5 + design sections 1–7).
**Drains backlog rows:** `F7-δ`, `R7` (both P3).
**Adds backlog rows (already filed during brainstorm):** `R7-termii-prod`, `R7-twilio-prod` (both P3, post-slice prod-impl pickup).

---

## 1. Goal

Let each tenant override the subject and body of the 4 transactional notification templates (receipt-email, payment-voucher-email, receipt-sms, payment-voucher-sms). Templates render through a logic-less engine (Mustache) against a known per-template variable allowlist. Editor saves take immediate effect with audit logging. SMS infrastructure ships end-to-end against a logging-only provider stub so the workflow + UI + cancel-signal mechanics are exercised in tests before a real Termii/Twilio impl lands.

## 2. Scope

### IN scope

1. New `tenant_notification_template` multi-row table (V60) with `UNIQUE(template_type, channel)` partial index on `WHERE deleted_at IS NULL`. Permissive override model: `subject_template` and `body_template` are independently nullable, each falls back to the JAR default when null.
2. Migrate the 2 existing JAR-default email templates from Thymeleaf `${var}` → Mustache `{{var}}`. Add 2 new JAR-default SMS templates (plain text, single GSM7 segment at typical values).
3. New `MustacheTemplateRenderer` (in `cia-documents`) — channel-and-domain-agnostic. Compiles + executes Mustache against a filtered merge-field map.
4. New `NotificationComposer` (in `cia-finance`) — replaces `EmailBodyComposer`. Looks up override → falls back to JAR default per field → calls renderer.
5. New `SmsService` SPI (in `cia-notifications`) + `LoggingSmsService` only. `@ConditionalOnProperty(name="cia.notifications.sms.provider", havingValue="logging", matchIfMissing=true)`. The existing placeholder-class `SmsNotificationService` is deleted.
6. Two new Temporal workflows — `SendReceiptSmsWorkflow` + `SendPaymentVoucherSmsWorkflow` — mirror F7-γ email workflows including `@SignalMethod void cancel()` + `boolean cancelled` pre-dispatch check.
7. New `BeneficiaryPhoneResolver` SPI + `BeneficiaryPhoneResolverDispatcher` (mirror `BeneficiaryEmailResolver` pattern; `<TYPE>-phone` bean-name convention).
8. New service methods on `ReceiptService` + `PaymentService`: `requestSms(UUID)` / `cancelSms(UUID)`. Mirror `requestEmail` shape exactly — preflight → 422 `{errorCode, message}` envelope or 202 `{workflowId}`.
9. V61 migration: `receipts.sms_sent_at` + `receipts.sms_sent_to` + same on `payments`. Mirrors V57's email columns.
10. New REST surface for the Setup CRUD + previews (7 endpoints) + 4 Finance SMS endpoints. See §5.
11. New frontend Setup page: **Notification Templates**. One row per `(type, channel)`; click to edit → split-pane editor (subject input + body textarea + live-preview pane) with variable-picker sidebar.
12. Single-row "Send SMS" + "Cancel SMS" buttons on the 4 finance surfaces (`ReceiptsListSection`, `PaymentsListSection`, `DebitNoteDetailDialog`, `CreditNoteDetailDialog`). Mirror F7-γ row actions for email. `recipientPhone` added to projection DTOs.
13. New shared `SmsConfirmDialog` (mirror of F7-γ `EmailConfirmDialog`).
14. Rename `TemporalQueues.EMAIL_QUEUE` → `TemporalQueues.NOTIFICATIONS_QUEUE` (value `"notifications-queue"`). Workflow IDs stay unique per workflow type → the rename is mechanically safe.
15. Rename `EmailPreflightException` → `NotificationPreflightException`. Refactor `EmailTemplateType` → `NotificationTemplateType` (channel-neutral) + new `NotificationChannel` enum.

### OUT of scope (explicitly)

- **Termii / Twilio prod SMS impls** — backlog rows `R7-termii-prod` / `R7-twilio-prod`.
- **Approval workflow for template edits** — Q4=A (immediate save with audit + preview).
- **Shared header/footer chrome + tenant asset hosting** — Q5=A (body+subject only; tenants inline external image URLs).
- **Short-link to PDF in SMS body** — would need a signed-URL subsystem. Deferred.
- **Per-tenant default channel preference** (the email-vs-SMS cascade hinted in the R7 backlog row) — out; each channel is opt-in per row.
- **Bulk SMS UI** — defer the bulk-runner-with-cancel pattern until a tenant actually uses SMS in anger. Single-row only for v1. The F11 `BulkEmailSheet` is unmodified.
- **Other transactional templates** (renewal reminders, OTPs, policy-bound notifications) — the framework supports them via new enum values + JAR defaults, but no other use-site lands in this slice.
- **Optimistic-locking on template edits** (`@Version`) — last-write-wins via `updated_at` is fine for v1. Two-System-Admins-editing-the-same-template-simultaneously is not a real concern.

## 3. Architecture

### Module placement

| Code | Module | Why |
|---|---|---|
| `TenantNotificationTemplate` entity + repository + `NotificationTemplateService` + `NotificationTemplateController` | **cia-setup** | Tenant master data; managed from Setup → Notification Templates. |
| `MustacheTemplateRenderer` + 4 JAR default templates | **cia-documents** | Channel-agnostic rendering layer + asset storage. |
| `SmsMessage` value type + `SmsService` SPI + `LoggingSmsService` impl | **cia-notifications** | Mirrors `EmailService` SPI. |
| `NotificationComposer`, `BeneficiaryPhoneResolver` SPI + dispatcher, 2 SMS Temporal workflows + activities, `requestSms` / `cancelSms` on `ReceiptService` + `PaymentService`, 4 Finance SMS REST endpoints | **cia-finance** | Domain orchestration: knows merge-field shape per receipt/payment, owns workflow lifecycle, exposes user-facing endpoints. |
| `NotificationTemplateType` (`RECEIPT`, `PAYMENT_VOUCHER`) + `NotificationChannel` (`EMAIL`, `SMS`) enums | **cia-common** | Replaces the channel-coupled `EmailTemplateType`. |

### New cross-module dependency

`cia-finance` → `cia-setup` — for `TenantNotificationTemplateRepository`. Consistent with the existing `cia-finance` → `cia-customer` / `cia-claims` / `cia-endorsement` / `cia-policy` chain added in F7-β.

### Temporal queue

Both new SMS workflows register on `NOTIFICATIONS_QUEUE` (renamed from `EMAIL_QUEUE`). The queue already hosts the F11 `PdfDownloadLogRetentionWorkflow`, so its old name was already imprecise. Workflow IDs stay unique per workflow type.

## 4. Data model + migrations

### V60 — `tenant_notification_template`

```sql
CREATE TABLE tenant_notification_template (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    template_type    VARCHAR(40)  NOT NULL,  -- RECEIPT | PAYMENT_VOUCHER (extensible)
    channel          VARCHAR(20)  NOT NULL,  -- EMAIL | SMS
    subject_template TEXT,                   -- Mustache; NULL = use default; always NULL for SMS
    body_template    TEXT,                   -- Mustache; NULL = use default
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

CREATE UNIQUE INDEX uq_tenant_notification_template_type_channel
    ON tenant_notification_template (template_type, channel)
    WHERE deleted_at IS NULL;
```

V60 also seeds `notification_templates:view` + `notification_templates:update` authorities into the System Admin access group via `INSERT INTO access_group_permission(...) VALUES (...)`.

### V61 — SMS bookkeeping columns

```sql
ALTER TABLE receipts ADD COLUMN sms_sent_at TIMESTAMPTZ;
ALTER TABLE receipts ADD COLUMN sms_sent_to VARCHAR(50);
ALTER TABLE payments ADD COLUMN sms_sent_at TIMESTAMPTZ;
ALTER TABLE payments ADD COLUMN sms_sent_to VARCHAR(50);
```

Populated by the SMS workflow activities on successful delivery. Mirrors V57's `email_sent_at` / `email_sent_to`.

### Enum refactor (no DB migration — code only)

```java
// Before (cia-common.email)
public enum EmailTemplateType { RECEIPT_EMAIL, PAYMENT_VOUCHER_EMAIL }

// After (cia-common.notification)
public enum NotificationTemplateType { RECEIPT, PAYMENT_VOUCHER }
public enum NotificationChannel       { EMAIL, SMS }
```

Touches ~6 call sites: `EmailBodyComposer`, `SendReceiptEmailWorkflowImpl`, `SendPaymentVoucherEmailWorkflowImpl`, 2 ITs that reference the old enum, and the `EmailTemplateType` import sites. Mechanical rename.

### JAR default template file layout

```
cia-documents/src/main/resources/templates/notifications/
├── email/
│   ├── receipt.subject              # one-line Mustache; default: "Receipt {{receiptNumber}} — payment received"
│   ├── receipt.html                 # Mustache HTML body; migrated from receipt-default.html ${var} → {{var}}
│   ├── payment-voucher.subject      # "Payment voucher {{paymentNumber}}"
│   └── payment-voucher.html         # migrated from payment-voucher-default.html
└── sms/
    ├── receipt.txt                  # "Hi {{customerName}}, we received your payment of {{amount}}. Receipt: {{receiptNumber}}."
    └── payment-voucher.txt          # "Hi {{beneficiaryName}}, payment of {{amount}} processed. Voucher: {{paymentNumber}}."
```

Filename convention: `{type-kebab-case}.{subject|html|txt}`. Channel inferred from directory. The `-default` suffix on existing files is dropped — the JAR file IS the default by definition.

### Variable allowlist (in `cia-common`)

```java
public final class NotificationVariables {
    public record Key(NotificationTemplateType type, NotificationChannel channel) {}
    private static final Map<Key, Set<String>> ALLOWLIST = Map.of(
        new Key(RECEIPT,         EMAIL), Set.of("customerName","amount","paymentDate","receiptNumber","debitNoteNumber","companyName"),
        new Key(PAYMENT_VOUCHER, EMAIL), Set.of("beneficiaryName","amount","paymentDate","paymentNumber","creditNoteNumber","companyName"),
        new Key(RECEIPT,         SMS),   Set.of("customerName","amount","receiptNumber"),
        new Key(PAYMENT_VOUCHER, SMS),   Set.of("beneficiaryName","amount","paymentNumber")
    );
    public static Set<String> allowlistFor(NotificationTemplateType t, NotificationChannel c) {
        return Optional.ofNullable(ALLOWLIST.get(new Key(t, c)))
            .orElseThrow(() -> new IllegalStateException("No allowlist for " + t + "/" + c));
    }
}
```

## 5. REST surface

### Setup (System Admin — `notification_templates:view` / `notification_templates:update`)

```
GET    /api/v1/setup/notification-templates                  → list overrides (0–4 rows)
GET    /api/v1/setup/notification-templates/defaults         → JAR-default content for all 4
GET    /api/v1/setup/notification-templates/variables        → allowlist per (type, channel)
POST   /api/v1/setup/notification-templates                  → create override
PUT    /api/v1/setup/notification-templates/{id}             → update
DELETE /api/v1/setup/notification-templates/{id}             → reset to default (delete row)
POST   /api/v1/setup/notification-templates/preview          → render with sample values
```

### Finance (transactional — `FINANCE_UPDATE`)

```
POST   /api/v1/debit-notes/{dnId}/receipts/{id}/sms          → request SMS  (202 + workflowId)
POST   /api/v1/debit-notes/{dnId}/receipts/{id}/sms/cancel   → cancel SMS   (202 + cancelled flag)
POST   /api/v1/credit-notes/{cnId}/payments/{id}/sms         → request SMS
POST   /api/v1/credit-notes/{cnId}/payments/{id}/sms/cancel  → cancel SMS
```

## 6. Renderer + workflow flow

### `NotificationComposer.compose(type, channel, mergeFields)`

```
override   = repo.findByTypeAndChannel(type, channel)
subjectTpl = override?.subjectTemplate ?? defaults.subjectFor(type, channel)   // null for SMS
bodyTpl    = override?.bodyTemplate    ?? defaults.bodyFor(type, channel)
allowlist  = NotificationVariables.allowlistFor(type, channel)
filtered   = mergeFields ∩ allowlist                    // defence-in-depth
return ComposedMessage(
    subject = subjectTpl == null ? null : renderer.render(subjectTpl, filtered),
    body    = renderer.render(bodyTpl, filtered)
)
```

### Variable allowlist enforcement — two gates

1. **Save-time** (primary): in `NotificationTemplateService.save`, compile the user's template via `MustacheFactory.compile(...)`, walk the parsed AST extracting all `ValueCode` + `IterableCode` names, compare against `NotificationVariables.allowlistFor(type, channel)`. Unknown variable or partial-include (`{{>...}}`) → `BusinessRuleException("UNKNOWN_TEMPLATE_VARIABLE", "varName: foo")` → HTTP 400.
2. **Render-time** (safety net): `MustacheTemplateRenderer` configured to throw on missing fields rather than silently emit blanks. Should never fire if save-time worked, but protects against drift if the allowlist tightens after a save.

### SMS workflow lifecycle (mirror of F7-γ email per receipt)

```
POST /api/v1/debit-notes/{dnId}/receipts/{id}/sms     (FINANCE_UPDATE)
  → ReceiptService.requestSms(receiptId):
       ├── load Receipt or 404
       ├── resolve phone via direct customers.phone JDBC (mirror of email)
       ├── throw NotificationPreflightException("RECEIPT_RECIPIENT_PHONE_UNRESOLVED")
       │      if phone is null/blank → 422
       │     [no PDF gate — SMS doesn't depend on PDF existing]
       └── workflowClient.start(SendReceiptSmsWorkflow::send, tenantId, receiptId, requestedBy)
             workflowId = "send-receipt-sms-<receiptId>"
  ← 202 { workflowId }

[Temporal worker on NOTIFICATIONS_QUEUE dequeues]
  → SendReceiptSmsWorkflowImpl.send(tenantId, receiptId, requestedBy):
       ├── if (cancelled) return;                         ← pre-dispatch cancel check
       └── activities.deliver(tenantId, receiptId, requestedBy):    @Transactional
            ├── load Receipt (debitNote eagerly via @Transactional lazy-proxy resolution)
            ├── re-resolve phone via JDBC (in case it changed mid-queue)
            ├── compose merge fields {customerName, amount, receiptNumber}
            ├── NotificationComposer.compose(RECEIPT, SMS, fields)
            │   → ComposedMessage(subject=null, body=<rendered>)
            ├── smsService.sendSms(new SmsMessage(toPhone, body))
            ├── update receipt.smsSentAt + smsSentTo (V61)
            └── AuditService.log(...) AuditAction.SEND { channel: SMS, recipient: <maskedPhone> }
       
       │ retry policy: 5min → 2× → 1hr, no max attempts (same as email)
       │ non-retryable codes: RECEIPT_NOT_FOUND, RECEIPT_RECIPIENT_PHONE_UNRESOLVED
```

Cancel path:

```
POST .../{id}/sms/cancel                                (FINANCE_UPDATE)
  → ReceiptService.cancelSms(receiptId):
       ├── workflowClient.stub("send-receipt-sms-<id>").cancel()    ← signal
       └── AuditService.log(...) AuditAction.CANCEL { workflowId, cancelledBy, channel: SMS }
  ← 202 { cancelled: true }
```

`PaymentService.requestSms` / `cancelSms` are the structural mirror — they use `BeneficiaryPhoneResolverDispatcher` instead of direct customer JDBC.

### Error codes

| Code | HTTP | Trigger |
|---|---|---|
| `RECEIPT_RECIPIENT_PHONE_UNRESOLVED` | 422 | Customer has no phone on file |
| `PAYMENT_RECIPIENT_PHONE_UNRESOLVED` | 422 | Beneficiary phone dispatcher returned empty |
| `UNKNOWN_TEMPLATE_VARIABLE` | 400 | Save-time template validation failed |
| `TEMPLATE_TYPE_CHANNEL_CONFLICT` | 409 | POST when a `(type, channel)` row already exists undeleted |
| `RECEIPT_NOT_FOUND` / `PAYMENT_NOT_FOUND` / `WORKFLOW_NOT_FOUND` | 422 | Reused from F7-γ |

All routed via `GlobalExceptionHandler.handleCiaException` (already routes `EmailPreflightException` / now `NotificationPreflightException`).

### Audit trail

One row per workflow completion (audit-after-success idempotency from F7-γ). Cancellation writes one CANCEL row. The `channel` discriminator lives in the `new_value` JSONB. `sms_sent_to` is masked in the audit row (`+234 *** *** 5678`) to reduce PII surface area.

## 7. Frontend editor UX

### Setup → Notification Templates list page

A 4-row table (one per `(type, channel)` pair) showing whether the override is `Default` or `Overridden`. Click a row to open the editor sheet.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Notification Templates                                                  │
│  ┌──────────────────┬─────────┬───────────────┬────────────────────┐    │
│  │ Template         │ Channel │ State         │ Last edited        │    │
│  ├──────────────────┼─────────┼───────────────┼────────────────────┤    │
│  │ Receipt          │ Email   │ ● Overridden  │ 2026-05-27 09:14   │    │
│  │ Receipt          │ SMS     │ ○ Default     │ —                  │    │
│  │ Payment Voucher  │ Email   │ ○ Default     │ —                  │    │
│  │ Payment Voucher  │ SMS     │ ○ Default     │ —                  │    │
│  └──────────────────┴─────────┴───────────────┴────────────────────┘    │
└──────────────────────────────────────────────────────────────────────────┘
```

### Editor sheet (`NotificationTemplateEditorSheet.tsx`)

Wide right-edge `<Sheet>` (~60% viewport width) with a split layout:

```
┌────────────────────────────────────────────────────────────────────────────────┐
│  Receipt — Email                                            [○ Showing default]│
│  ┌────────────────────────────────────┬────────────────────────────────────┐   │
│  │  Subject                           │  Preview                           │   │
│  │  ┌──────────────────────────────┐  │  ┌──────────────────────────────┐  │   │
│  │  │ Receipt {{receiptNumber}} —  │  │  │ Receipt REC-2026-00042 —     │  │   │
│  │  │ payment received             │  │  │ payment received             │  │   │
│  │  └──────────────────────────────┘  │  │                              │  │   │
│  │  Body                              │  │ ────────────────────────────  │  │   │
│  │  ┌──────────────────────────────┐  │  │  Hi Acme Logistics Ltd,      │  │   │
│  │  │ Hi {{customerName}},         │  │  │  We received your payment    │  │   │
│  │  │ ... (monospaced, larger)     │  │  │  of ₦450,000 on 2026-05-27.  │  │   │
│  │  └──────────────────────────────┘  │  │  ...                         │  │   │
│  │  Available variables               │  └──────────────────────────────┘  │   │
│  │  ┌──────────────────────────────┐  │                                    │   │
│  │  │ • customerName     [insert]  │  │                                    │   │
│  │  │ • amount           [insert]  │  │                                    │   │
│  │  │ • paymentDate      [insert]  │  │                                    │   │
│  │  │ ...                          │  │                                    │   │
│  │  └──────────────────────────────┘  │                                    │   │
│  └────────────────────────────────────┴────────────────────────────────────┘   │
│  [Reset to default]                              [Cancel]  [Save & activate]   │
└────────────────────────────────────────────────────────────────────────────────┘
```

UX details:

- **Plain textareas, not Monaco.** Mustache syntax doesn't need a code editor; a monospaced textarea is enough.
- **Live preview** re-renders on every keystroke (debounced 200ms) via `POST /notification-templates/preview` — single source of truth for the rendered output.
- **Variable picker** shows the allowlist for this `(type, channel)`. Clicking `[insert]` inserts `{{variableName}}` at cursor position. Help text mentions `{{{variableName}}}` for unescaped output.
- **State indicator** in top-right: `● Overridden` (filled, primary) vs `○ Showing default` (outline, muted). Toggles as soon as the user types.
- **Reset to default** = destructive (red, secondary placement) → opens `ConfirmDeleteDialog` (shared `@cia/ui` component from Session 79's reasoned-delete pattern). Confirm → `DELETE` → row vanishes from the override table, JAR default reactivates.
- **Sample preview values** are a constant frontend map (never hits DB). Lets preview render even when no real receipts exist.
- **SMS editor variant**: subject input is hidden (channel = SMS); body textarea has a "characters: 142 / 160 (1 segment)" counter below it; computes against the *rendered* preview, not the template source. No HTML allowed (frontend strips on save with a warning toast).

### SMS row-action buttons on the 4 finance surfaces

Mirror of F7-γ email buttons.

| Surface | Where | Gate |
|---|---|---|
| `ReceiptsListSection` (flat) | New "Send SMS" row-action between Email and Download | `recipientPhone !== null` |
| `PaymentsListSection` (flat) | Same | Same |
| `DebitNoteDetailDialog` (nested) | New outline "SMS" button alongside Email + Download | Same |
| `CreditNoteDetailDialog` (nested) | Same | Same |

`recipientPhone` added to `ReceiptListItemResponse` + `PaymentListItemResponse`. Resolved at projection-build time via JDBC (receipts) or `BeneficiaryPhoneResolverDispatcher` (payments). Same N+1 caveat as F7-γ `recipientEmail` — acceptable.

### `SmsConfirmDialog` (new shared component)

Mirror of `EmailConfirmDialog`. Props: `open / onOpenChange / recipientPhone / documentLabel / isPending / onConfirm`. Phone displayed using `formatPhone(raw)` util (Nigerian E.164 → `+234 901 234 5678`; fall through to raw display otherwise). Send button disabled while `isPending` or when `recipientPhone === null`.

### "Last sent" badges

Each row with `smsSentAt` shows under the status:

> Last SMS'd 2026-05-27 09:14:32 to +234 901 234 5678

Matches the existing "Last emailed" badge styling (small, muted text).

## 8. Testing strategy

### Unit tests (small, fast, no Testcontainers)

| Test class | Module | Coverage |
|---|---|---|
| `MustacheTemplateRendererTest` | cia-documents | Variable substitution, conditional sections, throw-on-missing, allowlist filtering. |
| `NotificationVariablesTest` | cia-common | `allowlistFor(type, channel)` returns expected sets; throws on unknown enum pairs. |
| `LoggingSmsServiceTest` | cia-notifications | `sendSms(SmsMessage)` logs the message + returns success. |
| `BeneficiaryPhoneResolver*Test` | cia-finance | One per dispatcher target (`CLAIM-phone`, `COMMISSION-phone`, `REINSURANCE-phone`, `ENDORSEMENT-phone`). |

### Integration tests (`cia-api` failsafe, Testcontainers PostgreSQL + Keycloak)

| IT class | Tests | Coverage |
|---|---|---|
| `NotificationTemplateControllerIT` | ~11 | Full CRUD: list empty/populated, defaults, variables, POST happy, `UNKNOWN_TEMPLATE_VARIABLE` 400, `TEMPLATE_TYPE_CHANNEL_CONFLICT` 409, PUT happy, `ck_tnt_at_least_one_override` 400, DELETE-as-reset, preview happy, 403 without authority. |
| `NotificationComposerIT` | ~7 | No override → JAR; full override → DB; subject-only → mixed; body-only → mixed; SMS → subject null; Mustache conditionals; allowlist filter drops extra fields. |
| `SendReceiptSmsWorkflowIT` | ~5 | TestWorkflowEnvironment + simulated clock. Happy / cancel-before-dispatch / non-retryable / retryable-then-success (audit idempotency) / preflight failure. |
| `SendPaymentVoucherSmsWorkflowIT` | ~6 | Mirror + CLAIM / COMMISSION / REINSURANCE / ENDORSEMENT happy paths via dispatcher. |
| `ReceiptControllerSmsIT` | ~4 | POST `/sms` happy, `RECEIPT_RECIPIENT_PHONE_UNRESOLVED` 422, `RECEIPT_NOT_FOUND` 422, 403 without `FINANCE_UPDATE`. |
| `PaymentControllerSmsIT` | ~4 | Mirror with COMMISSION-typed CN. |
| `CancelSmsWorkflowIT` | ~2 | `signalWithStart` deterministic — cancel before send / `WORKFLOW_NOT_FOUND`. |
| `CancelSmsControllerIT` | ~2 | 202 + CANCEL audit / 403 without `FINANCE_UPDATE`. |

Estimated new cia-api ITs: **~41**. Baseline goes 358 → ~399.

### Frontend Vitest tests (build on F11's infrastructure)

| Test | Coverage |
|---|---|
| `useNotificationTemplates.test.ts` | List query returns envelope; `useSaveTemplate` invalidates list on success. |
| `useSendReceiptSms.test.ts` | Mutation maps to fetcher with correct args; 422 errorCode surfaces code-specific toast. |
| `NotificationTemplateEditorSheet.test.tsx` | Variable picker `[insert]` writes `{{customerName}}` at cursor; debounced preview fires; state badge flips; reset confirmation calls DELETE. |

Estimated new frontend tests: **3**. Vitest count 2 → 5.

### CI guards (no new guards needed)

- `check-api-wiring.sh` — new hooks comply by construction.
- `check-dto-drift.mjs` — establishes baseline at zero for `NotificationTemplateDto` ↔ `NotificationTemplateResponse`. No new `dto-drift.config.json` entries.

## 9. Decisions log

| Decision | Choice | Reason |
|---|---|---|
| Q1: SMS provider scope | **A** — logging stub only | Termii/Twilio prod-impls deferred to backlog rows; ship SPI + workflow + UI end-to-end against logger. |
| Q2: Storage shape | **B** — multi-row `tenant_notification_template` | Extensible; adding new template types = enum value + JAR default + no migration. |
| Q3: Template engine | **B** — Mustache for defaults and overrides | Logic-less by design = no SSTI possible; ~20-line migration of 2 JAR templates. |
| Q4: Edit gating | **A** — immediate save with audit + preview | No real risk reduction from approval workflow on copy edits; reset-to-default is the instant escape hatch. |
| Q5: Branding scope | **A** — body + subject only | Asset hosting is a real product feature deserving its own slice; external CDN URLs cover the 80% case. |
| Mustache library | `com.github.spullara.mustache.java:compiler:0.9.x` | Stable since 2014, ~80KB, no transitive deps. Partials disabled at parse time. |
| HTML escaping | Default Mustache (`{{var}}` escapes, `{{{var}}}` raw) | XSS posture; document `{{{var}}}` in variable-picker help. |
| Sample preview values | Frontend constant map (no DB hit) | Lets preview render with no real data; simple, fast. |
| Property naming | `cia.notifications.sms.provider` (logging/termii/twilio) | Mirrors `cia.notifications.email.provider`. |
| Phone display | Light Nigerian E.164 formatting, raw fallthrough | No phone-format library; 10-line util. |
| Phone audit masking | `+234 *** *** 5678` | Reduces PII surface in audit_log without breaking ops debugging. |
| Workflow ID convention | `send-receipt-sms-<id>` / `send-payment-voucher-sms-<id>` | Mirrors F7-γ. |
| Phone resolver bean naming | `<TYPE>-phone` (e.g. `CLAIM-phone`) | Mirrors F7-γ's `<TYPE>-email`. |
| Reset-to-default + in-flight workflows | Already-started workflows finish with the template cached at activity-entry time; new defaults effective on next send | ~1-hour drift on retries; acceptable. |
| SMS body length cap | 1000 chars at template source | Tenants get room for `{{var}}` markers expanding longer; caps absurd templates. |
| Frontend Sidebar | New "Notification Templates" nav under Setup | Existing Setup group home for tenant master data. |

## 10. CLAUDE.md updates required

- **New Development Standards section:** "Notification template framework (cia-documents + cia-setup)" — covers Mustache engine choice, allowlist enforcement, `NotificationComposer` fallback chain, partials-disabled posture.
- **Module 1 row** gains "Notification Templates page" + sub-bullet for the editor.
- **Module 8 row** gains "Send SMS / Cancel SMS row actions on the 4 surfaces, mirror of F7-γ; `recipientPhone` projection field; V61 `sms_sent_at` / `sms_sent_to` columns".
- **Environment Variables table** gains `CIA_NOTIFICATIONS_SMS_PROVIDER` (`logging` / `termii` / `twilio`; default `logging`) + `CIA_NOTIFICATIONS_SMS_FROM` (sender ID).
- **Module dependencies diagram**: `cia-finance` → `cia-setup` edge gains a note (already there for masters).

## 11. Backlog drainage + additions

- **Drains:** `F7-δ`, `R7` (both P3, drained at slice end).
- **Adds (already filed Session 133):** `R7-termii-prod`, `R7-twilio-prod` (P3).
- **No other backlog changes expected**; any side-discoveries during execution follow the slice discipline rule and become new backlog rows, not absorbed.
