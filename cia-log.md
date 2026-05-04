# CIA Project Change Log

All changes, decisions, and configurations made during the development of the Core Insurance Application (General Business).

---

## 2026-05-04 — Session 53: Build audit + Sequence B (G7, G6, G5 wired)

### Context

After session 52 closed the session-51 review punch list, audit shifted to "what's left to build" rather than "what's broken." User asked for a deep audit, then chose **Sequence B** — small frontend wiring fixes first (G7→G6→G5→G8), then larger backend gaps (G3→G4→G1), then Phase 3 Partner Portal.

### Commits in this session

```
31138ba  docs(arch): correct module count to 19 in container diagram
fc6895c  chore(gitignore): ignore personal skills + tool working dirs
5639820  fix(setup): wire QuotesConfigTab to backend (G7)
9e6b1e1  docs(log): session 53 — build audit + start Sequence B (G7 wired)
de68d50  fix(finance): wire receipt + payment reversal to backend (G6)
753f2c7  docs(log): session 53 — extend with G6 finance reverse wiring
76983b9  fix(audit): wire alert acknowledge + client-side CSV export (G5)
```

### Deep audit findings

**Frontend (back-office, 10 modules):** CI guard clean (0 violations). 70 useQuery + 38 useMutation across modules — read wiring is real, not absent (the audit subagent's grep mismatched `useQuery<Type>(` and reported 0; manual verification corrected this). 20 allow-mock fallbacks (18 legitimate "in flight" patterns; 2 finance "decorative enrichment" worth a backend-existence check). 17 module-level TODOs naming concrete missing endpoints — these became gaps G3–G7.

**Backend (11 business modules):** No stub markers anywhere. The single `UnsupportedOperationException` in `ProductService.java:124` is a defensive guard pointing to `PolicyNumberFormatService.generateNext()` — intentional. Real gap: **cia-policy at 12 endpoints vs 23 features** — missing risk details (bulk + modify), document send/ack/download, survey (assign/upload/approve/override), coinsurance shares, NIID upload, renewal automation. cia-endorsement (8 vs 10) and cia-reports (14 vs 20) are counting mismatches, not gaps. cia-reports V18 seed contains 55 SYSTEM reports as documented.

**Doc drift:** CLAUDE.md container diagram listed "16 Maven modules" but 19 exist (cia-partner-api, cia-audit, cia-reports added since the diagram was written). Fixed in `31138ba`.

### Gap inventory (decision-ready)

| ID | Description | Impact | Effort |
| --- | --- | --- | --- |
| G1 | cia-policy backend — 11 missing endpoints | 🔴 high | L |
| G3 | Reinsurance — 7 missing endpoints (treaty status, FAC, allocations) | 🔴 high | M |
| G4 | Claims — 6 missing endpoints (inspection, cancel, doc bundle) | 🔴 high | M |
| G5 | Audit — 2 endpoints (alert acknowledge, report export) | 🟡 med | S |
| G6 | Finance — 1 endpoint (receipt/payment reverse) | 🟡 med | S |
| G7 | Setup quote-config save | 🟢 low | S |
| G8 | Finance "decorative enrichment" allow-mocks (verify backend has) | 🟢 low | S |
| G9 | Phase 3 Partner Portal (5 builds) | 🔴 high to partners | L |

### Workstream — Sequence B starts with G7

**Surprise on first task:** `PUT /api/v1/setup/quote-config` was already wired in `QuoteConfigController.java:32`. The TODO at `QuotesConfigTab.tsx:162` was the visible symptom; the page actually had three full CRUD flows (config + discount types + loading types) with **zero persistence** — local-state-only edits backed by `MOCK_DISCOUNT_TYPES`/`MOCK_LOADING_TYPES`/`MOCK_QUOTE_CONFIG`. Backend has 9 controller mappings supporting all of it.

Wired the whole tab in one commit:
- 3 useQuery (config singleton + discount types list + loading types list)
- 7 useMutation (config update + create/update/remove for both type lists)
- Skeleton fallback while initial queries are in flight
- Save button uses `updateConfigMutation.isPending` (matches H2 pattern)

`MOCK_*` exports in `quote-config-types.ts` kept — still imported by `QuoteDetailPage.tsx` for separate concerns. That wiring is a follow-up.

### Workstream — G6 finance reversal

Same backend-already-built pattern as G7. `PaymentController.reverse` and `ReceiptController.reverse` both existed at `/{id}/reverse` under their nested resource paths (`/api/v1/debit-notes/{debitNoteId}/receipts` and `/api/v1/credit-notes/{creditNoteId}/payments`). The frontend dialog had a single `// TODO: POST` and no UUIDs to call it with — `ReverseTarget` carried only display strings (`reference`, `linkedRef`).

Wired:

- Extended `ReverseTarget` with `id` (receipt|payment UUID) and `parentId` (debit-note|credit-note UUID for the nested URL).
- Both `ReceivablesTab` and `PayablesTab` populate the new fields from the row DTO (`row.original.id` + `row.original.debitNoteId`/`creditNoteId`).
- Dialog gains a required `reason` Textarea — backend `ReverseRequest` is `@NotBlank`. Inline validation: empty reason on Confirm shows error, doesn't fire mutation.
- `useMutation` POSTs to the correct nested URL based on `target.type`. On success, invalidates both the list query (`receipts`/`payments`) and the parent query (`debit-notes`/`credit-notes`) so the parent's status flips back to Outstanding.
- Confirm + Cancel disabled while `mutation.isPending`. Server errors with `field === 'reason'` surface inline; everything else surfaces as a destructive toast.
- `applyApiErrors` not used here — that helper requires a react-hook-form instance, and this dialog only has one field. Inlined a 5-line error parse instead.

### Workstream — G5 audit (acknowledge + CSV export)

Two TODOs in the audit module — but the underlying gaps were asymmetric:

- **G5a — Alert acknowledge:** Backend exists at `POST /api/v1/audit/alerts/{id}/acknowledge` (frontend TODO said PATCH; backend uses POST — corrected). Wired `useMutation` in `AlertsTab`, Confirm + Cancel disabled while `isPending`, `onSuccess` invalidates `['audit', 'alerts']` and toasts, `onError` surfaces a destructive toast with the server message.
- **G5b — Reports CSV export:** Backend has the 6 report fetch endpoints (`/api/v1/audit/reports/actions-by-user`, etc.) but **no `/export` endpoint**. The frontend report tables also still render hardcoded mock arrays — they aren't wired to those fetch endpoints yet.

Honest scope for G5b: don't add a backend export endpoint. Don't wire the 6 report reads either (separate, larger task). Do replace the broken Export button with a client-side CSV generator using the same `Blob + createObjectURL` pattern already proven in `AuditLogTab.exportCSV` and `LoginLogTab.exportCSV`. Refactored `ExportButton` to take `{ filename, headers, rows }` and plumbed those props from each of the 6 tabs. When the report reads land later, the data flows through the same prop — no further changes to ExportButton needed.

### Housekeeping

**`.gitignore` cleanup (`fc6895c`).** Repo had accumulated 7 personal skills under `.claude/skills/` (content-reviewer, gcloud-refresh, plan-week, post, post2, uat, uat-script-generator) plus `.playwright-mcp/` and `.superpowers/` working dirs as side effects of running tools cd'd here. Pattern `.claude/skills/*` + `!.claude/skills/cia/` ignores future bleed-through while keeping the project-canonical CIA skill tracked.

### Verification

- `pnpm --filter @cia/back-office exec tsc --noEmit` → exit 0 (clean)
- `bash cia-frontend/scripts/check-api-wiring.sh` → 0 violations
- `git ls-files .claude/skills/cia/` → still tracked after gitignore change

### Files modified

| File | Why |
| --- | --- |
| [CLAUDE.md](CLAUDE.md) | Container diagram count drift |
| [.gitignore](.gitignore) | Personal skills + tool working dirs |
| [.markdownlint.json](.markdownlint.json) | Disable MD013 + MD040 project-wide |
| [.markdownlintignore](.markdownlintignore) | Exempt cia-log.md from markdownlint entirely (append-only freeform log) |
| [QuotesConfigTab.tsx](cia-frontend/apps/back-office/src/modules/setup/pages/policy-specs/QuotesConfigTab.tsx) | G7 — wire all three CRUDs to backend |
| [ReverseTransactionDialog.tsx](cia-frontend/apps/back-office/src/modules/finance/pages/ReverseTransactionDialog.tsx) | G6 — wire useMutation + reason field |
| [ReceivablesTab.tsx](cia-frontend/apps/back-office/src/modules/finance/pages/receivables/ReceivablesTab.tsx) | G6 — pass id + parentId to dialog |
| [PayablesTab.tsx](cia-frontend/apps/back-office/src/modules/finance/pages/payables/PayablesTab.tsx) | G6 — pass id + parentId to dialog |
| [AlertsTab.tsx](cia-frontend/apps/back-office/src/modules/audit/pages/alerts/AlertsTab.tsx) | G5a — wire acknowledge useMutation + isPending guards |
| [ReportsTab.tsx](cia-frontend/apps/back-office/src/modules/audit/pages/reports/ReportsTab.tsx) | G5b — client-side CSV via Blob + createObjectURL; ExportButton takes filename/headers/rows |

### Sequence B status

| Gap | Status |
| --- | --- |
| G7 — Setup quote-config | ✓ done (`5639820`) |
| G6 — Finance reverse | ✓ done (`de68d50`) |
| G5 — Audit (acknowledge + export) | ✓ done (`76983b9`) — backend export endpoint not added; client-side CSV used. Wiring the 6 report reads is a separate follow-up. |
| G8 — Finance enrichment investigation | next |
| G3 — Reinsurance (7 endpoints) | pending |
| G4 — Claims (6 endpoints) | pending |
| G1 — cia-policy (11 endpoints) | pending |
| G9 — Phase 3 Partner Portal (5 builds) | pending |

### Follow-ups

- `QuoteDetailPage.tsx` still imports `MOCK_DISCOUNT_TYPES`/`MOCK_LOADING_TYPES`/`MOCK_QUOTE_CONFIG` for fallback rendering on the detail page. When that page is wired, the MOCK_ exports can be deleted entirely.
- The audit's TODO list flagged the visible `// TODO:` comments but missed unwired CRUDs that didn't carry comments (the discount/loading types CRUD on this tab). Future audits should also flag local-state CRUD on pages that have a backend controller.
- **Audit reports (6 tables) still hardcoded.** Backend endpoints exist (`/api/v1/audit/reports/{actions-by-user,actions-by-module,approvals,data-changes,login-security,user-activity}`) but the frontend renders mock arrays. Wiring those reads (and adding date-range filter forms) is a separate task — when done, ExportButton already works because the data flows through the same prop.

---

## 2026-05-04 — Session 52: Land all 17 session-51 review items + partner-api compile fix

### Context

Session 51 (cloud-based code reviewer agent) produced a 17-item punch list spanning the diff surface since Session 48: 3 Critical, 5 High, 7 Medium, 3 Low. User directive was absolute: **"We need to fix all items, let's start with C1, C2, C3 then fix (H2,H1,H3) and then every other known issue. It is critical that everything is fixed before we make further changes or updates."**

This session lands all 17 items. Order followed the user's specification exactly: C1→C2→C3→H2→H1→H3→H4→H5→M1→M2→M3→M4→M5→M6→M7→L1→L2→L3, with one bonus fix to unblock cia-partner-api compilation.

### Commits in this session

```
fdf0f0a  fix(critical): Rules-of-Hooks, render-body setValue, query-key mismatches  (C1, C2, C3)
11a09ba  fix(forms): switch 22 forms from formState.isSubmitting to mutation.isPending  (H2)
e004ef4  fix(security): validate pii-key at startup to block SQL injection            (H1)
9288c15  fix(forms): map server field errors + toast fallback                          (H3)
d49b47f  fix(partner-api): segment-aware route matching in PartnerScopeFilter          (H4 + M3)
ab74eb1  fix(review-52): land remaining session-51 review items + partner controller compile fix  (H5, M1, M2, M4, M5, M6, M7, L1, L2, L3, bonus)
```

### What changed by review item

**C1 — Rules of Hooks in ClaimDetailPage.** All 14 useState hooks moved above the loading-skeleton early-return so React doesn't see a different hook order on the first render.

**C2 — setValue in render body in PostReceiptSheet.** Wrapped `form.setValue('amount', totalAmount)` in `useEffect`, gated on a value comparison so it doesn't re-fire when the user is typing.

**C3 — Query-key mismatches.** Aligned `EditCustomerSheet` (`['customer', id]` → `['customers', id]`) and `ProcessPaymentSheet` (invalidate `['finance','payables']` → `['finance','credit-notes']`). Audit also caught a third file beyond the two originally flagged.

**H1 — Hikari pii-key SQL injection.** New `PiiKeyValidator` (cia-common) implements `EnvironmentPostProcessor`, registered via `META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports`. Validates `cia.security.pii-key` against `^[A-Za-z0-9+/=._\-]{12,256}$` before the DataSource bean is created. 17 unit tests including 12 SQL-injection vectors. Without this, a key containing `'`, `;`, or `\n` would inject SQL onto every pooled connection in every tenant.

**H2 — formState.isSubmitting → mutation.isPending.** 22 forms migrated. RHF's `formState.isSubmitting` only flips true while `handleSubmit`'s callback is running — which finishes synchronously when the callback delegates to `useMutation`. Result: spinner disappears the instant the request leaves the browser, and a fast double-click submits twice. `mutation.isPending` stays true until the network response arrives.

**H3 — Field-level error mapping.** New `applyApiErrors()` helper in `apps/back-office/src/lib/form-errors.ts`. For each `{ field, message }` in `response.data.errors`, calls `form.setError(field, ...)` so the error surfaces under the same `<FormMessage />` as Zod messages. Falls through to a destructive toast if no field-level errors (500s, network errors, form-level errors). Wired into all 22 form mutations + 2 multi-form variants. Mounted `<Toaster />` in AppShell.

**H4 — PartnerScopeFilter map collision (+ M3 folded in).** `Map<String,String>` with `Map.ofEntries` is iteration-order-unspecified, and `path.startsWith(mapPath)` matches both `/policies` and `/policies/` prefixes. `POST /partner/v1/policies/p-1/claims` could resolve to either `policies:create` or `claims:create` depending on JVM. Fix: `List<Route>` (declaration-order priority) + Spring `AntPathMatcher` (single-segment `*` wildcards). Most-specific patterns first. Added 20-test `PartnerScopeFilterTest`. M3 folded in: `extractScopes` now wraps claim parse in try/catch returning empty list — malformed JWT scope claim now rejected as 403 (insufficient scope), never propagated as 500.

**H5 — AlertConfigDialog form.reset clobbers input.** Added `keepDirtyValues: true` to `form.reset(configQuery.data, ...)`. RHF preserves any field the user has touched; remaining fields are populated from the refetch.

**M1 — Report export silent truncation.** `ReportRunnerService` now fetches `EXPORT_MAX_ROWS + 1` rows so it can detect when the dataset exceeded the cap. New `CsvExport` and `PdfExport` records carry the truncation flag. `ReportController` surfaces it via `X-Report-Truncated` and `X-Report-Rows` response headers. Body shape unchanged (still valid CSV / valid PDF).

**M2 — typeName resolution race.** SingleRiskQuoteSheet + MultiRiskQuoteSheet disable Save while `loadingTypesQuery.isLoading || discountTypesQuery.isLoading`. `resolveTypeName()` returns `''` when types haven't loaded; submitting that early would persist empty typeName strings into AdjustmentEntry JSONB on the backend.

**M4 — ReportAccessService.upsert XOR.** Added explicit `IllegalArgumentException` when both `category` and `reportId` are non-null. Previously `reportId` silently won, hiding the caller's bug. The DB constraint on `report_access_policy` is XOR; service-layer validation now matches.

**M5 — brokerOptions identity churn.** `useMemo` wrapping in three customer sheets: EditCustomerSheet (with NO_BROKER_OPTION sentinel prepended), CorporateOnboardingSheet, IndividualOnboardingSheet. Stops `<SelectItem>` from being re-keyed every parent render.

**M6 — CI guard regex relaxed.** `check-api-wiring.sh` now matches `^[[:space:]]*const (mock|MOCK_)` (was column-0 only). Caught one real misnaming on first re-run: `DebitNoteAnalysisPage` had `const mockData = byPeriodQuery.data ?? []` — that's actual query data, not a mock. Renamed to `byPeriod`.

**M7 — allow-mock proximity.** CI guard now accepts the `// allow-mock: <reason>` marker anywhere within the 3 lines preceding a declaration (was the immediately preceding line only). Multi-line reasons or a single intervening blank line are now fine.

**L1 — MOCK_CUSTOMERS PII.** Replaced realistic Nigerian names, addresses, phone numbers, and ID numbers with obviously-synthetic placeholders ("Sample Individual N", "+000 000 000 000N", "*.test" emails, "SAMPLE-NIN-NNNN"). The fallback is still useful for layout, but a screenshot or accidental log can no longer resemble a real customer.

**L2 — V24 perf note.** Migration header now documents that `ALTER COLUMN ... TYPE bytea USING pgp_sym_encrypt(...)` rewrites every row and locks ACCESS EXCLUSIVE. Operators planning rollouts for tenants with 100k+ customers can now size maintenance windows correctly. Includes a throughput estimate (10-30k rows/sec, CPU-bound).

**L3 — PII key pre-flight runbook.** Added a 6-step operator checklist to `PiiKeyValidator` javadoc: (1) generate via `openssl rand -base64 32`, (2) store in a secret manager, (3) verify the env var is set pre-deploy, (4) back up to a separate vault location, (5) verify Flyway can read the same key, (6) rotation procedure (no automated path; manual maintenance window). The runbook lives next to the validation regex so they evolve together.

### Bonus — PartnerCustomerController compile fix

`mvn -pl cia-partner-api -am compile` had been failing since the initial commit because `PartnerCustomerController.createIndividual(request)` and `createCorporate(request)` called 1-arg signatures that don't exist — `CustomerService.createIndividual` requires `(IndividualCustomerRequest, MultipartFile)`, and `createCorporate` requires `(CorporateCustomerRequest, MultipartFile, List<MultipartFile>)`.

Partner API is JSON-only by design — partners verify by ID number, not document upload. `uploadKycDocument()` already short-circuits on null files (line 542). Fix: pass `null` for the file args. Added inline comments explaining the design choice and noting that a separate multipart document-upload endpoint can be added later if regulators require originals on file. cia-partner-api now compiles cleanly.

### Verification

```
mvn -pl cia-common,cia-reports,cia-partner-api -am clean compile  → BUILD SUCCESS
mvn -pl cia-partner-api -am test -Dtest=PartnerScopeFilterTest    → 20 tests, 0 failures
mvn -pl cia-common -am test -Dtest=PiiKeyValidatorTest            → 17 tests, 0 failures
pnpm --filter @cia/back-office exec tsc --noEmit                  → no errors
bash cia-frontend/scripts/check-api-wiring.sh                     → no violations
```

### Files modified (across the 6 session-52 commits)

Backend:
- `cia-backend/cia-api/src/main/resources/db/migration/V24__pii_encryption.sql` — V24 perf note
- `cia-backend/cia-common/src/main/java/com/nubeero/cia/common/config/PiiKeyValidator.java` (new) — H1 + L3 runbook
- `cia-backend/cia-common/src/main/resources/META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports` (new) — H1 registration
- `cia-backend/cia-common/src/test/java/com/nubeero/cia/common/config/PiiKeyValidatorTest.java` (new) — H1 tests
- `cia-backend/cia-partner-api/src/main/java/com/nubeero/cia/partner/config/PartnerScopeFilter.java` — H4 + M3
- `cia-backend/cia-partner-api/src/test/java/com/nubeero/cia/partner/config/PartnerScopeFilterTest.java` (new) — H4 tests
- `cia-backend/cia-partner-api/src/main/java/com/nubeero/cia/partner/controller/PartnerCustomerController.java` — bonus compile fix
- `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/controller/ReportController.java` — M1
- `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportRunnerService.java` — M1
- `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportAccessService.java` — M4

Frontend:
- `cia-frontend/apps/back-office/src/lib/form-errors.ts` (new) — H3 helper
- `cia-frontend/apps/back-office/src/app/layout/AppShell.tsx` — Toaster mount
- `cia-frontend/apps/back-office/src/modules/claims/pages/detail/ClaimDetailPage.tsx` — C1
- `cia-frontend/apps/back-office/src/modules/finance/pages/receivables/PostReceiptSheet.tsx` — C2 + H2 + H3
- `cia-frontend/apps/back-office/src/modules/finance/pages/payables/ProcessPaymentSheet.tsx` — C3 + H2 + H3
- `cia-frontend/apps/back-office/src/modules/customers/pages/detail/EditCustomerSheet.tsx` — C3 + M5
- `cia-frontend/apps/back-office/src/modules/audit/pages/alerts/AlertConfigDialog.tsx` — H2 + H3 + H5
- `cia-frontend/apps/back-office/src/modules/customers/pages/detail/CustomerDetailPage.tsx` — L1
- `cia-frontend/apps/back-office/src/modules/customers/pages/corporate/CorporateOnboardingSheet.tsx` — M5
- `cia-frontend/apps/back-office/src/modules/customers/pages/individual/IndividualOnboardingSheet.tsx` — M5
- `cia-frontend/apps/back-office/src/modules/quotation/pages/create/SingleRiskQuoteSheet.tsx` — H2 + H3 + M2
- `cia-frontend/apps/back-office/src/modules/quotation/pages/create/MultiRiskQuoteSheet.tsx` — H2 + H3 + M2
- `cia-frontend/apps/back-office/src/modules/endorsements/pages/reports/DebitNoteAnalysisPage.tsx` — M6 misnaming fix
- 19 other form files across audit, claims, endorsements, finance, policy, quotation, reinsurance, setup modules — H2 + H3

CI:
- `cia-frontend/scripts/check-api-wiring.sh` — M6 + M7

### Postman collection regeneration

Not required this session — no `/partner/v1/` endpoints added or modified at the surface level. PartnerScopeFilter is internal middleware; PartnerCustomerController signatures/contracts unchanged from the partner client's perspective (still JSON in, JSON out).

### Follow-ups

- A separate multipart-aware partner document-upload endpoint should be added if/when regulators require original ID documents on file at the partner-API tier. Currently partners can pass ID numbers but no document copy is captured — KYC verification still runs by number, which is the typical partner integration pattern.
- Session-51 review surface only covered the diff since Session 48. A fresh full-codebase review may surface new findings as Phase 3 (Partner Portal) work proceeds.

---

## 2026-05-03 — Session 50: API-wiring CI guard + final H2 misses

### Context

User asked how to maintain the "all forms use useMutation, all lists use useQuery" invariant going forward. Added a CI guard script + CLAUDE.md convention block so the rule survives subsequent edits — both by humans and AI assistants. Process found 5 additional regressions that were quietly left behind in earlier sweeps.

### Catches found by the new guard on first run

- `IndividualOnboardingSheet`, `CorporateOnboardingSheet`, `EditCustomerSheet` — three broker pickers still rendering hardcoded `mockBrokers`. All now read from `useQuery` against `GET /api/v1/setup/brokers`. `EditCustomerSheet` prepends a `NO_BROKER_OPTION` sentinel so the Channel select can represent "Direct".
- `AddCommentDialog`, `UploadDocumentDialog` (claims module) — two `console.log` form-submit stubs from the original H2 work. Both now take a `claimId` prop alongside the existing display fields and submit via `useMutation` to `POST /api/v1/claims/{id}/comments` and `POST /api/v1/claims/{id}/documents` (multipart for the upload).

### CI guard

`cia-frontend/scripts/check-api-wiring.sh` (new) — bash, runs in <1s. Detects three regression patterns in `cia-frontend/apps/back-office/src/modules/**`:

- `console.log(` anywhere in module code
- top-level `const mockX = [...]` or `const MOCK_X = [...]`
- stale `// TODO: useMutation` / `useQuery` / `useCreate` / `useUpdate`

Each violation prints `file:line` with the offending content. Wired into the existing `frontend` job in `.github/workflows/ci.yml` as the step **before** typecheck. Fails the PR if any violation appears.

### Opt-out marker for legitimate fallbacks

Add `// allow-mock: <reason>` on the line immediately above a deliberate mock to bypass the guard. The reason lands in `git blame`. 19 existing fallbacks were annotated this way in `9d80901` — detail-page in-flight loaders, decorative dialog enrichment, the per-treaty allocation drilldown.

### Files Modified

- `cia-frontend/scripts/check-api-wiring.sh` (new, executable)
- `.github/workflows/ci.yml` — added `API-wiring guard` step to the frontend job
- `CLAUDE.md` → Development Standards → new `Frontend API wiring rules` subsection
- `cia-frontend/apps/back-office/src/modules/customers/pages/individual/IndividualOnboardingSheet.tsx`
- `cia-frontend/apps/back-office/src/modules/customers/pages/corporate/CorporateOnboardingSheet.tsx`
- `cia-frontend/apps/back-office/src/modules/customers/pages/detail/EditCustomerSheet.tsx`
- `cia-frontend/apps/back-office/src/modules/claims/pages/detail/AddCommentDialog.tsx`
- `cia-frontend/apps/back-office/src/modules/claims/pages/detail/UploadDocumentDialog.tsx`
- `cia-frontend/apps/back-office/src/modules/claims/pages/detail/ClaimDetailPage.tsx` — pass `claimId` to both dialogs
- 13 fallback files annotated with `// allow-mock:` markers (audit + reinsurance tabs, detail pages, finance dialogs)

### Git Commits

- `8054d1e` — wire 3 broker pickers to `/api/v1/setup/brokers`
- `4a12d68` — wire AddCommentDialog + UploadDocumentDialog to API
- `9d80901` — annotate 19 legitimate fallback mocks with `// allow-mock:`
- `0159eb7` — CI guard script + CLAUDE.md Frontend API wiring rules

### Verification

- Guard runs clean: `✓ No API-wiring violations.`
- `pnpm --filter @cia/back-office typecheck` clean
- All commits pushed to `main`

### Open Items

- Could add an ESLint custom rule for IDE-time feedback in addition to CI. Lower priority since CI catches the same patterns at PR review.

---

## 2026-05-02 — Session 49: Code review fixes — critical/high/medium + NDPR PII encryption

### Context

Worked through the Session 48 code review findings. Started with 14 issues identified; this session resolved all critical + high + medium findings, deferred H2/M1 (form-to-API wiring across 22+ forms) to a continuation.

### Backend fixes

- **C3 — PartnerScopeFilter OAuth2 scope parsing.** Keycloak issues `scope` as a space-delimited string per RFC 8693, not a JSON array. `jwt.getClaimAsStringList("scope")` returned null for strings, triggering 403 on every partner API call. Added `extractScopes()` that handles both shapes. Hardened `forbidden()` JSON construction with proper escape function (`jsonEscape`).
- **H1 — ReportQueryBuilder result limit.** Added `setMaxResults()` cap: 10,000 for JSON, 100,000 for CSV/PDF exports. ReportRunnerService threads the higher cap through CSV/PDF paths.
- **H4 — Removed `@Async` from AlertDetectionService.** Was breaking `TenantContext` ThreadLocal. Detection logic is lightweight (small COUNT queries), runs synchronously on the request thread.
- **H6 — ReportAccessService.upsert** now correctly sets the `report` relationship on report-level policies (was leaving `report_id` NULL, breaking access-resolution hierarchy).
- **CustomerService** defaults `country` to `"Nigeria"` when omitted from the request, so the frontend doesn't need to send it.
- **V23 migration** — composite index on `audit_log (user_id, action, timestamp)` for `AlertDetectionService.checkBulkDelete()` queries; backfill `customer_number` for any rows that pre-date V20.

### NDPR PII encryption (C2)

- **V24 migration** — `CREATE EXTENSION IF NOT EXISTS pgcrypto`; converts `customers.id_number/id_document_url/address` and `customer_directors.id_number/id_document_url` from plain VARCHAR/TEXT to bytea, encrypting any existing rows in place using `pgp_sym_encrypt(value, current_setting('app.pii_key'))`.
- **Customer.java + CustomerDirector.java** — Hibernate `@ColumnTransformer` wraps reads/writes with `pgp_sym_decrypt` / `pgp_sym_encrypt`. Entity field type stays `String`, transparent to service code.
- **application.yml** — `cia.security.pii-key` reads `PII_ENCRYPTION_KEY` env var; Hikari `connection-init-sql` runs `SET app.pii_key = '<key>'` per connection so Flyway and runtime queries share the key.
- **Search-critical fields** (`first_name`, `last_name`, `email`, `phone`, `date_of_birth`) intentionally remain plain — substring search on encrypted bytea is impossible without companion HMAC-indexed lookup columns. Adding HMAC indexes is a documented follow-up.
- **Pre-existing build break** in `PartnerQuoteResponse.from()` fixed at the same time — was calling removed `getDiscount()` and `getNetPremium()` left over from the V21/V22 quote refactor; replaced with `totalGrossPremium` / `totalNetPremium`.

### Frontend fixes

- **C1 — DevAuthProvider production guard.** Switched the guard from "is `VITE_KEYCLOAK_URL` set?" to "are we in dev mode?" — production builds without Keycloak now fail loud at startup rather than silently shipping unauthenticated mock access.
- **H5 — Removed hardcoded `'Nigeria'`** from `IndividualOnboardingSheet` and `CorporateOnboardingSheet` form submissions. Backend defaults the field if omitted.
- **M3 — `today` constant** in `CorporateOnboardingSheet` moved inside `superRefine` so KYC expiry validation is correct across midnight rollovers.
- **M2 + M6 — QuotePdfPreview refactored.** Added `typeName` (denormalized at construction time) and `validityDays` to `QuotePdfData`; new `computeQuoteSummary()` replaces three separate copies of the per-item gross/loading/discount math. Updated `QuoteDetailPage` and `QuotationListPage` to populate the new fields.
- **H3 — `zodResolver(...) as any`** removed from 11 simple-schema forms. Kept on 18 forms whose schemas use Zod's `coerce`/`transform`/`default` (genuine input/output type divergence — Zod feature, not a defect). Those casts now sit behind `eslint-disable-next-line` comments to mark the intentional escape.

### H2/M1 form-to-API wiring (complete)

All 22 H2 forms wired to live API endpoints, replacing `console.log` stubs with `useMutation` calls. Mock arrays feeding form selects replaced with `useQuery` hooks against the corresponding `/api/v1/...` endpoints. Each form's parent invalidates the appropriate React Query key on success.

**Setup (7 forms):** ProductSheet, ClassSheet, UserSheet, AccessGroupSheet, ApprovalGroupSheet, BrokerSheet, CompanySettingsPage.

**Quotation (2 forms):** SingleRiskQuoteSheet, MultiRiskQuoteSheet — POST `/api/v1/quotes` with denormalized `typeName` on every AdjustmentEntry; live customers/products/loading-types/discount-types from API.

**Policy (1 form, 2 tabs):** CreatePolicySheet — FromQuoteForm POSTs to `/api/v1/policies/bind-from-quote/{quoteId}`; DirectForm POSTs to `/api/v1/policies`. Live customers/products/approved-quotes feeds.

**Endorsement (1 form):** CreateEndorsementSheet — POST `/api/v1/endorsements`; ACTIVE policies query.

**Claims (3 forms):** RegisterClaimSheet (POST `/api/v1/claims`), AddReserveDialog (POST `/api/v1/claims/{id}/reserves`), AddExpenseDialog (POST `/api/v1/claims/{id}/expenses`). The two dialogs gained `claimId` props alongside the existing `claimNumber` (display only).

**Finance (2 forms):** PostReceiptSheet (routes to `/api/v1/finance/receipts/bulk` when in bulk mode, otherwise `/api/v1/finance/receipts`); ProcessPaymentSheet (POST `/api/v1/finance/payments`).

**Reinsurance (5 forms):** TreatySheet (POST/PUT `/api/v1/reinsurance/treaties`), BatchReallocationSheet (POST `/api/v1/reinsurance/allocations/batch-reallocate`), CreateFACOfferSheet (POST `/api/v1/reinsurance/fac/outward`, plus 3 separate query hooks for excess policies / reinsurers / FAC brokers), AddInwardFACSheet (POST `/api/v1/reinsurance/fac/inward`), InwardFACActionSheet (POST `/api/v1/reinsurance/fac/inward/{id}/{renew|extend}`).

**Audit (1 form):** AlertConfigDialog — GET `/api/v1/audit/alert-config` on open + PUT to save. Form resets onto returned config via useEffect.

### M1 list-page wiring (complete)

After H2 was completed, the user pushed back on deferring M1, so the same pass continued through every list/detail page that rendered mock arrays. ~30 pages wired across 10 commits, one per logical group:

- **Quotation** — QuotationListPage, QuoteDetailPage
- **Customers** — CustomersListPage, CustomerDetailPage (with /policies + /claims sub-queries), ActiveCustomersReportPage, LossRatioReportPage
- **Setup** — ProductsPage, ClassesPage, UsersPage, AccessGroupsPage, ApprovalGroupsPage, OrganisationsPage (BrokersTab)
- **Policy** — PolicyListPage, PolicyDetailPage
- **Endorsement** — EndorsementsListPage, EndorsementDetailPage, DebitNoteAnalysisPage (by-period + by-type sub-queries)
- **Claims** — ClaimsListPage, ClaimDetailPage (with /reserves + /expenses sub-queries)
- **Finance** — ReceivablesTab (debit-notes + receipts), PayablesTab (credit-notes + payments)
- **Reinsurance** — TreatiesTab, AllocationsTab, FACTab (outward + inward)
- **Audit** — AuditLogTab, LoginLogTab, AlertsTab — useMemo filtering layer preserved, fetched data feeds in as the source array
- **Reports** — ReportAccessSetupPage — access-group picker now reads live data

Pattern across all wirings: `useQuery` against the matching `/api/v1/...` endpoint; `Skeleton` placeholders while in-flight; falls back to the existing local mock data while loading so the UI stays renderable mid-prototype. Detail pages additionally fall back to local mock when the request hasn't returned, so the page survives unknown ids.

The decorative MOCK_POLICY_DETAIL / MOCK_SOURCE_DETAIL lookups inside the per-row finance detail dialogs intentionally remain — they enrich existing data with product names / source labels and aren't simple list endpoints.

### Files Modified

Backend:

- `cia-backend/cia-partner-api/src/main/java/com/nubeero/cia/partner/config/PartnerScopeFilter.java`
- `cia-backend/cia-partner-api/src/main/java/com/nubeero/cia/partner/controller/dto/PartnerQuoteResponse.java`
- `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportQueryBuilder.java`
- `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportRunnerService.java`
- `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportAccessService.java`
- `cia-backend/cia-audit/src/main/java/com/nubeero/cia/audit/alert/AlertDetectionService.java`
- `cia-backend/cia-customer/src/main/java/com/nubeero/cia/customer/Customer.java`
- `cia-backend/cia-customer/src/main/java/com/nubeero/cia/customer/CustomerDirector.java`
- `cia-backend/cia-customer/src/main/java/com/nubeero/cia/customer/CustomerService.java`
- `cia-backend/cia-api/src/main/resources/application.yml`
- `cia-backend/cia-api/src/main/resources/db/migration/V23__audit_log_index_and_customer_number_backfill.sql` (new)
- `cia-backend/cia-api/src/main/resources/db/migration/V24__pii_encryption.sql` (new)

Frontend:

- `cia-frontend/apps/back-office/src/main.tsx`
- `cia-frontend/apps/back-office/src/modules/customers/pages/individual/IndividualOnboardingSheet.tsx`
- `cia-frontend/apps/back-office/src/modules/customers/pages/corporate/CorporateOnboardingSheet.tsx`
- `cia-frontend/apps/back-office/src/modules/customers/pages/detail/EditCustomerSheet.tsx`
- `cia-frontend/apps/back-office/src/modules/quotation/pages/QuotePdfPreview.tsx`
- `cia-frontend/apps/back-office/src/modules/quotation/pages/QuotationListPage.tsx`
- `cia-frontend/apps/back-office/src/modules/quotation/pages/detail/QuoteDetailPage.tsx`
- `cia-frontend/apps/back-office/src/modules/setup/pages/products/ProductSheet.tsx`
- `cia-frontend/apps/back-office/src/modules/setup/pages/classes/ClassSheet.tsx`
- 18 other forms with selective `zodResolver as any` retention

Docs:

- `CLAUDE.md` — NDPR section + env-vars table updated for `PII_ENCRYPTION_KEY`
- `docs-site/docs/guides/database-migrations.md` — V23 + V24 entries
- `docs-site/docs/guides/environment-variables.md` — `PII_ENCRYPTION_KEY` entry

### Git Commits

- `ef6d94e` — backend fixes (partner scope, report access, async, indexes)
- `d8c304a` — frontend fixes (auth guard, country, quote PDF refactor, type safety)
- `ff1af5a` — V23 migration docs
- `ff1c080` — C2 NDPR PII encryption (V24, @ColumnTransformer, app.pii_key)
- `7033d52` — ProductSheet wired to API
- `7f816c5` — ClassSheet wired to API

### Open Items

- **H2/M1 continuation** — 20 more forms to wire (UserSheet, AccessGroupSheet, ApprovalGroupSheet, BrokerSheet, CompanySettingsPage; Quotation/Policy/Endorsement/Claims/Finance/Reinsurance create flows; AlertConfigDialog). User chose option 1 (quality pace, commit per form). Continuing.
- **NDPR full coverage** — `first_name`, `last_name`, `email`, `phone`, `date_of_birth` still plain. Encrypting them needs HMAC-indexed companion columns to preserve `CustomerRepository.search()` `LIKE` queries. Documented as follow-up.
- **PII key rotation** — no automated path. Manual procedure: maintenance window, decrypt with old key, re-encrypt with new. Documented in V24 migration header.

---

## 2026-05-02 — Session 48: Full codebase code review (frontend, backend, APIs)

### Context

User requested a comprehensive code review of everything built so far across frontend, backend, and APIs. Review conducted by `superpowers:code-reviewer` subagent against CLAUDE.md standards.

### Findings Summary

**Critical (3) — fix before production:**

- **C1.** `DevAuthProvider` can silently activate in production — `main.tsx` guards on `!!import.meta.env.VITE_KEYCLOAK_URL` instead of `import.meta.env.DEV`. If the env var is absent from Vercel, the build ships with unauthenticated mock access.
- **C2.** NDPR PII encryption at rest not implemented — `customers` and `customer_directors` tables store name, DOB, NIN, email, phone, address as plain `VARCHAR`. No `pgcrypto` extension or `@ColumnTransformer` in place.
- **C3.** OAuth2 scope parsing bug in `PartnerScopeFilter.java` — Keycloak issues `scope` as a space-delimited string (RFC 8693), not a JSON array. `jwt.getClaimAsStringList("scope")` returns null for a string, triggering 403 on every partner API call.

**High (6):**

- **H1.** `ReportQueryBuilder.execute()` has no `setMaxResults()` — full table scans on mature tenants.
- **H2.** 20+ form submit handlers are `console.log` stubs, not wired to API mutations (quotes, policies, receipts, payments, treaties).
- **H3.** Widespread `zodResolver(...) as any` cast suppresses TypeScript strict mode.
- **H4.** `AlertDetectionService` uses `@Async` — breaks `TenantContext` ThreadLocal.
- **H5.** Hardcoded `'Nigeria'` country code in `IndividualOnboardingSheet.tsx` and `CorporateOnboardingSheet.tsx`.
- **H6.** `ReportAccessService.upsert()` never sets `report_id` on report-level policies — access hierarchy broken.

**Medium (6):**

- **M1.** Mock data still wired into 59 form select fields (customers, products, brokers, loading/discount types).
- **M2.** `QuotePdfPreview.resolveTypeName()` looks up names from mock data — will show raw IDs when real API is wired.
- **M3.** `today` constant computed at module load in `CorporateOnboardingSheet.tsx`.
- **M4.** Missing composite index on `audit_log (user_id, action, timestamp)` for bulk-delete detection.
- **M5.** `customer_number` column has no backfill for pre-V20 rows.
- **M6.** Premium calculation logic duplicated three times in `QuotePdfPreview.tsx`.

**Positive observations:**

- Module dependency graph clean (`cia-reports` and `cia-audit` correctly isolated).
- `ReportDefinitionService` throws on SYSTEM report mutations.
- `ReportRunnerService.pin()` checks `existsByUserIdAndReportId`.
- `ReportQueryBuilder.sanitizeColumnName()` whitelist correct.
- `AuditAlertConfigService.loadConfig()` uses `findFirstByOrderByCreatedAtAsc()`.
- `WebhookEventListener` correctly synchronous.
- `AuditService.log()` catches all exceptions to prevent audit failures propagating.
- `tokens.css` NairaFallback `@font-face` correctly scoped to `U+20A6`.

### Files Modified

None — review only. No code changes made this session.

### Open Questions

- User has not yet decided which fixes to start with. Recommended priority: Critical #1 (DevAuth) → Critical #3 (scope parsing) → High #5 (form submits) → Critical #2 (NDPR) → High #7 (@Async) → High #9 (report access).

### Git Commit

None — review-only session.

---

## 2026-05-01 — Session 47: Gate — Complete internal-api.json for quotation endpoints

### Context

Session completion gate from the prior session (46c) ran before a final documentation audit revealed gaps in `internal-api.json`. This session documents the fix applied in commit `f404ec4`.

### Files Modified

- `docs-site/static/internal-api.json` — 119 → 127 paths, 36 → 43 schemas
  - **New paths added:** `POST /quotes` (was entirely missing), `GET /quotes` (list with status/customerId/page/size filters)
  - **Updated paths:** `GET /quotes/{id}` response now references `QuoteResponse` schema; `PUT /quotes/{id}` requestBody now references `QuoteUpdateRequest` schema
  - **New schemas added (7):** `AdjustmentEntryRequest`, `AdjustmentEntryResponse`, `QuoteRiskRequest`, `QuoteRiskResponse`, `QuoteRequest`, `QuoteResponse`, `QuoteUpdateRequest`

### Gate Items Verified

- ✅ cia-log.md — this entry
- ✅ CLAUDE.md — updated in gate commit 4f38d7e (Build 4 rows, feature count 5→6, Module Summary)
- ✅ SKILL.md — Quote Premium Formula, Data Model, entities updated in gate commit 4f38d7e
- ✅ database-migrations.md — V21 and V22 entries present
- ✅ internal-api.json — 127 paths / 43 schemas, all quote + setup/quote-config endpoints documented
- ✅ Vercel deploy — docs site deployed after f404ec4 push

### Git Commit

`f404ec4` docs(api): complete quotation endpoints in internal-api.json

---

## 2026-04-28 — Session 46c: Quote PDF margin — increase gap between General Subjectivity and signatures

### Files Modified
- `cia-frontend/apps/back-office/src/modules/quotation/pages/QuotePdfPreview.tsx`
  - `PrintContent` (dialog preview): `mb-8` → `mb-16` on the General Subjectivity `<ol>` — doubles bottom margin before the signature block
  - `buildPrintHtml` (print popup CSS): `.sig { margin-top: 28px }` → `56px` — doubles top margin on the signature row

### Git Commit
`c7288ea` fix(quotation): increase margin between General Subjectivity and signatures in quote PDF

---

## 2026-04-28 — Session 46b: Fix blank PDF on quote download

### Root Causes Found and Fixed

**Frontend — blank print output:**
The `window.print()` CSS isolation approach used `display: none` set inline via JavaScript on `#quote-print-portal` *after* injecting the `@media print` CSS, which re-hid the element before printing ran. The portal was invisible during print despite the `!important` rule.

**Backend — blank/error PDF via API endpoint:**
`QuotePdfService.buildHtml()` generated HTML with `display:flex`, `display:grid`, CSS class attributes (class='right', class='amber'), and the `₦` sign (U+20A6, outside WinAnsi). `HtmlToPdfConverter` only renders `h1/h2/p/table/ul/ol/hr` — CSS class attributes and layout divs fall through to a no-op `default` branch. The `₦` character throws `IllegalArgumentException` in `PDType1Font.showText()` since Helvetica uses WinAnsiEncoding.

### Files Modified
- `cia-frontend/apps/back-office/src/modules/quotation/pages/QuotePdfPreview.tsx`:
  - Added `buildPrintHtml()` — generates a fully self-contained HTML document with embedded `<style>` block (no Tailwind dependency), all quote content, and `window.onload = window.print()` auto-trigger
  - `handlePrint()` now creates a `Blob` from the HTML string, opens it via `URL.createObjectURL()` in a new window — zero CSS specificity issues, isolated rendering context
  - Removed the `#quote-print-portal` hidden div from JSX (no longer needed)
- `cia-backend/cia-quotation/src/main/java/com/nubeero/cia/quotation/QuotePdfService.java`:
  - Rewrote `buildHtml()` to use only tags `HtmlToPdfConverter` supports: `h1`, `h2`, `p`, `table`, `ol`, `hr`
  - Removed all CSS class attributes and `display:flex`/`display:grid` layout divs
  - Replaced `₦` (U+20A6) with ASCII-safe `NGN ` prefix throughout
  - Replaced `appendAdjustments()` (which used `class=` attributes) with `appendAdjTable()` (clean table rows only)
  - Removed unused `addInfo()` helper

### Git Commit
`2176ba7` fix(quotation): blank PDF — replace CSS-portal print with Blob URL popup; fix PDFBox HTML

---

## 2026-04-28 — Session 46a: Backend for quotation module — loadings, discounts, clause selection, PDF, quote config

### Files Created
- `cia-backend/cia-api/src/main/resources/db/migration/V21__quote_config_tables.sql` — `quote_discount_types`, `quote_loading_types`, `quote_config` tables; seeded with 5 discount types, 5 loading types, default config (30 days, LOADING_FIRST)
- `cia-backend/cia-api/src/main/resources/db/migration/V22__quote_adjustments.sql` — adds `rate`, `loadings`, `discounts` JSONB to `quote_risks`; adds `quote_loadings`, `quote_discounts`, `selected_clause_ids`, `inputter_name`, `approver_name` to `quotes`
- `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/quote/` (new package):
  - `CalcSequence.java` — enum: LOADING_FIRST | DISCOUNT_FIRST
  - `QuoteDiscountType.java`, `QuoteLoadingType.java` — entities (soft-delete, unique name)
  - `QuoteConfig.java` — singleton entity (validity_days, calc_sequence)
  - `QuoteDiscountTypeRepository.java`, `QuoteLoadingTypeRepository.java`, `QuoteConfigRepository.java`
  - `QuoteConfigService.java` — CRUD for both type lists + singleton upsert; `fetchConfig()` for QuoteService
  - `QuoteConfigController.java` — 8 endpoints: GET/PUT /quote-config, GET/POST/PUT/DELETE /quote-discount-types, GET/POST/PUT/DELETE /quote-loading-types
  - `dto/AdjustmentTypeRequest.java`, `AdjustmentTypeResponse.java`, `QuoteConfigRequest.java`, `QuoteConfigResponse.java`
- `cia-backend/cia-quotation/src/main/java/com/nubeero/cia/quotation/AdjustmentFormat.java` — enum: PERCENT | FLAT
- `cia-backend/cia-quotation/src/main/java/com/nubeero/cia/quotation/AdjustmentEntry.java` — JSONB value object (typeId, typeName denormalized, format, value)
- `cia-backend/cia-quotation/src/main/java/com/nubeero/cia/quotation/QuotePdfService.java` — HTML → PDF via HtmlToPdfConverter; per-item loading/discount rows, quote-level adjustments, General Subjectivity (3 lines), signature blocks
- `cia-backend/cia-quotation/src/main/java/com/nubeero/cia/quotation/dto/AdjustmentEntryRequest.java`, `AdjustmentEntryResponse.java`

### Files Modified
- `cia-backend/cia-quotation/pom.xml` — added `cia-documents` dependency for HtmlToPdfConverter
- `cia-backend/cia-quotation/src/main/java/com/nubeero/cia/quotation/QuoteRisk.java` — added `rate`, `grossPremium`, `loadings` JSONB, `discounts` JSONB
- `cia-backend/cia-quotation/src/main/java/com/nubeero/cia/quotation/Quote.java` — added `quoteLoadings`, `quoteDiscounts`, `selectedClauseIds` JSONB + `inputterName`, `approverName`
- `cia-backend/cia-quotation/src/main/java/com/nubeero/cia/quotation/QuoteService.java` — full rewrite of premium calculation (LOADING_FIRST/DISCOUNT_FIRST configurable); type names denormalized at save; inputterName from JWT; approverName on approval; validity days from QuoteConfig
- `cia-backend/cia-quotation/src/main/java/com/nubeero/cia/quotation/QuoteController.java` — added `GET /{id}/pdf` endpoint (APPROVED/CONVERTED only, returns application/pdf)
- `cia-backend/cia-quotation/src/main/java/com/nubeero/cia/quotation/dto/QuoteRequest.java` — added quoteLoadings, quoteDiscounts, selectedClauseIds; removed flat discount field
- `cia-backend/cia-quotation/src/main/java/com/nubeero/cia/quotation/dto/QuoteUpdateRequest.java` — added quoteLoadings, quoteDiscounts, selectedClauseIds; removed flat discount field
- `cia-backend/cia-quotation/src/main/java/com/nubeero/cia/quotation/dto/QuoteRiskRequest.java` — added rate, loadings, discounts
- `cia-backend/cia-quotation/src/main/java/com/nubeero/cia/quotation/dto/QuoteRiskResponse.java` — added rate, grossPremium, loadings, discounts
- `cia-backend/cia-quotation/src/main/java/com/nubeero/cia/quotation/dto/QuoteResponse.java` — replaced discount/netPremium with totalGrossPremium/totalNetPremium; added quoteLoadings, quoteDiscounts, selectedClauseIds, inputterName, approverName

### Business Rules Implemented
- Per-item: Gross = SI × Rate; Loaded = Gross + Σloadings; Net = Loaded − Σdiscounts (LOADING_FIRST)
- Quote-level: Final Net = Σ item nets + quote loading (% base = Σ gross) − quote discount
- Calculation sequence (LOADING_FIRST / DISCOUNT_FIRST) configurable per tenant in quote_config
- PDF only available for APPROVED or CONVERTED quotes; throws BusinessRuleException otherwise
- typeName denormalized into JSONB at save time — PDF renders without joins

### Design Decisions
- JSONB chosen over junction tables for loadings/discounts — consistent with existing risk_details pattern; avoids schema proliferation for variable-length arrays
- `typeName` denormalized into AdjustmentEntry at save time so PDF generation needs no additional DB queries
- `total_premium` (existing column) reused for gross total; `net_premium` reused for final net — no new columns needed, avoiding a V23 migration for those fields

### Git Commit
`5ab938a` feat(quotation): backend support for per-item loadings/discounts, clause selection, PDF + quote config

---

## 2026-04-27 — Session 45k: Clause search bar in quote sheets

### Files Modified
- `cia-frontend/apps/back-office/src/modules/quotation/pages/create/MultiRiskQuoteSheet.tsx` — added `clauseSearch` state + search `Input` above the clause list; filters by title or text, case-insensitive
- `cia-frontend/apps/back-office/src/modules/quotation/pages/create/SingleRiskQuoteSheet.tsx` — same change applied

### Git Commit
`33acbf5` feat(quotation): add clause search bar to single-risk and multi-risk quote sheets

---

## 2026-04-27 — Session 45j: Quotation module — loadings, discounts, clauses, PDF download, Quotes config tab

### Files Created
- `cia-frontend/apps/back-office/src/modules/setup/pages/policy-specs/quote-config-types.ts` — shared types: `DiscountType`, `LoadingType`, `QuoteConfig`, `AdjustmentEntry`; mock data for discount types (5), loading types (5), and default quote config (30-day validity, LOADING_FIRST sequence)
- `cia-frontend/apps/back-office/src/modules/setup/pages/policy-specs/QuotesConfigTab.tsx` — new Quotes tab: Discount Types CRUD, Loading Types CRUD, Quote Validity Period input, Premium Calculation Sequence select (LOADING_FIRST / DISCOUNT_FIRST); extensible for future settings
- `cia-frontend/apps/back-office/src/modules/quotation/pages/clauses-shared.ts` — shared clause data (8 clauses) used by both quote sheets and PDF preview
- `cia-frontend/apps/back-office/src/modules/quotation/pages/QuotePdfPreview.tsx` — print-ready Dialog: risk items table with per-item loading/discount rows, quote-level adjustment table, Final Net Premium highlighted, applicable clauses, General Subjectivity section (3 lines: no known loss, validity period with computed expiry date, satisfactory survey), inputter + approver signature blocks; Print/Save as PDF via `window.print()` with isolated print styles

### Files Modified
- `cia-frontend/apps/back-office/src/modules/setup/pages/policy-specs/PolicySpecificationsPage.tsx` — added Quotes tab trigger and content slot
- `cia-frontend/apps/back-office/src/modules/quotation/pages/create/MultiRiskQuoteSheet.tsx` — full rewrite: `AdjustmentRows` sub-component (shared for loadings and discounts); `RiskItemCard` component with nested `useFieldArray` for per-item loadings and discounts; quote-level loadings and discounts; clause selection (scrollable checkbox list from clause bank); live grand total preview
- `cia-frontend/apps/back-office/src/modules/quotation/pages/create/SingleRiskQuoteSheet.tsx` — same loading/discount/clause treatment as multi-risk; replaced single flat discount field with full adjustment arrays
- `cia-frontend/apps/back-office/src/modules/quotation/pages/detail/QuoteDetailPage.tsx` — fixed `useParams()` bug (was always showing first quote); typed MOCK_QUOTES with explicit `MockQuote` interface; expanded risk items card (per-item loading/discount breakdown); clauses card; inputter/approver in details card; Download PDF button (APPROVED/CONVERTED only)
- `cia-frontend/apps/back-office/src/modules/quotation/pages/QuotationListPage.tsx` — added `mockQuotePdfData` map; Download PDF row action for APPROVED and CONVERTED quotes; `QuotePdfPreview` dialog wired to list page

### Business Rules Implemented
- **Premium calculation (LOADING_FIRST):** Gross = SI × Rate%; Loaded = Gross + Σ loadings (% of gross or flat); Item Net = Loaded − Σ discounts (% of loaded or flat)
- **Quote-level adjustments:** Final Net = Σ item nets + quote loading (% of Σ gross) − quote discount (% of quote-loaded base)
- **PDF download:** Only available when quote status is APPROVED or CONVERTED; inputter and approver names both present
- **Calculation sequence:** Configurable in Quotes tab (LOADING_FIRST default); DISCOUNT_FIRST option available
- **Clause selection:** Underwriter selects from existing clause bank; new clauses must be added to Policy Specifications first

### Design Decisions
- Used `RiskItemCard` sub-component with its own `useFieldArray` calls to avoid hooks-in-loops violation for nested loading/discount arrays
- PDF uses `window.print()` with dynamically injected `<style>` (textContent, not innerHTML) scoping print output to `#quote-print-portal` — no extra library dependency
- `as const` on format literals in mock data would narrow types and cause TypeScript to flag `format === 'PERCENT'` comparisons as unreachable — resolved by explicit `MockQuote` interface with `AdjustmentLine` typing

### Git Commit
`42369a3` feat(quotation): per-item loadings/discounts, clause selection, PDF download + Quotes config tab

---

## 2026-04-27 — Session 45f: Clickable policy and claim rows in customer detail

### Change
- `CustomerDetailPage.tsx` — policy rows now navigate to `/policies/:id` on click; claim rows navigate to `/claims/:id`. Added `cursor-pointer`, `hover:bg-muted/40`, and underline on the reference number cell for clear affordance.

### Git Commit
`20df822` fix(customers): make policy and claim rows clickable in customer detail

---

## 2026-04-27 — Session 45e: Hide customer-level KYC section for corporate customers

### Change
- `EditCustomerSheet.tsx` — wrapped the "KYC Identity Document" block (Separator, ID Type, ID Number, expiry date, document upload, reason block) in `{!isCorporate && <>...</>}`. Corporate customer KYC is entirely handled through the directors section; showing a customer-level ID section is not applicable.

### Git Commit
`c1fe3cf` fix(customers): hide customer-level KYC section for corporate customers

---

## 2026-04-27 — Session 45d: Corporate Director Management in Edit Customer Sheet

### Files Created
- `cia-backend/cia-customer/src/main/java/com/nubeero/cia/customer/dto/DirectorUpdateRequest.java` — id (null=new director), deleted flag, name/DOB/KYC fields, kycUpdateReason + kycUpdateNotes

### Files Modified
- `cia-backend/cia-customer/src/main/java/com/nubeero/cia/customer/dto/CustomerUpdateRequest.java` — added `List<DirectorUpdateRequest> directors`
- `cia-backend/cia-customer/src/main/java/com/nubeero/cia/customer/CustomerService.java` — `processDirectorUpdates()`: soft-delete, edit-existing (KYC change detection + reason validation + re-verify + dual audit entry), add-new (verify PENDING directors); `BusinessRuleException` if active directors < 2; `update()` signature extended with directorDocs Map
- `cia-backend/cia-customer/src/main/java/com/nubeero/cia/customer/CustomerController.java` — switched to `MultipartRequest` to extract `idDocument` + `directorDoc_{i}` files
- `cia-frontend/apps/back-office/src/modules/customers/pages/detail/EditCustomerSheet.tsx` — full rewrite: `useFieldArray` for directors, per-director KYC change detection vs originals map, amber reason block per director, Removed/Restore toggle for soft-delete, new directors removable immediately, "active directors < 2" banner disables Save
- `cia-frontend/apps/back-office/src/modules/customers/pages/detail/CustomerDetailPage.tsx` — `directors` added to `MockCustomer` interface; Alaba Trading Co. and Danforth Logistics each have 2 mock directors; snapshot passes directors to EditCustomerSheet

### Business Rules Implemented
- Minimum 2 active directors required for corporate customers — enforced on both backend (BusinessRuleException) and frontend (disabled Save + banner)
- Director KYC field changes require reason (same dropdown as customer-level); "Other" makes notes mandatory
- Director deletion = soft-delete (deleted_at); new directors in the form = removed from array entirely on cancel
- Each director change logged as two audit entries: general UPDATE + dedicated CustomerDirectorKyc UPDATE with reason/notes/kycStatus

### Git Commit
`3a49e63` feat(customers): corporate director management in Edit Customer sheet

---

## 2026-04-27 — Session 45c: Additional Notes required when KYC reason is Other

### Change
- `EditCustomerSheet.tsx` — Zod `superRefine` validates `kycUpdateNotes` is non-empty when `kycUpdateReason === 'Other'`; label toggles between "Additional Notes *" (required) and "Additional Notes (optional)" based on `useWatch` on the reason field.

### Git Commit
`9fc8f1b` feat(customers): make Additional Notes required when KYC reason is Other

---

## 2026-04-27 — Session 45i: Docs Site NubSure Rebrand

### Changes
- `docs-site/docusaurus.config.ts` — title → "NubSure Documentation"; tagline → "NubSure by Nubeero · Developer & Partner Reference"; navbar title → "NubSure Docs"; logo alt → "NubSure Logo"
- `docs-site/src/css/custom.css` — replaced default Docusaurus green with NubSure teal (#1a9e91 light mode, #29d0c0 dark mode) across all 7 Infima color variants; added dark-teal hero gradient, active nav underline, dark footer
- `docs-site/src/pages/index.tsx` — updated SEO description to reference NubSure

### Git Commit
`a010992` feat(docs): rebrand docs site to NubSure Documentation

---

## 2026-04-27 — Session 45h: Confluence PRD Update — Customer Module

### Confluence Page Updated
- **Page:** "7. Customer Onboarding" (ID: 344653826, now v4)
- **URL:** https://akinwalenubeero.atlassian.net/wiki/spaces/CIAGB/pages/344653826/7.+Customer+Onboarding

### Structure of PRD Before Update
Single flat page with 10 features (2.7.1–2.7.10). No child pages. All features as H2 sections with Acceptance Criteria and Business Rules sub-sections.

### Sections Updated

**2.7.1 Individual Onboarding** — Added to required fields: ID Expiry Date (mandatory for DL/Passport, must not be in the past), ID Document Upload (JPG/PNG, max 5MB). Added acceptance criterion: Customer Number generated on creation. Added business rules: document upload mandatory; expiry enforcement; Customer Number format requirement.

**2.7.2 Corporate Onboarding** — Added to required fields: CAC Certificate upload + CAC Issued Date; per-director ID Document Upload; per-director ID Expiry Date (mandatory for DL/Passport). Added acceptance criterion: minimum 2 directors required; Customer Number generated on creation. Added business rules: CAC mandatory; director document mandatory; min-2 directors enforced; director expiry enforcement.

**2.7.5 KYC Update → Edit Customer and KYC Update** — Complete rewrite. New user story: edit contact + KYC from single panel. New acceptance criteria: contact-only edits (email, phone, address, contact person, channel) need no reason; KYC field changes trigger reason-required section (6 predefined options + "Other" which makes notes mandatory); corporate director management (edit/add/delete); min-2 active directors block save; auto-reverification on KYC changes; new KYC replaces current tab record; old KYC preserved in audit log only. Updated business rules accordingly.

**2.7.6 Customer Summary Page** — Updated customer list columns to include Customer Number sub-line and Channel column with "Direct" badge. Added Customer ID clarification: auto-generated formatted number (CUST/2026/IND/00000001), configured in Setup → Customer Number Format. Added tab descriptions including clickable policy and claim rows navigating to detail pages. Updated business rules for formatted Customer ID and clickable rows.

**Unchanged:** 2.7.3, 2.7.4, 2.7.7, 2.7.8, 2.7.9, 2.7.10 — preserved verbatim.

---

## 2026-04-27 — Session 45g: Figma Sync — Editable Frames (not screenshots)

### Why this session
Previous Figma syncs uploaded raster screenshots (flat images). This session creates proper **editable vector frames** using the Figma Plugin API — real text nodes, auto-layout, named layers, and correct OKLCH-mapped colours. All frames are fully editable in Figma.

### Figma File
BackOffice design file: `Zaiu2K7NvEJ7Cjj6z1xt2D`

### Frames Created

| Page | Frame Name | Node ID | Dimensions |
|---|---|---|---|
| Setup | `BackOffice / Setup / Customer Number Format` | `255:2` | 1440×900 |
| Customers | `Sheet: Edit Customer (Individual)` | `260:2` | 480×900 |
| Customers | `BackOffice / Customer / Chioma Okafor / Detail — Updated` | `261:2` | 1440×900 |

### What Each Frame Shows

**Customer Number Format (Setup):**
Full app shell with sidebar (Setup active, Customers sub-nav group visible with Customer Number Format highlighted in teal). Form card: Prefix input ("CUST"), Sequence Digits input ("8"), Include Year toggle (ON), Include Customer Type toggle (ON), Live Preview section showing `CUST/2026/IND/00000001` and `CUST/2026/CORP/00000001`, Save Format button.

**Sheet: Edit Customer (Individual):**
480px side sheet. Header: "Edit Customer" (Bricolage Grotesque SemiBold), description text. Contact Details: Email + Phone (2-col), Address, Channel select. KYC Identity Document: ID Type + ID Number (2-col), Upload zone. Amber KYC Reason Block: "KYC details changed — reason required." label, Reason dropdown ("Document expired"), Additional Notes textarea. Footer: Cancel (outline) + Save Changes (teal).

**Customer Detail — Updated:**
Full app shell, Customers active in sidebar. Page header: "Chioma Okafor" + `Individual · CUST/2026/IND/00000001` sub-line + Verified/Active badges + Edit Customer button + New Policy button. Tabs: Summary (active, teal underline), KYC, Policies (2), Claims (1). Contact Details card: Customer ID as first row, all other fields. Recent Policies panel: policy numbers in teal with underline (clickable affordance), status badges, premiums.

### Technical notes
- Fonts: Bricolage Grotesque SemiBold for headings, Geist Regular/Medium/SemiBold for UI
- Colours: OKLCH design tokens approximated as RGB (teal ≈ #1AB6A4, sidebar ≈ #1C2D2D)
- All frames use auto-layout — editable in Figma without ungrouping
- `resize()` called BEFORE `primaryAxisSizingMode='AUTO'` (lesson learned: resize resets sizing modes to FIXED)
- `layoutSizingHorizontal/Vertical='FILL'` always set AFTER `parent.appendChild(child)`

---

## 2026-04-27 — Session 45b: Edit Customer Sheet with KYC Update Flow

### Files Created
- `cia-frontend/apps/back-office/src/modules/customers/pages/detail/EditCustomerSheet.tsx` — side sheet with contact section (email, phone, address, contactPerson for corporate, channel/broker) + KYC section (ID type, ID number, expiry date, document upload); KYC reason block (dropdown + notes textarea) conditionally rendered only when any KYC field changes; reason required validation enforced client-side before submit

### Files Modified
- `cia-backend/cia-customer/src/main/java/com/nubeero/cia/customer/dto/CustomerUpdateRequest.java` — added idType, idNumber, idExpiryDate, brokerId, kycUpdateReason, kycUpdateNotes fields
- `cia-backend/cia-customer/src/main/java/com/nubeero/cia/customer/CustomerService.java` — update() now accepts MultipartFile idDocument; isKycChanged() detects field-level KYC changes; if changed: validates reason, applies KYC fields, uploads new document, re-runs KYC verification, logs two audit entries (general UPDATE with before/after snapshot + dedicated CustomerKyc UPDATE with reason/notes/kycStatus)
- `cia-backend/cia-customer/src/main/java/com/nubeero/cia/customer/CustomerController.java` — PUT /{id} switched to multipart/form-data to accept optional idDocument file
- `cia-frontend/apps/back-office/src/modules/customers/pages/detail/CustomerDetailPage.tsx` — "Update KYC" renamed to "Edit Customer"; standalone "Re-submit KYC" removed from KYC tab; KYC tab shows "Edit Customer / Update KYC" button instead; EditCustomerSheet wired with customer snapshot; idExpiryDate added to MockCustomer type; Passport/DL records populated with expiry dates

### KYC Reason Dropdown Options
Document expired · Incorrect details submitted · Name mismatch · Customer request · ID type change · Other

### Git Commit
`4407ce0` feat(customers): Edit Customer sheet with KYC update flow

---

## 2026-04-27 — Session 45: KYC Update Flow — Requirements Clarification (in progress)

### Status
Requirements gathering only — no code written this session. Implementation pending.

### Feature Agreed
**Edit Customer Sheet** replaces the inactive "Update KYC" button on the customer detail page.

**What changes:**
- "Update KYC" button → renamed to "Edit Customer"
- Standalone "Re-submit KYC" button removed from the KYC tab
- New `EditCustomerSheet` side sheet with contact + KYC sections

**Individual editable fields:** Email, Phone, Address, Channel (broker), ID type, ID number, expiry date, document upload

**Corporate editable fields:** Email, Phone, Address, Contact Person, Channel (broker), ID type, ID number, expiry date, document upload

**KYC reason section** — conditionally rendered only when ID type, ID number, expiry date, or document changes. Reason = dropdown (Document expired / Incorrect details submitted / Name mismatch / Customer request / ID type change / Other) + optional notes field.

**On save:**
- Contact changes → saved to customer record, audit logged
- If any KYC field changed → new KYC details saved to customer record (shown on KYC tab), old KYC details preserved in audit log as before/after snapshot, reason logged, auto re-submitted to KYC provider, KYC status updated on customer record based on provider response

**KYC tab** → always shows current record only; history visible only in audit log

### Open Questions
None — requirements fully confirmed by user. Ready to implement next session.

---

## 2026-04-26 — Session 44c: Fix customer detail page navigation

### Bug
`CustomerDetailPage` always rendered the hardcoded `c1` mock regardless of which customer was clicked, because `useParams()` was never called.

### Fix
- `cia-frontend/apps/back-office/src/modules/customers/pages/detail/CustomerDetailPage.tsx` — replaced single `mockCustomer` with `MOCK_CUSTOMERS` array (all 5 records, each with full individual/corporate fields); added `useParams<{id}>()` to resolve the route param; lookup by ID with `EmptyState` fallback for unknown IDs; summary tab now conditionally renders individual fields (DOB, occupation, ID type/number) vs corporate fields (RC number, industry, contact person, directors); policies and claims keyed per customer ID so c1 shows real data while others show empty-state messages

### Git Commit
`13023e9` fix(customers): detail page reads :id from URL — shows correct customer

---

## 2026-04-26 — Session 44b: Direct Customer Channel Indicator

### Change
- `cia-frontend/apps/back-office/src/modules/customers/pages/CustomersListPage.tsx` — renamed Broker column to **Channel**; direct customers (no `brokerId`) now display a styled "Direct" badge instead of `—`; broker-enabled customers continue to show the broker name. Makes onboarding channel visible at a glance on the customer list.

### Git Commit
`b1c6cd4` feat(customers): show Direct badge for non-broker customers in list

---

## 2026-04-26 — Session 44: Tenant-Configurable Customer Number Format

### PRD Verification
- Confirmed "Customer ID" is explicitly required by PRD 2.7.6 (Customer Summary Page): listed as a display field alongside Name, Email, Phone; also referenced as a clickable identifier in the customer list.
- Confirmed the PRD does not specify the format — "Customer ID" is the only mention. Decision made to implement as tenant-configurable (Option B), consistent with the existing policy number format pattern in Setup.

### Decision: Customer Number Format Design
- **Singleton per tenant** (not per product) — one row in `customer_number_format` table, configurable by System Admin.
- **Format:** `{prefix}/{year}/{type}/{sequence}` — e.g. `CUST/2026/IND/00000001`, `CUST/2026/CORP/00000001`
- **`includeType` flag** — when true, appends IND or CORP and maintains **separate sequences per type** (lastSequenceIndividual / lastSequenceCorporate). When false, uses a single shared sequence.
- **`sequenceLength` defaults to 8** — supports up to 99,999,999 per type per year (user escalated from 5-digit default).
- **PESSIMISTIC_WRITE** lock on `customer_number_format` during generation — prevents duplicates under concurrent onboardings.

### Files Created
- `cia-backend/cia-api/src/main/resources/db/migration/V20__customer_number_format.sql` — adds `customer_number VARCHAR(60) UNIQUE` to `customers`; creates `customer_number_format` singleton table
- `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/customer/CustomerNumberFormat.java` — entity
- `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/customer/CustomerNumberFormatRepository.java` — findFirstByDeletedAtIsNull + PESSIMISTIC_WRITE findForUpdate
- `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/customer/CustomerNumberFormatService.java` — generateNext(customerType), get(), upsert()
- `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/customer/CustomerNumberFormatController.java` — GET/PUT /api/v1/setup/customer-number-format
- `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/customer/dto/CustomerNumberFormatRequest.java`
- `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/customer/dto/CustomerNumberFormatResponse.java`
- `cia-frontend/apps/back-office/src/modules/setup/pages/customer-number-format/CustomerNumberFormatPage.tsx` — Setup page with live format preview (useMemo mirrors backend generateNext logic)

### Files Modified
- `cia-backend/cia-customer/pom.xml` — added `cia-setup` dependency
- `cia-backend/cia-customer/src/main/java/com/nubeero/cia/customer/Customer.java` — added `customerNumber` field
- `cia-backend/cia-customer/src/main/java/com/nubeero/cia/customer/CustomerService.java` — injected CustomerNumberFormatService; generateNext called in createIndividual and createCorporate
- `cia-backend/cia-customer/src/main/java/com/nubeero/cia/customer/dto/CustomerResponse.java` — added customerNumber
- `cia-backend/cia-customer/src/main/java/com/nubeero/cia/customer/dto/CustomerSummaryResponse.java` — added customerNumber
- `cia-frontend/apps/back-office/src/modules/setup/layout/SetupLayout.tsx` — added "Customers" nav group with Customer Number Format link
- `cia-frontend/apps/back-office/src/modules/setup/index.tsx` — added /setup/customer-number-format route
- `cia-frontend/apps/back-office/src/modules/customers/pages/CustomersListPage.tsx` — customer number shown as monospace sub-line under customer name
- `cia-frontend/apps/back-office/src/modules/customers/pages/detail/CustomerDetailPage.tsx` — customer number in page header description + Customer ID row in summary tab
- `docs-site/static/internal-api.json` — 119 → 120 paths; added /setup/customer-number-format GET+PUT + CustomerNumberFormat schema

### Git Commit
`c2c8fe3` feat(customers): tenant-configurable customer number format

---

## 2026-04-20

### Session 1 — Project Setup & Planning

**Changes made:**

- `.claude/settings.json` — Created project-level permissions file. Allowed: WebSearch, WebFetch, and non-destructive Bash commands (source, export, curl, jq, cat, ls, grep, echo, which, wc, file, pwd, mkdir, touch, head, tail, find, sort, tree, diff, node, npm, npx, git status, git diff, git log).

- `.claude/settings.local.json` — Created local settings file with `ANTHROPIC_API_KEY` env placeholder. Gitignored by default.

- `.claude/skills/cia/SKILL.md` — Created the `cia` Claude skill. Encodes full domain context: 8 modules, 128 features, tech stack, multi-tenancy model, Nigerian regulatory integrations (NAICOM, NIID, NDPR), key business rules, data model highlights, and development conventions.

- `CLAUDE.md` — Created project CLAUDE.md. Codifies project overview, tech stack decisions with rationale, architecture, module inventory, development standards, and open questions.

**Decisions made:**

- **Stack confirmed:** React + Vite (frontend), Java 21 + Spring Boot 3 (backend), PostgreSQL schema-per-tenant, Keycloak (auth), Temporal (workflows), MinIO S3-compatible adapter (storage).
- Better Auth → replaced with **Keycloak** (Java ecosystem fit, self-hostable).
- Inngest → replaced with **Temporal** (mature Java SDK, durable workflows, self-hostable, used in financial systems at scale).
- Storage abstracted behind S3-compatible interface for cloud-agnostic / on-prem deployment.
- Claude API integration is **optional and feature-flagged per tenant**.

**PRD ingested:**

- Source: [CIAGB Confluence](https://akinwalenubeero.atlassian.net/wiki/spaces/CIAGB/overview)
- All 8 module pages read in full (Setup & Admin, Quotation, Policy, Endorsements, Claims, Reinsurance, Customer Onboarding, Finance).

**Open questions (pending clarification):**

- ~~KYC provider~~ → **Provider-agnostic** (resolved 2026-04-20)
- ~~Phase 1 module priority~~ → **Confirmed order:** Setup → Customer → Quotation → Policy → Finance → Endorsements → Claims → Reinsurance (resolved 2026-04-20)
- ~~Email/SMS notification provider~~ → **Provider-agnostic** (`NotificationService` abstraction — email + SMS implementations via config) (resolved 2026-04-20)
- ~~NAICOM/NIID API access~~ → **Stub adapters** confirmed. Post-approval async Temporal workflow with exponential backoff retry. Approval flow never blocks on NAICOM/NIID. Swap to live adapter via Spring profile when credentials arrive. (resolved 2026-04-20)

---

## 2026-04-21

### Session 2 — System Architecture, Partner Open API Design & Backend Scaffold

**Architecture documentation:**

- `CLAUDE.md` — Replaced generic `## Architecture` section with comprehensive `## System Architecture` (11 subsections: request flow, multi-tenancy, security layers, module topology, workflow engine, document generation, storage abstraction, KYC abstraction, partner API platform, AI integration, regulatory integrations). Added `## Partner Open API Platform` section (9: target users, API surface, OAuth2 CC auth, webhook system, rate limiting, docs deliverables, partner management, sandbox).

**Skill updated:**

- `.claude/skills/cia/SKILL.md` — Updated module count (8 → 9 modules, 128 → 143 features). Added Module 9 — Partner Open API (15 features). Added partner entities to data model. Added `## SESSION COMPLETION GATE` section with mandatory 6-item protocol (cia-log.md, CLAUDE.md, OpenAPI endpoints, Postman collection, backend APIs). Added mandatory `@Operation` / `@ApiResponse` / `@SecurityRequirement` annotation requirements for all partner controllers.

**Hooks added:**

- `.claude/settings.json` — Added `Stop` hook (displays 6-item SESSION COMPLETION GATE checklist to user on session end) and `PreCompact` hook (injects gate checklist into model context via `hookSpecificOutput.additionalContext` before compaction).

**Backend scaffold created — `cia-backend/` (Maven multi-module):**

Parent POM: `com.nubeero.cia:cia-backend:1.0.0-SNAPSHOT`, Spring Boot 3.3.5 parent, Java 21. 17 modules declared in build order. Key version pins: Temporal 1.25.0, MapStruct 1.5.5.Final, Springdoc 2.5.0, PDFBox 3.0.2, MinIO 8.5.11, AWS SDK v2 2.25.60, Bucket4j 0.12.7, Testcontainers 1.20.1.

**`cia-common` module — shared infrastructure:**

| File | Description |
| --- | --- |
| `tenant/TenantContext.java` | ThreadLocal holding current tenant schema name; `setTenantId`, `getTenantId`, `clear` |
| `tenant/MultiTenantConnectionProvider.java` | Hibernate `MultiTenantConnectionProvider<String>`; sets PostgreSQL schema per connection |
| `tenant/TenantIdentifierResolver.java` | Hibernate `CurrentTenantIdentifierResolver<String>`; reads from TenantContext or defaults to "public" |
| `entity/BaseEntity.java` | `@MappedSuperclass`; UUID PK, JPA-audited createdAt/updatedAt/createdBy, softDelete() |
| `api/ApiResponse.java` | Generic response envelope: `{ data, meta, errors }` with static factories |
| `api/ApiMeta.java` | Pagination metadata: total, page, size, nextCursor, prevCursor |
| `api/ApiError.java` | Error detail: code, message, field |
| `exception/CiaException.java` | Base RuntimeException with errorCode + HttpStatus |
| `exception/ResourceNotFoundException.java` | 404 for missing entities |
| `exception/BusinessRuleException.java` | 422 for business rule violations |
| `exception/GlobalExceptionHandler.java` | `@RestControllerAdvice`; handles CiaException, validation, unexpected errors |
| `audit/AuditAction.java` | Enum: CREATE, UPDATE, DELETE, APPROVE, REJECT, SUBMIT, SEND, CANCEL, REVERSE, EXECUTE |
| `audit/AuditLog.java` | `@Entity audit_log`; entity snapshots with JSONB old/new values |
| `audit/AuditLogRepository.java` | JPA repository; query by entity, user, time range |
| `audit/AuditService.java` | Writes audit records; resolves userId/userName from SecurityContextHolder JWT |
| `config/CiaCommonAutoConfiguration.java` | `@EnableJpaAuditing`; `AuditorAware` bean reading JWT subject |

**`cia-auth` module — Keycloak / Spring Security:**

| File | Description |
| --- | --- |
| `TenantContextFilter.java` | `OncePerRequestFilter`; reads `tenant_id` JWT claim → TenantContext |
| `JwtAuthConverter.java` | Maps `realm_access.roles` to `ROLE_*` Spring authorities |
| `SecurityConfig.java` | `@EnableWebSecurity`; stateless JWT, permits health/partner-docs, adds TenantContextFilter |
| `AuthenticatedUserService.java` | `currentUserId()`, `currentUserName()`, `currentTenantId()`, `hasRole()` |

**`cia-storage` module — document storage abstraction:**

| File | Description |
| --- | --- |
| `DocumentStorageService.java` | Interface: upload, download, delete, presignedUrl |
| `config/StorageProperties.java` | `@ConfigurationProperties(cia.storage)`: type, endpoint, bucket, credentials, region |
| `impl/MinioStorageService.java` | MinIO adapter; `@ConditionalOnProperty(cia.storage.type=minio)` |
| `impl/S3StorageService.java` | AWS S3 adapter; `@ConditionalOnProperty(cia.storage.type=s3)` |
| `config/StorageAutoConfiguration.java` | MinioClient + S3Client + S3Presigner beans, conditional per storage type |

**`cia-notifications` module — notification abstraction:**

| File | Description |
| --- | --- |
| `model/NotificationChannel.java` | Enum: EMAIL, SMS |
| `model/NotificationRequest.java` | recipient, subject, body, channel, tenantId |
| `model/NotificationResult.java` | success, providerId, errorMessage |
| `NotificationService.java` | Interface with `send()` and default `supports(channel)` |
| `impl/EmailNotificationService.java` | JavaMailSender SMTP adapter; conditional on `cia.notifications.email.enabled` |
| `impl/SmsNotificationService.java` | Stub logging adapter (Termii/Infobip/Twilio TBD) |
| `impl/CompositeNotificationService.java` | `@Primary` router — delegates to matching channel service |
| `config/NotificationsAutoConfiguration.java` | `JavaMailSender` bean from `spring.mail.*` properties |

**`cia-integrations` module — external provider stubs:**

KYC: `IndividualKycRequest`, `CorporateKycRequest`, `DirectorKycRequest`, `KycResult`, `KycVerificationService` (interface), `MockKycService` (`@Profile("dev | test")`), `DojahKycService` (stub, `cia.kyc.provider=dojah`), `PremblyKycService` (stub, `cia.kyc.provider=prembly`).

NAICOM: `NaicomUploadRequest`, `NaicomUploadResult`, `NaicomService` (interface), `StubNaicomService` (default, `cia.naicom.mode=stub`), `NaicomRestService` (live stub — pending credentials).

NIID: `NiidUploadRequest`, `NiidUploadResult`, `NiidService` (interface), `StubNiidService` (default), `NiidRestService` (live stub — pending credentials).

**`cia-workflow` module — Temporal workflow definitions:**

| File | Description |
| --- | --- |
| `config/TemporalConfig.java` | `WorkflowServiceStubs`, `WorkflowClient`, `WorkerFactory` beans |
| `TemporalQueues.java` | Constants: approval-queue, naicom-upload-queue, niid-upload-queue, notification-queue, webhook-dispatch-queue |
| `approval/ApprovalWorkflow.java` | `@WorkflowInterface`; `@WorkflowMethod runApproval`, `@SignalMethod approve/reject`, `@QueryMethod getStatus` |
| `approval/ApprovalRequest.java` | entityType, entityId, tenantId, initiatedBy, amount, currency |
| `approval/ApprovalStatus.java` | Enum: PENDING, APPROVED, REJECTED |
| `approval/ApprovalActivity.java` | `@ActivityInterface`; `notifyApprovers`, `finaliseApproval` |
| `naicom/NaicomUploadWorkflow.java` | `@WorkflowInterface`; `uploadPolicy(policyId, tenantId)` |
| `naicom/NaicomUploadActivity.java` | `fetchPolicyPayload`, `uploadToNaicom`, `updatePolicyCertificate` |
| `webhook/WebhookDispatchWorkflow.java` | `@WorkflowInterface`; `dispatch(WebhookDispatchRequest)` |
| `webhook/WebhookDispatchRequest.java` | webhookRegistrationId, tenantId, eventType, payloadJson, timestamp |
| `webhook/WebhookDispatchActivity.java` | `send(WebhookDispatchRequest) → WebhookDeliveryResult` |
| `webhook/WebhookDeliveryResult.java` | success, httpStatus, responseBody, errorMessage |

**`cia-partner-api` module — Insurtech Open API platform:**

| File | Description |
| --- | --- |
| `config/PartnerSecurityConfig.java` | `@Order(1)` SecurityFilterChain scoped to `/partner/**`; OAuth2 JWT resource server |
| `config/OpenApiConfig.java` | Springdoc `OpenAPI` bean (bearer + OAuth2 CC schemes) + `GroupedOpenApi` for `/partner/v1/**` |
| `config/RateLimitConfig.java` | Documents Bucket4j Redis rate-limit config (tuned via application.yml) |
| `app/PartnerApp.java` | `@Entity partner_apps`; clientId, appName, contactEmail, tenantId, active, PartnerPlan |
| `app/PartnerPlan.java` | Enum: SANDBOX, STARTER, GROWTH, ENTERPRISE |
| `app/PartnerAppRepository.java` | JPA repository; `findByClientId` |
| `webhook/WebhookRegistration.java` | `@Entity webhook_registrations`; partnerAppId, targetUrl, secret, eventTypes, active |
| `webhook/WebhookRegistrationRepository.java` | JPA repository; `findByPartnerAppIdAndActiveTrue` |
| `webhook/WebhookDispatchActivityImpl.java` | Temporal activity impl; HMAC-SHA256 signed HTTP POST delivery |
| `controller/PartnerProductController.java` | `GET /partner/v1/products`; placeholder with full Springdoc `@Operation` / `@ApiResponse` annotations |

**`cia-api` module — main application:**

| File | Description |
| --- | --- |
| `CiaApplication.java` | `@SpringBootApplication(scanBasePackages="com.nubeero.cia")` |
| `resources/application.yml` | Full application config: datasource, JPA multi-tenancy, Flyway, Keycloak JWT, mail, Redis, Temporal, storage, NAICOM/NIID/KYC stubs, partner API, Springdoc, Bucket4j, logging |
| `resources/application-dev.yml` | Dev overrides: SQL logging, DEBUG levels, all stubs enabled |
| `resources/db/migration/V1__create_public_schema.sql` | `tenants` table (schema registry) in public schema |
| `resources/db/migration/V2__create_tenant_schema_template.sql` | `template_` schema with `audit_log`, `webhook_registrations`, `partner_apps` tables |

**`docker-compose.yml` — local dev environment:**

Services: PostgreSQL 16, Keycloak 24.0, Temporal 1.25.0 (auto-setup), Temporal UI 2.26.2, MinIO (latest), Redis 7 (alpine). `cia-api` service commented out (uncomment when ready). Volumes: `postgres_data`, `minio_data`.

**OpenAPI endpoints added this session:**

| Method | Path                 | Module          | Description                                       |
| ------ | -------------------- | --------------- | ------------------------------------------------- |
| GET    | /partner/v1/products | cia-partner-api | List insurance products available to partner      |

**Partner API authentication:** OAuth2 Client Credentials flow. Token URL: `{KEYCLOAK_URL}/realms/cia/protocol/openid-connect/token`. Swagger UI available at `/partner/docs`. OpenAPI spec at `/partner/v3/api-docs`.

**Next session — build order:**

1. `cia-setup` module — Module 1: Setup & Administration (35 features): products, classes of business, approval groups, master data, partner app management.
2. `cia-customer` module — Module 7: Customer Onboarding & KYC (10 features).
3. `cia-quotation` module — Module 2: Quotation (5 features).
4. Continue in PRD build order: Policy → Finance → Endorsements → Claims → Reinsurance.

---

## 2026-04-20 (continued)

### Session 3 — cia-setup Module: Full REST API Layer

**Module completed:** `cia-setup` — Module 1 (Setup & Administration). All 26 controllers written covering all 35 features.

**Flyway migration:**

`V3__create_setup_tables.sql` — 30 tables across all setup domains.

**Entities written (previously):** `CompanySettings`, `PasswordPolicy`, `Bank`, `Currency`, `AccessGroup`, `AccessGroupPermission`, `ApprovalGroup`, `ApprovalGroupLevel`, `ClassOfBusiness`, `Product`, `ProductSection`, `CommissionSetup`, `PolicySpecification`, `PolicyNumberFormat`, `ClaimDocumentRequirement`, `ClaimNotificationTimeline`, `SurveyThreshold`, `NatureOfLoss`, `CauseOfLoss`, `ClaimReserveCategory`, `Sbu`, `Branch`, `Broker`, `RelationshipManager`, `Surveyor`, `InsuranceCompany`, `ReinsuranceCompany`, `VehicleMake`, `VehicleModel`, `VehicleType`.

**REST controllers — 26 endpoints:**

| Controller | Path | Notes |
| --- | --- | --- |
| `CompanySettingsController` | `GET/PUT /api/v1/setup/company-settings` | Singleton upsert |
| `BankController` | `CRUD /api/v1/setup/banks` | |
| `CurrencyController` | `CRUD /api/v1/setup/currencies` | |
| `AccessGroupController` | `CRUD /api/v1/setup/access-groups` | Nested permissions list |
| `ApprovalGroupController` | `CRUD /api/v1/setup/approval-groups` + `GET /by-entity-type/{entityType}` | Nested levels |
| `ClassOfBusinessController` | `CRUD /api/v1/setup/classes-of-business` | |
| `ProductController` | `CRUD /api/v1/setup/products` | Nested sections |
| `NatureOfLossController` | `CRUD /api/v1/setup/nature-of-loss` | |
| `CauseOfLossController` | `CRUD /api/v1/setup/cause-of-loss` + `GET /by-nature/{natureOfLossId}` | |
| `ClaimReserveCategoryController` | `CRUD /api/v1/setup/claim-reserve-categories` | |
| `SbuController` | `CRUD /api/v1/setup/sbus` | |
| `BranchController` | `CRUD /api/v1/setup/branches` | FK: Sbu |
| `BrokerController` | `CRUD /api/v1/setup/brokers` | |
| `RelationshipManagerController` | `CRUD /api/v1/setup/relationship-managers` + `GET /by-branch/{branchId}` | FK: Branch |
| `SurveyorController` | `CRUD /api/v1/setup/surveyors` | SurveyorType enum |
| `InsuranceCompanyController` | `CRUD /api/v1/setup/insurance-companies` | |
| `ReinsuranceCompanyController` | `CRUD /api/v1/setup/reinsurance-companies` | |
| `VehicleTypeController` | `CRUD /api/v1/setup/vehicle-types` | |
| `VehicleMakeController` | `CRUD /api/v1/setup/vehicle-makes` | |
| `VehicleModelController` | `CRUD /api/v1/setup/vehicle-makes/{makeId}/models` | Nested sub-resource |
| `CommissionSetupController` | `CRUD /api/v1/setup/products/{productId}/commission-setups` | |
| `PolicySpecificationController` | `GET/PUT /api/v1/setup/products/{productId}/policy-specification` | Singleton upsert |
| `PolicyNumberFormatController` | `GET/PUT /api/v1/setup/products/{productId}/policy-number-format` | Singleton upsert; `generateNext()` used by policy module |
| `ClaimDocumentRequirementController` | `CRUD /api/v1/setup/products/{productId}/claim-document-requirements` | |
| `ClaimNotificationTimelineController` | `GET/PUT /api/v1/setup/products/{productId}/claim-notification-timeline` | Singleton upsert |
| `SurveyThresholdController` | `CRUD /api/v1/setup/products/{productId}/survey-thresholds` | |

**Key design decisions:**

- All controllers use `@PreAuthorize("hasRole('SETUP_VIEW|CREATE|UPDATE|DELETE')")` — Keycloak roles map to `ROLE_SETUP_*` Spring authorities.
- Product-linked singletons (PolicySpec, PolicyNumberFormat, ClaimNotificationTimeline) use PUT for upsert — avoids client-side "does it exist?" checks.
- Sub-resource controllers (VehicleModel under VehicleMake, product-config under Product) enforce parent ownership in service layer — cross-parent access returns 404.
- `PolicyNumberFormatService.generateNext()` uses `@Lock(PESSIMISTIC_WRITE)` to prevent duplicate sequence numbers under concurrent policy approvals.
- `AccessGroupService.softDelete()` cascades through `permissions.clear()` on update; orphanRemoval handles DB cleanup.
- `AuditService.log()` called on every write; catches all exceptions so audit failure never breaks the business operation.

**Next session — build order:**

1. `cia-customer` module — Module 7: Customer Onboarding & KYC (10 features).
2. `cia-quotation` module — Module 2: Quotation (5 features).
3. Continue in PRD build order: Policy → Finance → Endorsements → Claims → Reinsurance.

---

## 2026-04-21 (continued)

### Session 4 — cia-customer, cia-quotation, cia-policy, cia-finance, cia-endorsement, cia-claims

**Modules completed:** cia-customer (24 files), cia-quotation (21 files), cia-policy (21 files), cia-finance (37 files), cia-endorsement (18 files), cia-claims (34 files).

**Flyway migrations added:**

| Migration | Tables |
|---|---|
| `V4__create_customer_tables.sql` | `customers`, `customer_directors`, `customer_documents` |
| `V5__create_quotation_tables.sql` | `quote_counters`, `quotes`, `quote_risks`, `quote_coinsurance_participants` |
| `V6__create_policy_tables.sql` | `policy_counters`, `policies`, `policy_risks`, `policy_coinsurance_participants`, `policy_documents` |
| `V7__create_finance_tables.sql` | `debit_note_counters`, `credit_note_counters`, `receipt_counters`, `payment_counters`, `debit_notes`, `credit_notes`, `receipts`, `payments` |
| `V8__create_endorsement_tables.sql` | `endorsement_counters`, `endorsements`, `endorsement_risks` |
| `V9__create_claims_tables.sql` | `claim_counters`, `claims`, `claim_reserves`, `claim_expenses`, `claim_documents` |

**Key files created — cia-customer:**

| File | Description |
|---|---|
| `Customer.java` | Entity; `CustomerType` (INDIVIDUAL/CORPORATE), `KycStatus`, `IdType` enum fields; soft-delete |
| `CustomerDirector.java` | Corporate director entity; linked to Customer |
| `CustomerDocument.java` | KYC document upload entity |
| `CustomerService.java` | `createIndividual()`, `createCorporate()`, `update()`, `retriggerKyc()`, `blacklist()`, `unblacklist()` |
| `CustomerController.java` | Full CRUD + KYC retrigger + blacklist endpoints |
| `CustomerDocumentService/Controller` | Multipart upload, download, delete |
| DTOs | `IndividualCustomerRequest`, `CorporateCustomerRequest`, `CustomerDirectorRequest`, `CustomerResponse`, `CustomerSummaryResponse`, `CustomerUpdateRequest`, `BlacklistRequest` |

**Key files created — cia-quotation:**

| File | Description |
|---|---|
| `Quote.java` | Entity; `QuoteStatus` (DRAFT/SUBMITTED/APPROVED/REJECTED/CONVERTED/EXPIRED), `BusinessType` |
| `QuoteRisk.java` | Risk line item on a quote |
| `QuoteCoinsuranceParticipant.java` | Coinsurance participant |
| `QuoteService.java` | `create()`, `update()`, `submit()`, `approve()`, `reject()`, `markConverted()` |
| `QuoteController.java` | Full REST surface with `@PreAuthorize` |
| `QuoteNumberService.java` | Gap-free sequential quote numbers; `@Lock(PESSIMISTIC_WRITE)` |

**Key files created — cia-policy:**

| File | Description |
|---|---|
| `Policy.java` | Entity; `PolicyStatus`, `BusinessType`; NAICOM/NIID UID fields; `policyDocumentPath` |
| `PolicyRisk.java` | Risk item; `riskDetails` JSONB |
| `PolicyService.java` | `bindFromQuote()`, `create()`, `submit()`, `approve()`, `reject()`, `cancel()`, `reinstate()`, `triggerNaicomUpload()` |
| `PolicyController.java` | Full REST; `@PreAuthorize` per action |
| `PolicyNumberService.java` | Gap-free sequential numbers |

Policy approval publishes `PolicyApprovedEvent` with 14 fields (including RI allocation fields added later).

**Key files created — cia-finance:**

| File | Description |
|---|---|
| `DebitNote.java` / `CreditNote.java` | Finance note entities; linked to source entity type + ID |
| `Receipt.java` / `Payment.java` | Settlement entities |
| `FinanceService.java` | Creates debit/credit notes; receipt + payment approval workflows |
| Event listeners | `PolicyApprovedEventListener` → debit note; `EndorsementApprovedEventListener` → debit/credit note; `ClaimApprovedEventListener` → credit note; `FacPremiumCededEventListener` → credit note |

**Key files created — cia-endorsement:**

| File | Description |
|---|---|
| `Endorsement.java` | Entity; `EndorsementStatus`, `EndorsementType` (ADDITIONAL_PREMIUM/RETURN_PREMIUM/NON_PREMIUM_BEARING) |
| `EndorsementRisk.java` | Risk snapshot on endorsement |
| `EndorsementService.java` | `create()`, `submitForApproval()`, `approve()`, `reject()`, `cancel()`; pro-rata premium calculation |
| `EndorsementNumberService.java` | Gap-free sequential numbers |

**Key files created — cia-claims:**

| File | Description |
|---|---|
| `Claim.java` | Entity; `ClaimStatus` (REGISTERED/UNDER_INVESTIGATION/RESERVED/PENDING_APPROVAL/APPROVED/SETTLED/REJECTED/WITHDRAWN) |
| `ClaimReserve.java` / `ClaimExpense.java` / `ClaimDocument.java` | Sub-entities |
| `ClaimService.java` | Full lifecycle: `register()`, `assignSurveyor()`, `setReserve()`, `submitForApproval()`, `approve()`, `reject()`, `withdraw()`, `markSettled()` |
| `ClaimController.java` | Full REST surface |
| `ClaimNumberService.java` | Gap-free sequential numbers |

**Common events published from this session (in cia-common):**

| Event | Published by | Consumed by |
|---|---|---|
| `PolicyApprovedEvent` | `PolicyService.approve()` | cia-finance (debit note), cia-reinsurance (auto-allocation), cia-partner-api (webhook) |
| `EndorsementApprovedEvent` | `EndorsementService.approve()` | cia-finance (debit/credit note), cia-partner-api (webhook) |
| `ClaimApprovedEvent` | `ClaimService.approve()` | cia-finance (credit note), cia-partner-api (webhook) |

---

## 2026-04-21 (continued)

### Session 5 — cia-reinsurance Module

**Module completed:** `cia-reinsurance` — Module 6 (Reinsurance). 37 Java files.

**Flyway migration:** `V10__create_reinsurance_tables.sql`

Tables: `ri_counters`, `ri_fac_counters`, `ri_treaties`, `ri_treaty_participants`, `ri_allocations`, `ri_allocation_lines`, `ri_fac_covers`.

**Enums:** `TreatyType` (SURPLUS, QUOTA_SHARE, XOL), `TreatyStatus` (DRAFT, ACTIVE, EXPIRED, CANCELLED), `AllocationStatus` (DRAFT, CONFIRMED, CANCELLED), `FacCoverStatus` (PENDING, CONFIRMED, CANCELLED).

**Key files:**

| File | Description |
|---|---|
| `RiTreaty.java` | Treaty entity; retentionLimit, surplusCapacity, quotaSharePercent, xolLimit per treaty type |
| `RiTreatyParticipant.java` | Reinsurer share on a treaty |
| `RiAllocation.java` / `RiAllocationLine.java` | Per-policy RI allocation with retained/ceded split |
| `RiFacCover.java` | Outward facultative cover |
| `AllocationService.java` | SURPLUS/QUOTA_SHARE/XOL strategies; `autoAllocate()` wrapped in try/catch — RI failure never blocks policy approval |
| `PolicyApprovedEventListener.java` | Listens for `PolicyApprovedEvent`; triggers `autoAllocate()` |
| `FacCoverService.java` | `confirm()` publishes `FacPremiumCededEvent` |
| `RiNumberService.java` | Sequential `RIA-YYYY-NNNNNN` and `FAC-YYYY-NNNNNN` format; `REQUIRES_NEW` transaction |
| `RiTreatyController.java` | `GET/POST/PUT/DELETE /api/v1/ri/treaties` |
| `RiAllocationController.java` | `GET/POST /api/v1/ri/allocations` |
| `RiFacCoverController.java` | `GET/POST/PUT /api/v1/ri/fac-covers` |

**New events added to cia-common:**

| Event | Fields |
|---|---|
| `FacPremiumCededEvent` | facCoverId, facReference, policyId, policyNumber, reinsuranceCompanyId, reinsuranceCompanyName, premiumCeded, commissionAmount, netPremiumCeded, currencyCode |

**Cross-module changes:**

- `PolicyApprovedEvent` enriched with 4 new RI fields: `productId`, `classOfBusinessId`, `totalSumInsured`, `policyStartDate`
- `ReinsuranceCompanyRepository` — added `findByIdAndDeletedAtIsNull(UUID id)` (was missing)
- `cia-reinsurance/pom.xml` — added `cia-policy` and `cia-setup` dependencies

---

## 2026-04-21 (continued)

### Session 6 — cia-documents Module

**Module completed:** `cia-documents` — PDF generation module. 13 Java files + 3 HTML templates.

**Flyway migration:** `V11__add_document_tables.sql`

```sql
CREATE TABLE document_templates (id, template_type, product_id, class_of_business_id, storage_path, description, active, created_at, ...);
ALTER TABLE endorsements ADD COLUMN document_path VARCHAR(500);
ALTER TABLE claims ADD COLUMN dv_document_path VARCHAR(500);
```

**Key files:**

| File | Description |
|---|---|
| `DocumentGenerationService.java` | Interface; all methods return `null` on failure — approval flow is never blocked |
| `DocumentGenerationServiceImpl.java` | Resolves template (DB → MinIO → classpath fallback); renders via Thymeleaf; converts to PDF via PDFBox; stores via DocumentStorageService |
| `HtmlToPdfConverter.java` | Walks JSoup HTML tree; renders h1/h2/h3/p/br/hr/ul/ol/table/b to PDFBox; auto page breaks; word wrapping |
| `DocumentEngineConfig.java` | `@Bean("documentTemplateEngine")` with `StringTemplateResolver` — isolated from main Thymeleaf engine |
| `DocumentTemplateService.java` | CRUD; `upload()` deactivates prior active template for same type+scope |
| `DocumentTemplateController.java` | `POST /api/v1/document-templates` (multipart), GET list/single, DELETE |
| Context records | `PolicyDocumentContext`, `EndorsementDocumentContext`, `ClaimDvContext` |
| Templates | `policy-default.html`, `endorsement-default.html`, `claim-dv-default.html` (Thymeleaf inline `[[${var}]]`) |

**Cross-module changes:**

| Module | Change |
|---|---|
| `cia-policy / PolicyService.approve()` | Added `DocumentGenerationService` injection; generates + stores policy PDF on approval; stores path in `policy_document_path` |
| `cia-endorsement / EndorsementService.approve()` | Added PDF generation; stores path in `document_path` |
| `cia-claims / ClaimService.approve()` | Added DV PDF generation; stores path in `dv_document_path` |
| `cia-endorsement / Endorsement.java` | Added `document_path` field |
| `cia-claims / Claim.java` | Added `dv_document_path` field |

**Technical decisions:**

- PDFBox 3.x API: `Standard14Fonts.FontName.HELVETICA` (not deprecated PDFBox 2.x constants)
- `getStringWidth()` returns units/1000 — multiply by fontSize for actual points
- `sanitise()` strips non-WinAnsi characters (PDFBox chokes on them)
- jsoup `1.17.2` added explicitly — Spring Boot BOM does not manage it directly

---

## 2026-04-22

### Session 7 — cia-partner-api Module (Full Implementation)

**Module completed:** `cia-partner-api` — Module 9 (Partner Open API). Upgraded from 10 skeletal files to 27 files. Covers all 15 endpoints in spec.

**Flyway migration:** `V12__create_partner_tables.sql`

Tables: `partner_apps`, `webhook_registrations`, `webhook_delivery_logs`.

**New files:**

| File | Description |
|---|---|
| `app/PartnerApp.java` | Enriched with `scopes`, `rateLimitRpm`, `allowedIps`, `plan`; `@Setter` added |
| `app/PartnerAppService.java` | CRUD; `create()` checks duplicate `clientId`; `toggleActive()`; `softDelete()` |
| `app/dto/CreatePartnerAppRequest.java` | Validation: `@Email`, `@NotBlank`, `@Positive` |
| `webhook/WebhookRegistration.java` | `partnerAppId` corrected to `UUID`; `@Setter` added |
| `webhook/WebhookDeliveryLog.java` | Audit entity; `webhookRegistrationId`, `eventType`, `payloadJson`, `success`, `httpStatus`, `responseBody`, `errorMessage`, `attempt` |
| `webhook/WebhookDeliveryLogRepository.java` | JPA repository |
| `webhook/WebhookEvent.java` | Enum: 10 event types; `eventName()` converts `CLAIM_APPROVED` → `claim.approved` |
| `webhook/WebhookService.java` | `register()`, `list()`, `findOrThrow()`, `delete()`; `publish()` fans out to all active matching registrations via Temporal |
| `webhook/WebhookRegistrationRepository.java` | `findAllByPartnerAppIdAndDeletedAtIsNull()`, `findByIdAndDeletedAtIsNull()`, `findAllByActiveTrue()` |
| `webhook/WebhookEventListener.java` | Listens for `PolicyApprovedEvent`, `EndorsementApprovedEvent`, `ClaimApprovedEvent`, `ClaimSettledEvent`; synchronous (not `@Async`) so `TenantContext` ThreadLocal is still set |
| `webhook/WebhookDispatchActivityImpl.java` | Upgraded: now logs every delivery to `webhook_delivery_logs` |
| `webhook/WebhookDispatchWorkflowImpl.java` | Temporal workflow impl; 4-attempt retry, exponential backoff (30s → 10min) |
| `webhook/dto/RegisterWebhookRequest.java` | `targetUrl`, `secret` (min 16 chars), `eventTypes` |
| `config/PartnerScopeFilter.java` | `OncePerRequestFilter`; enforces OAuth2 scope per endpoint path+method after JWT validation |
| `config/PartnerSecurityConfig.java` | Added `PartnerScopeFilter` registration after `TenantContextFilter`; removed unused `@Value` |
| `config/WebhookWorkerConfig.java` | `@PostConstruct` registers `WebhookDispatchWorkflowImpl` + activity on `WEBHOOK_QUEUE` |
| `controller/PartnerProductController.java` | `GET /partner/v1/products`, `GET /partner/v1/products/{id}`, `GET /partner/v1/products/{id}/classes` |
| `controller/PartnerQuoteController.java` | `POST /partner/v1/quotes`, `GET /partner/v1/quotes/{id}` |
| `controller/PartnerCustomerController.java` | `POST /partner/v1/customers/individual`, `POST /partner/v1/customers/corporate`, `GET /partner/v1/customers/{id}` |
| `controller/PartnerPolicyController.java` | `POST /partner/v1/policies` (bind from quote), `GET /partner/v1/policies/{id}`, `GET /partner/v1/policies/{id}/document` |
| `controller/PartnerClaimController.java` | `POST /partner/v1/policies/{policyId}/claims`, `GET /partner/v1/claims/{id}` |
| `controller/PartnerWebhookController.java` | `POST/GET /partner/v1/webhooks`, `DELETE /partner/v1/webhooks/{id}`; resolves `partnerAppId` from JWT `partner_app_id` claim |
| `controller/PartnerAppController.java` | Internal admin: `GET/POST /api/v1/partner-apps`, `PATCH /{id}/activate`, `DELETE /{id}`; `@PreAuthorize("hasAuthority('setup:*')")` |
| `docs/postman_environment.json` | Postman environment with `baseUrl`, `keycloakUrl`, `tenantRealm`, `clientId`, `clientSecret`, `accessToken` |
| `docs/developer-guide.md` | Full integration guide: auth, scopes, quick start, webhook verification, rate limits, error format, sandbox |

**Cross-module changes:**

| Module | File | Change |
|---|---|---|
| `cia-common` | `ClaimSettledEvent.java` | New event: `claimId`, `claimNumber`, `policyId`, `policyNumber`, `customerId`, `customerName`, `settledAt` |
| `cia-claims` | `ClaimService.markSettled()` | Now publishes `ClaimSettledEvent` |
| `cia-api` | `config/TemporalWorkerStarter.java` | New: `@EventListener(ApplicationReadyEvent)` starts `WorkerFactory` after all module workers are registered via `@PostConstruct` — fixes project-wide gap |
| `cia-partner-api` | `pom.xml` | Added `cia-auth` and `cia-setup` as explicit dependencies |

**Design decisions:**

- Partner API is a **pure facade** — zero business logic; all rules enforced by existing business module services.
- Webhook listeners are **synchronous** (not `@Async`) so `TenantContext` ThreadLocal is available; actual HTTP delivery is async inside Temporal.
- `TemporalWorkerStarter` fires on `ApplicationReadyEvent` — guarantees all `@PostConstruct` worker registrations across all modules complete before `factory.start()`.
- `partnerAppId` resolved from JWT `partner_app_id` custom claim (set at Keycloak client creation time).

**Postman collection regeneration required** — new endpoints added. Run: `mvn package -pl cia-partner-api` (openapi-generator-maven-plugin executes at package phase).

**Open questions:** None — both items from Session 7 closed in Session 8.

---

### Session 8 — cia-partner-api: @Schema Annotations + Document Streaming

**Items closed from Session 7:**

1. **`@Schema` annotations on all partner API DTOs** — CLOSED.
2. **Document streaming in `GET /partner/v1/policies/{id}/document`** — CLOSED.

**New partner DTO layer introduced** (all in `cia-partner-api/src/.../partner/controller/dto/`):

| File | Description |
|---|---|
| `PartnerClaimResponse.java` | Partner-safe projection of `Claim` entity; omits internal workflow, surveyor, and withdrawal fields; includes static `from(Claim)` factory |
| `PartnerWebhookResponse.java` | Partner-safe projection of `WebhookRegistration`; omits `secret`; splits comma-delimited `eventTypes` into `List<String>` |
| `PartnerPolicyResponse.java` | Partner projection of `PolicyResponse`; omits internal workflow ID and user audit fields; includes `@Schema` on class + every field |
| `PartnerQuoteResponse.java` | Partner projection of `QuoteResponse`; `@Schema` on class + every field |
| `PartnerCustomerResponse.java` | Partner projection of `CustomerResponse`; omits `kycProviderRef`, `alternatePhone`, `directors`, `documents`; `@Schema` on class + every field |
| `PartnerProductResponse.java` | Partner projection of `ProductResponse`; omits `sections`; `@Schema` on class + every field |
| `PartnerClassOfBusinessResponse.java` | Partner projection of `ClassOfBusinessResponse`; `@Schema` on class + every field |

**Architectural decision:** `@Schema` annotations live only in `cia-partner-api` (where springdoc is a dependency). Business modules (`cia-policy`, `cia-quotation`, `cia-customer`, `cia-setup`) do not depend on swagger-annotations — documentation concerns belong in the API surface module, not domain modules.

**Updated controllers (all 6 partner controllers now have full `@ApiResponse` annotations):**

| Controller | Change |
|---|---|
| `PartnerProductController.java` | Switched to `PartnerProductResponse`/`PartnerClassOfBusinessResponse`; added `@ApiResponse` for all response codes |
| `PartnerQuoteController.java` | Switched to `PartnerQuoteResponse`; added `@ApiResponse` for all response codes |
| `PartnerCustomerController.java` | Switched to `PartnerCustomerResponse`; added `@ApiResponse` for all response codes |
| `PartnerPolicyController.java` | Switched to `PartnerPolicyResponse`; wired `DocumentStorageService` for real PDF streaming; added `@ApiResponse` for all response codes |
| `PartnerClaimController.java` | Switched from `Claim` entity to `PartnerClaimResponse`; added `@ApiResponse` for all response codes |
| `PartnerWebhookController.java` | Switched from `WebhookRegistration` entity to `PartnerWebhookResponse`; added `@ApiResponse` for all response codes |

**pom.xml changes:**

- `cia-partner-api/pom.xml` — Added `cia-storage` as explicit dependency (required for `DocumentStorageService` injection)

**Document streaming implementation (`PartnerPolicyController.downloadDocument`):**

- Reads `TenantContext.getTenantId()` for storage tenant isolation
- Calls `documentStorageService.download(tenantId, policy.getPolicyDocumentPath())`
- Returns `InputStreamResource` with `Content-Type: application/pdf` and `Content-Disposition: attachment; filename="policy-{policyNumber}.pdf"`
- Returns 404 if `policyDocumentPath` is null (policy not yet approved)

**Postman collection regeneration required** — partner DTO types changed. Run: `mvn package -pl cia-partner-api`

**Open questions:** None.

---

### Session 9 — Backend Verification, GitHub Repo, CI Pipeline, Docusaurus Docs Site

**Primary deliverables:**

1. Backend compiled and full test suite run (`mvn verify`)
2. Private GitHub repo created and pushed (`RazorMVP/CoreInsurance`)
3. GitHub Actions CI pipeline covering all four testing layers
4. Docusaurus documentation site on GitHub Pages

---

**Compilation fixes applied:**

| File | Problem | Fix |
|---|---|---|
| `cia-backend/pom.xml` | `temporal-spring-boot-starter-alpha:1.25.0` does not exist in Maven Central | Renamed to `temporal-spring-boot-starter` (artifact renamed from v1.24+) |
| `cia-backend/cia-workflow/pom.xml` | Same artifact rename + missing `cia-integrations` dependency (required by `NaicomUploadActivity`/`NiidUploadActivity`) | Added both fixes |
| `cia-endorsement/EndorsementService.java` | `workflow::startApproval` (no such method) + `new ApprovalRequest(…)` positional constructor (no-arg Lombok `@Builder`) | Changed to `workflow::runApproval` + builder pattern |
| `cia-claims/ClaimService.java` | Same pattern as EndorsementService | Same fix |
| `cia-documents/DocumentGenerationServiceImpl.java` | `Map.of()` called with 12–13 entries (limit is 10) | Switched to `Map.ofEntries(entry(…), …)` |
| `cia-finance/CreditNoteController.java` | `BaseEntity.getCreatedAt()` returns `Instant`; `CreditNoteResponse` expects `OffsetDateTime` | Added `ZoneOffset.UTC` conversion |

**Runtime environment:** Java 21 required (Lombok 1.18.36 is incompatible with Java 25 due to removed `com.sun.tools.javac.code.TypeTag` internals).

---

**GitHub repository:**

- Remote: `https://github.com/RazorMVP/CoreInsurance` (private)
- All backend modules, frontend, docs-site, CI workflows pushed to `main`

---

**CI pipeline (`.github/workflows/ci.yml`):**

| Job | Runner | Status |
|---|---|---|
| `backend` | `ubuntu-latest` / Java 21 / Maven | Active — runs `mvn verify` with Testcontainers (Docker socket available on ubuntu-latest) |
| `frontend` | `ubuntu-latest` / Node 20 | Stubbed (`if: false`) — Vitest runs cleanly; enables when frontend reaches feature parity |
| `docs` | `ubuntu-latest` / Node 20 | Stubbed (`if: false`) — enables when docs build is fully validated |

**Docs deploy pipeline (`.github/workflows/docs-deploy.yml`):** GitHub Pages deployment from `docs-site/build/`; jobs stubbed with `if: false` until docs build is stable.

---

**OpenAPI source artifact (`cia-backend/cia-partner-api/docs/openapi.json`):**

- Hand-crafted OpenAPI 3.1.0 spec checked into the repo as a build-time source artifact
- Covers all 15 partner API endpoints across 7 resource groups
- Drives Postman collection generation at build time via `openapi-generator-maven-plugin`
- Springdoc validates runtime output against this spec

---

**Docusaurus site (`docs-site/`):**

- Docusaurus 3.10 + React 19; targets `https://razormvp.github.io/CoreInsurance/`
- **Dropped `docusaurus-theme-openapi-docs`** — React 19 SSR incompatibility (`useTabsContext()` outside `Tabs.Provider` during static generation); replaced with sidebar links to live Swagger UI at `/partner/docs`
- **Webpack `webpackbar` v7 override** — `@docusaurus/bundler` nested `webpackbar@6.x` passed invalid props to webpack's `ProgressPlugin`; forced to v7 via npm overrides (later removed when openapi plugin was dropped)

**Internal developer documentation written:**

| Doc | Path |
|---|---|
| Architecture Overview | `docs/architecture/overview.md` |
| Module Inventory | `docs/architecture/modules.md` |
| Multi-Tenancy | `docs/architecture/multi-tenancy.md` |
| Security Architecture | `docs/architecture/security.md` |
| Workflow Architecture | `docs/architecture/workflows.md` |
| Integrations | `docs/architecture/integrations.md` |
| Local Setup Guide | `docs/guides/local-setup.md` |
| Tenant Provisioning | `docs/guides/tenant-provisioning.md` |
| Environment Variables | `docs/guides/environment-variables.md` |
| Database Migrations | `docs/guides/database-migrations.md` |
| Coding Standards | `docs/development/coding-standards.md` |
| Testing Guide | `docs/development/testing.md` |
| Adding a Module | `docs/development/adding-a-module.md` |

**Partner API documentation written:**

| Doc | Path |
|---|---|
| Partner API Overview | `docs/partner/overview.md` |
| Authentication Guide | `docs/partner/authentication.md` (cURL, TypeScript, Python, Java examples) |
| Webhook Integration | `docs/partner/webhooks.md` (TypeScript + Python signature verification) |
| Rate Limiting | `docs/partner/rate-limiting.md` |
| Sandbox Environment | `docs/partner/sandbox.md` |

**Open questions:** None from this session.

---

## 2026-04-23

### Session — Audit & Compliance Module (Module 10) + Build Fixes + Docs Update

**New Maven module: `cia-audit`**

| File | Description |
|---|---|
| `cia-audit/pom.xml` | New module; deps: cia-common, cia-notifications, commons-csv:1.10.0 |
| `V16__create_audit_module_tables.sql` | Adds `approval_amount` column to `audit_log`; creates `login_audit_log`, `audit_alert_config` (singleton row seeded), `audit_alert` tables |

**`cia-common` extensions:**

| File | Change |
|---|---|
| `AuditLog.java` | Added `approval_amount NUMERIC(19,2)` field |
| `AuditLogRepository.java` | Added `JpaSpecificationExecutor<AuditLog>`, `countByUserIdAndActionAndTimestampAfter()`, JPQL `findUserActivitySummary()` with `UserActivityProjection` inner interface |
| `AuditService.java` | Added `ApplicationEventPublisher`; refactored to publish `AuditLogCreatedEvent` after every save; added `logWithAmount()` overload |
| `AuditLogCreatedEvent.java` | New Spring `ApplicationEvent` wrapping `AuditLog` |

**`cia-audit` entities / repos / DTOs / services / controllers — all new:**

| Layer | Files |
|---|---|
| Entities | `AlertType`, `AuditAlertConfig`, `AuditAlert`, `LoginEventType`, `LoginAuditLog` |
| Repositories | `AuditAlertConfigRepository`, `AuditAlertRepository`, `LoginAuditLogRepository` |
| DTOs | `AuditLogFilter`, `AuditLogResponse`, `LoginAuditLogResponse`, `AuditAlertResponse`, `AuditAlertConfigRequest/Response`, `UserActivitySummary` |
| Services | `AuditQueryService`, `LoginAuditService`, `AuditAlertConfigService`, `AuditAlertService`, `AlertDetectionService`, `AuditExportService`, `AuditReportService` |
| Controllers | `AuditLogController`, `LoginAuditController`, `AuditAlertController`, `AuditAlertConfigController`, `AuditExportController`, `AuditReportController` |

**API endpoints added (15):**

| Endpoint | Notes |
|---|---|
| `GET /api/v1/audit/logs` | Filterable audit log with pagination |
| `POST /api/v1/auth/session/start` | Login event recording (public — requires valid JWT) |
| `POST /api/v1/auth/session/end` | Logout event recording |
| `POST /api/v1/auth/login/failed` | Failed login recording (**public endpoint** — no JWT) |
| `GET /api/v1/audit/login-logs` | Login log viewer |
| `GET /api/v1/audit/alerts` | List alerts (with `?unacknowledgedOnly=true`) |
| `POST /api/v1/audit/alerts/{id}/acknowledge` | Acknowledge an alert |
| `GET /api/v1/setup/audit-config` | Read alert config (AUDIT_VIEW + SETUP_UPDATE) |
| `PUT /api/v1/setup/audit-config` | Update alert config (SETUP_UPDATE only) |
| `GET /api/v1/audit/export` | CSV export of audit log (text/csv, streaming) |
| `GET /api/v1/audit/reports/actions-by-user` | Report 1 |
| `GET /api/v1/audit/reports/actions-by-module` | Report 2 |
| `GET /api/v1/audit/reports/approvals` | Report 3 |
| `GET /api/v1/audit/reports/data-changes` | Report 4 |
| `GET /api/v1/audit/reports/login-security` | Report 5 |
| `GET /api/v1/audit/reports/user-activity` | Report 6 |

**Other changes:**

| File | Change |
|---|---|
| `CiaApplication.java` | Added `@EnableAsync` for `AlertDetectionService` |
| `SecurityConfig.java` | Added `AntPathRequestMatcher("/api/v1/auth/login/failed")` to permit list |
| `cia-backend/pom.xml` | Upgraded Lombok from `1.18.36` → `1.18.46` (JDK 25 compatibility fix) |

**Documentation updated:**

| Doc | What changed |
|---|---|
| `CLAUDE.md` | Module Summary: added row 10; Backend Module Inventory: added `cia-audit`; Dependency Graph: added `cia-audit` entry |
| `SKILL.md` | Frontmatter: 9 → 10 modules, 143 → 158 features; added Module 10 section; added 4 new entities; added 8 new development conventions |
| `docs-site/docs/architecture/modules.md` | Added `cia-audit` to inventory and cross-module dependency table |
| `docs-site/docs/architecture/overview.md` | Module count 18 → 19; added row 10 to Business Modules table |
| `docs-site/docs/architecture/security.md` | Replaced placeholder stub with full security documentation |
| `docs-site/docs/guides/local-setup.md` | Updated Lombok troubleshooting note for JDK 24+ |

**Decisions made:**

- `cia-audit` depends only on `cia-common` + `cia-notifications` — zero dependency on business modules.
- `audit_alert_config` is a singleton per tenant (one row, seeded by migration); `loadConfig()` always reads `findFirstByOrderByCreatedAtAsc()`.
- Off-hours login detection is handled directly in `LoginAuditController.loginFailed()` via `checkFailedLogins()`, not via `AuditLogCreatedEvent` (logins are not in `AuditLog`).
- `AuditAction.LOGIN` does not exist — login events use `LoginEventType` in a separate table.
- System Auditor role (`AUDIT_VIEW`) is strictly read-only; only System Admin (`SETUP_UPDATE`) can modify alert config.

**Open questions:** None.

---

## 2026-04-24

### Session 4 — Frontend Monorepo Scaffold

**Files created:**

| File | Description |
|---|---|
| `cia-frontend/package.json` | pnpm workspace root; Turborepo + TypeScript devDeps |
| `cia-frontend/pnpm-workspace.yaml` | Declares `apps/*` and `packages/*` workspaces |
| `cia-frontend/turbo.json` | Pipeline: build, dev, lint, typecheck with `^build` dependency |
| `cia-frontend/tsconfig.base.json` | Shared TS config: ES2022, bundler moduleResolution, strict |
| `cia-frontend/.impeccable.md` | Design context: users, brand, aesthetic, font selection, principles |
| `packages/ui/src/tokens.css` | Full OKLCH design token file: Nubeero teal/charcoal palette, shadcn semantic tokens, status tokens, dark mode |
| `packages/ui/tailwind.config.ts` | Shared Tailwind config mapping CSS vars to Tailwind utilities |
| `packages/ui/src/components/button.tsx` | shadcn Button with CIA brand variants |
| `packages/ui/src/components/badge.tsx` | Status Badge: active/pending/rejected/draft/cancelled variants |
| `packages/api-client/src/client.ts` | `createApiClient()` + `initApiClient()` + `setTokenGetter()` — env-agnostic |
| `packages/api-client/src/types.ts` | `ApiResponse<T>`, `PageResponse<T>`, `ApiMeta`, `ApiError` |
| `packages/auth/src/keycloak.ts` | Keycloak instance + `configureKeycloak()` + init/refresh helpers |
| `packages/auth/src/AuthProvider.tsx` | React context: user, token, roles, `hasRole()`, `logout()` |
| `apps/back-office/src/app/layout/AppShell.tsx` | Sidebar + Topbar + `<Outlet />` |
| `apps/back-office/src/app/layout/Sidebar.tsx` | Three nav groups; teal active state; user profile + logout |
| `apps/back-office/src/app/layout/Topbar.tsx` | Route-aware page title + notification icon |
| `apps/back-office/src/app/router.tsx` | Lazy-loaded module routes + skeleton fallback |
| `apps/back-office/src/modules/dashboard/DashboardPage.tsx` | Stats grid + recent activity |
| `apps/back-office/src/modules/*/index.tsx` | Stub entry points for 9 business modules |
| `apps/partner/` | Dark-mode portal skeleton; port 5174 |

**Decisions made:**

- pnpm + Turborepo selected; `^build` chain ensures `@cia/ui` builds before apps.
- Two apps: `@cia/back-office` (light, port 5173) and `@cia/partner` (dark, port 5174).
- Three shared packages: `@cia/ui`, `@cia/api-client`, `@cia/auth`.
- OKLCH color tokens stored as full `oklch(L C H)` values (not channels) for devtools readability.
- Fonts: Bricolage Grotesque (headings) + Geist (body) via Google Fonts.
- Icon library: hugeicons v1.1.6 (`@hugeicons/react`).
- Shared packages are Vite env-agnostic; apps call `configureKeycloak()` and `initApiClient()` at startup.
- Figma BackOffice file (fileKey: `Zaiu2K7NvEJ7Cjj6z1xt2D`) currently empty — designs stubbed as modules are built.
- `tsc --noEmit` passes with zero errors on `@cia/back-office`.

**Open questions:**

- Partner portal auth flow: needs OAuth2 Client Credentials (machine-to-machine), not Keycloak human login.
- Figma `get_design_context` requires Figma desktop app open with node selected (desktop plugin mode).

---

### Session 4b — UI Housecleaning (NubSure rebrand + topbar/sidebar enhancements)

**Files modified:**

| File | Change |
|---|---|
| `apps/back-office/index.html` | Title + description updated to "NubSure"; favicon set to `/logo.png` |
| `apps/back-office/public/logo.png` | Nubeero PNG logo copied from `/Users/razormvp/Documents/Nubeero_Images/nubeeroLogo/` |
| `apps/back-office/src/app/layout/AppShell.tsx` | Added `collapsed` state; passes to `Sidebar` and `Topbar`; sidebar `<aside>` uses `width` + `transition` for smooth collapse |
| `apps/back-office/src/app/layout/Sidebar.tsx` | Full rewrite: logo PNG, "NubSure" name, hugeicons for all 10 modules, font 13→15px, collapsible (icon-only at 64px), `title` tooltip on collapsed items |
| `apps/back-office/src/app/layout/Topbar.tsx` | Added hamburger toggle (left), search bar (flex-1, always visible), notification + help icons (right); accepts `collapsed` + `onToggle` props |
| `packages/ui/package.json` & `apps/back-office/package.json` | Added `@hugeicons/core-free-icons@^4.1.1` dependency |

**Decisions made:**

- App name: **NubSure** (replaces CIAGB everywhere in frontend)
- Logo: PNG asset at `/public/logo.png` (28×28px in sidebar)
- Sidebar collapse trigger: **hamburger button in topbar** (best practice — stays visible when sidebar is collapsed)
- Collapsed state: 64px wide, icon-only with native `title` tooltips
- Collapse animation: `width 220ms cubic-bezier(0.16, 1, 0.3, 1)` CSS transition on `<aside>` in AppShell
- hugeicons API: `HugeiconsIcon` renderer from `@hugeicons/react` + icon data from `@hugeicons/core-free-icons`
- Icon mapping: Dashboard→`DashboardSquare01Icon`, Customers→`UserGroupIcon`, Quotation→`NoteEditIcon`, Policies→`Shield01Icon`, Endorsements→`FileEditIcon`, Claims→`AlertCircleIcon`, Finance→`Money01Icon`, Reinsurance→`RepeatIcon`, Setup→`Setting06Icon`, Audit→`Audit01Icon`
- `tsc --noEmit` passes with zero errors after all changes

**Open questions:** None.

---

### Session 4c — UI Polish, Figma Completion & Dev Tooling

**Files modified:**

| File | Change |
|---|---|
| `packages/ui/src/tokens.css` | Added `NairaFallback` @font-face (unicode-range U+20A6 → local Arial); added Noto Sans to Google Fonts import; `NairaFallback` placed first in `--font-display` and `--font-body` stacks |
| `packages/auth/src/AuthProvider.tsx` | Added `DevAuthProvider` — mock context using same `AuthContext`, provides fake admin user; added `.catch()` to Keycloak init for graceful failure |
| `packages/auth/src/keycloak.ts` | `onLoad: 'login-required'` in prod, `'check-sso'` in dev |
| `packages/auth/src/index.ts` | Exports `DevAuthProvider` |
| `apps/back-office/src/main.tsx` | Uses `DevAuthProvider` when `import.meta.env.DEV` — no Keycloak required for local dev |
| `apps/back-office/tailwind.config.ts` | Changed import from `@cia/ui/tailwind.config` (package export) to `../../packages/ui/tailwind.config` (relative path) — fixes Tailwind PostCSS CJS loader |
| `apps/partner/tailwind.config.ts` | Same relative path fix |
| `packages/ui/package.json` | Added `"./tailwind.config": "./tailwind.config.ts"` to exports (belt-and-suspenders) |
| `apps/back-office/src/app/layout/Sidebar.tsx` | Added `onToggle` prop; hamburger (`Menu01Icon`) moved to sidebar logo row (right side); sidebar group headings 10→11px; collapsed state: logo only + centered hamburger |
| `apps/back-office/src/app/layout/Topbar.tsx` | Removed hamburger toggle (now in sidebar); Topbar is stateless — no props needed |
| `apps/back-office/src/app/layout/AppShell.tsx` | Passes `onToggle` to `Sidebar`; `Topbar` receives no props |
| `CLAUDE.md` | Frontend Architecture section replaced with actual monorepo structure; design system table; layout shell diagram; frontend patterns; VITE_ env vars table added |
| `.claude/skills/cia/SKILL.md` | Frontend Conventions section added (14 conventions) |

**Figma changes (file: `Zaiu2K7NvEJ7Cjj6z1xt2D`):**

| Node | Change |
|---|---|
| Sidebar logo row | Real Nubeero PNG applied via `upload_assets` (not base64 decoding) — imageHash `48e815d859429d722f18ad2e1ce1dcedeab4a8b9` |
| Sidebar logo row | Hamburger (≡) added to right side of logo row; removed from topbar |
| Sidebar nav items | 10 placeholder squares replaced with proper SVG stroke-path vectors for each module |
| Sidebar group labels | Font size 10→11px |
| Topbar | Rebuilt: title + search bar + bell + ? icons; no hamburger |
| Search bar | Height 36→37px |
| Premiums (MTD) stat | ₦ character in `₦84.2M`, `vs ₦71.5M last month`, and activity row set to `Noto Sans Regular` via `setRangeFontName(i, i+1, ...)` |

**Decisions made:**

- Hamburger toggle lives in the **sidebar logo row** (right-aligned), not the topbar. Sidebar manages its own collapse trigger.
- `DevAuthProvider` in `@cia/auth` (not in the app) so `useAuth()` works identically in both real and dev modes — same `AuthContext`.
- Tailwind config shared via **relative path import only** — never via package name, because Tailwind's PostCSS plugin uses CJS `require()` which ignores `package.json` `exports`.
- Naira sign ₦ (U+20A6): fixed at the CSS level via `unicode-range` scoped `@font-face` pointing to local Arial; fixed in Figma via `setRangeFontName` to Noto Sans per-character.
- Figma image uploads use `mcp__claude_ai_Figma__upload_assets` + curl POST (not `figma.createImage()` with base64) — the latter silently fails in API/screenshot contexts.
- React Query DevTools icon (bottom-right in dev) is intentional — dev-only, not part of production UI.

**Open questions:** None.

---

### Session 4d — CI/CD, Vercel Deploy & SESSION COMPLETION GATE Automation

**Files created/modified:**

| File | Change |
|---|---|
| `.claude/settings.json` | Stop hook updated to 8-gate SESSION COMPLETION GATE checklist |
| `.claude/skills/cia/SKILL.md` | SESSION COMPLETION GATE expanded from 6 → 8 gates; frontend + Figma gates added |
| `.github/workflows/ci.yml` | Frontend job enabled: pnpm v9, tsc on both apps, vite build, artifact upload |
| `.github/workflows/vercel-deploy.yml` | New: Vercel preview on PR + production on push to main (cia-frontend/** filter) |
| `cia-frontend/vercel.json` | Created at monorepo root; buildCommand + outputDirectory + SPA rewrite |
| `cia-frontend/.vercel/project.json` | Vercel project link at monorepo root (projectId: prj_d9m8fgnCZlKe0xTYjeRcnSMAQnHm) |
| `cia-frontend/apps/back-office/vercel.json` | Deleted — caused Vercel to only upload 254B instead of full workspace |
| `CLAUDE.md` | Frontend deployment section updated with production URL |

**Decisions made:**

- Vercel MUST be linked from `cia-frontend/` (monorepo root) — linking from `apps/back-office/` causes Vercel to upload only that subdirectory (254B), leaving workspace packages unreachable during install.
- `vercel.json` at `cia-frontend/` root. Build: `pnpm --filter @cia/back-office build`. Output: `apps/back-office/dist`.
- First two deploy attempts failed: OOM SIGKILL (wrong root, cold turbo build) and exit 127 (vite not found at app-level node_modules). Fixed by deploying from monorepo root.
- SESSION COMPLETION GATE enforced via Claude Code `Stop` hook — fires automatically at end of every session.
- `VERCEL_PROJECT_ID` GitHub secret updated to back-office project (was previously cia-docs).

**Production URL:** [back-office-blush-six.vercel.app](https://back-office-blush-six.vercel.app)

**Open questions:** None.

---

### Session 4e — Frontend Build Queue Established

**Decision:** A comprehensive, ordered frontend build queue has been saved in `CLAUDE.md` under the section **"Frontend Build Queue"**. This section is the authoritative tracker for all frontend work and must be kept up to date throughout the build.

**Build queue summary:**

| Phase | Builds | Description |
|---|---|---|
| Phase 1 | 1a–1e | Shared infrastructure (shadcn components, data table, page layout, form infrastructure, API types + hooks) |
| Phase 2 | Builds 2–10 | All 9 back-office modules in build order |
| Phase 3 | P1–P5 | Partner portal (auth, API explorer, webhooks, sandbox, usage dashboard) |
| **Total** | **19 builds** | **0% complete as of 2026-04-24** |

**Build order (Phase 2):**

1. Module 1 — Setup & Administration (35 features) — unlocks all other modules
2. Module 7 — Customer Onboarding (10 features)
3. Module 2 — Quotation (5 features)
4. Module 3 — Policy (23 features)
5. Module 8 — Finance (5 features)
6. Module 4 — Endorsements (10 features)
7. Module 5 — Claims (23 features)
8. Module 6 — Reinsurance (17 features)
9. Module 10 — Audit & Compliance (15 features) — can run parallel with Builds 8–9

**Audit protocol:** At the start of every frontend session, check `CLAUDE.md → Frontend Build Queue` for current status. Update the `[ ]` / `[~]` / `[x]` checkboxes as builds progress. At session end, the SESSION COMPLETION GATE Stop hook will prompt verification.

**Open questions:** None.

---

### Session 5 — Phase 1: Shared Infrastructure Complete

**Build queue progress: 5/19 builds complete (26%)**

**Builds completed this session:**

| Build | Status | Key files |
|---|---|---|
| 1a — shadcn components | `[x]` | `packages/ui/src/components/`: input, label, textarea, select, checkbox, switch, tabs, dialog, sheet, toast, toaster, dropdown-menu, avatar, card, skeleton, tooltip, separator, scroll-area |
| 1b — Data table | `[x]` | `packages/ui/src/components/data-table/`: data-table, column-header, toolbar, pagination, row-actions |
| 1c — Page layout | `[x]` | `packages/ui/src/components/layout/`: page-header, page-section, empty-state, stat-card, breadcrumb |
| 1d — Form infrastructure | `[x]` | `packages/ui/src/components/form.tsx` (Form, FormField, FormItem, FormLabel, FormControl, FormMessage, FormSection, FormRow) |
| 1e — API types + hooks | `[x]` | `packages/api-client/src/modules/`: setup, customer, quotation, policy, claims, finance DTOs; `hooks.ts`: useGet, useList, useCreate, useUpdate, useRemove |

**New packages added:**

| Package | Added to | Purpose |
|---|---|---|
| `@radix-ui/react-checkbox` | `@cia/ui` | Checkbox primitive |
| `@radix-ui/react-switch` | `@cia/ui` | Switch toggle primitive |
| `@radix-ui/react-tabs` | `@cia/ui` | Tabs primitive |
| `@radix-ui/react-popover` | `@cia/ui` | Popover (future combobox) |
| `lucide-react` | `@cia/ui` | Icon chevrons inside shadcn components |
| `@tanstack/react-table` | `@cia/ui` | Headless table engine |
| `react-hook-form` | `@cia/ui` + `@cia/back-office` | Form state management |
| `zod` | `@cia/ui` + `@cia/back-office` | Schema validation |
| `@hookform/resolvers` | `@cia/ui` + `@cia/back-office` | Zod ↔ RHF bridge |

**Decisions made:**
- `lucide-react` used for shadcn component internals (chevrons, check marks, X icons). hugeicons used for application-level navigation and module icons. No conflict — different use-cases.
- `react-hook-form` and `zod` added to `@cia/ui` (not just the app) so `Form` components live in the shared package.
- TanStack Table is headless — DataTable owns all rendering, zero UI opinions from the library.
- Form pattern: shadcn `Form` → `FormField` → `FormItem` → `FormLabel` + `FormControl` + `FormMessage`. Zod schema passed to `useForm({ resolver: zodResolver(schema) })` in the consuming component.
- API DTOs added for 6 modules (Setup, Customer, Quotation, Policy, Claims, Finance). Endorsement, Reinsurance, Audit DTOs to be added when those modules are built.

**TypeScript: ✅ 0 errors on `@cia/back-office` after all changes.**

**Open questions:** None.

---

### Session 5b — Figma Gate 5 catchup: Setup module screens

Two frames pushed to Figma file `Zaiu2K7NvEJ7Cjj6z1xt2D`, new page "Setup" (id: `54:2`):

| Frame | Node ID | Represents |
|---|---|---|
| `Setup / Users` | `55:2` | Archetypal list view — AppShell + Setup secondary nav, DataTable with status badges |
| `Setup / Company Settings` | `58:2` | Archetypal form view — Card sections, form fields, Save button |

Gate 5 (Figma Sync) was missed in Session 5 and corrected here before proceeding to Build 3.

**Open questions:** None.

---

### Session 5c — ProductSheet: inline Class of Business creation

**File modified:**

| File | Change |
|---|---|
| `apps/back-office/src/modules/setup/pages/products/ProductSheet.tsx` | Full rewrite — see decisions below |
| `apps/back-office/src/modules/customers/index.tsx` | Module routing scaffold (stub pages) |
| `apps/back-office/src/modules/customers/pages/*.tsx` | Stub placeholder pages for Build 3 |

**Decisions made:**

- Classes of Business dropdown now has a `+ New Class of Business` sentinel item (`value="__create_new__"`) at the bottom, separated by a `SelectSeparator`.
- Sentinel is intercepted in `onValueChange` before `field.onChange` — the field value is never set to the sentinel string.
- Inline creation opens a **Dialog** (centred modal), not a Sheet, to avoid z-index issues from nesting a Sheet inside an already-open Sheet.
- On save: new class appended to local state (`useState`) and immediately auto-selected via `form.setValue`. When backend is wired, `onCreateClass` will POST to `/api/v1/setup/classes` and use the returned ID.
- Seed list expanded from 4 hardcoded entries to 14 covering the full Nigerian market range: Motor Private/Commercial, Fire & Burglary, Marine Cargo/Hull, Goods in Transit, Engineering/CAR, Professional Indemnity, Public Liability, Employer's Liability, Personal Accident, Travel Insurance, Group Life, Bonds.
- The same inline-create pattern (sentinel value → Dialog → append to state → auto-select) should be applied to other master-data selects (Brokers, Reinsurers, Surveyors, etc.) as those modules are built.
- `tsc --noEmit` passes with 0 errors.

**GitHub:** commit `bd39256` on `main`
**Vercel:** Production deployment `back-office-bkycm4xxs` — Status: Ready ✅

**Open questions:** None.

---

### Session 6 — Build 3: Customer Onboarding module complete

**Build queue progress: 7/19 builds complete (37%)**

**Files created:**

| File | Description |
|---|---|
| `apps/back-office/src/modules/customers/index.tsx` | Module routing: list, detail (/:id), reports |
| `apps/back-office/src/modules/customers/pages/CustomersListPage.tsx` | DataTable with Individual/Corporate type badge, KYC badge (verified/pending/failed), Status badge, Broker column, "New Customer ▾" dropdown |
| `apps/back-office/src/modules/customers/pages/individual/IndividualOnboardingSheet.tsx` | Sheet with first/last name, email, phone, DOB, ID type (NIN/Voter/DL/Passport), ID number, address, occupation, broker-enabled toggle |
| `apps/back-office/src/modules/customers/pages/corporate/CorporateOnboardingSheet.tsx` | Sheet with company name, RC number, email, phone, address, useFieldArray directors table, broker-enabled toggle |
| `apps/back-office/src/modules/customers/pages/detail/CustomerDetailPage.tsx` | Tabs: Summary (contact details), KYC (ID + re-submit button), Policies (inline table), Claims (inline table); breadcrumb + action buttons |
| `apps/back-office/src/modules/customers/pages/reports/LossRatioReportPage.tsx` | StatCards + table by class with colour-coded rating badge (Good/Moderate/High) |
| `apps/back-office/src/modules/customers/pages/reports/ActiveCustomersReportPage.tsx` | StatCards + table by onboarding channel (individual vs corporate count + share %) |

**Figma:** Customers page created (id: `62:2`)
- `Customers / List` (node `62:3`): DataTable with all 5 rows, KYC badges, type badges, broker column
- `Customers / Detail` (node `65:2`): Summary tab with Contact Details card, tabs row (Summary/KYC/Policies 2/Claims 1)

**Decisions made:**
- Customers entry point uses a "New Customer ▾" dropdown splitting individual vs corporate onboarding — same pattern as "New Quote ▾" in quotation.
- `updatedAt` field added to all CustomerDto mock objects to satisfy the DTO type.
- Removed `Separator` unused import from CustomerDetailPage — TS strict mode catches unused imports.

**GitHub:** commit `dbd05db` | **Vercel:** Ready ✅

**Open questions:** None.

---

### Session 7 — Build 4: Quotation module complete

**Build queue progress: 8/19 builds complete (42%)**

**Files created:**

| File | Description |
|---|---|
| `apps/back-office/src/modules/quotation/index.tsx` | Module routing: list, detail (/:id), bulk-upload |
| `apps/back-office/src/modules/quotation/pages/QuotationListPage.tsx` | DataTable with quote number (teal link), customer, product, ₦ sum insured + net premium, 5 status variants (approved/submitted/draft/converted/rejected), version badge; Bulk Upload + New Quote ▾ dropdown |
| `apps/back-office/src/modules/quotation/pages/create/SingleRiskQuoteSheet.tsx` | Customer + product selects (product auto-fills rate), policy period, sum insured, rate, discount, live premium preview block (gross → discount → net) visible when SI+rate filled |
| `apps/back-office/src/modules/quotation/pages/create/MultiRiskQuoteSheet.tsx` | useFieldArray risk items each with description/SI/rate, rolling total SI + total premium summary |
| `apps/back-office/src/modules/quotation/pages/detail/QuoteDetailPage.tsx` | 2-column cards (quote details + premium summary), version history timeline with v-dot indicators, status-conditional action buttons (Submit / Convert / Edit) |
| `apps/back-office/src/modules/quotation/pages/bulk/BulkUploadPage.tsx` | Drag-and-drop CSV zone, validation results with error row detail, CSV template download section |

**Figma:** Quotation page created (id: `66:2`)
- `Quotation / List` (node `66:3`): all 5 status badge variants, ₦ premium columns, version numbers

**Decisions made:**
- `MockQuote` type defined explicitly (not `Partial<QuoteDto>`) to avoid TypeScript narrowing issues where `q.status === 'DRAFT'` was always false due to literal type.
- SingleRiskQuoteSheet auto-fills the rate field when a product is selected from the dropdown, using `form.setValue('rate', product.defaultRate)`.
- QuoteDetailPage action buttons are status-conditional: `canSubmit = DRAFT`, `canConvert = APPROVED`, `canEdit = not CONVERTED and not APPROVED`.
- Bulk upload uses a controlled `UploadState` ('idle' | 'validating' | 'done') — simulates async validation with setTimeout.

**GitHub:** commit `0ff5f66` | **Vercel:** Ready (latest production: `back-office-9dsx0cqzx`) ✅

**Open questions:** None.
---

### Session 8 — Build 5: Policy module complete

**Build queue progress: 9/19 builds complete (47%)**

**Files created:**

| File | Description |
|---|---|
| `apps/back-office/src/modules/policy/index.tsx` | Module routing: list + detail (/:id) |
| `apps/back-office/src/modules/policy/pages/PolicyListPage.tsx` | DataTable with policy number (teal), customer, product/class, ₦ SI + net premium, 6 status variants, NAICOM UID column (UID or PENDING badge), expiry; "New Policy ▾" dropdown with status-conditional row actions |
| `apps/back-office/src/modules/policy/pages/create/CreatePolicySheet.tsx` | Two-tab sheet: "From Approved Quote" (quote select, business type, payment terms) and "Direct Entry" (customer, product, dates, SI, rate, discount, live premium preview) |
| `apps/back-office/src/modules/policy/pages/detail/PolicyDetailPage.tsx` | 5-tab layout: Details (2-column cards), Document (clause bank, template, send/acknowledge), Financial (debit note, Post Receipt), Survey (threshold-conditional, surveyor, override), NAICOM (UID status, upload log, manual trigger) |

**Figma:** Policies page created (id: `72:2`)
- `Policies / List` (node `72:3`): all 5 rows, status badges, NAICOM UID column (2 PENDING, 3 with UIDs)

**Decisions made:**
- NAICOM UID column shows the actual UID string when present, or an amber "PENDING" badge when not yet uploaded. This makes the regulatory status immediately scannable without navigating to the detail page.
- CreatePolicySheet uses a Tabs component to host both creation flows in one sheet, avoiding two separate Sheet components.
- PolicyDetailPage `MockPolicy` type defined explicitly (not `Partial<PolicyDto>`) to avoid TypeScript literal type narrowing issues on status comparisons — same pattern established in QuoteDetailPage.
- Survey tab is conditionally rendered: when `surveyRequired = false`, it shows "no survey needed" with option to request one. When `surveyRequired = true`, shows the full workflow.
- `clauses` array on the mock policy represents the clause bank — the basis for the Document tab's editable clause list.

**GitHub:** commit `fa4078f` | **Vercel:** auto-deploy triggered via GitHub Actions

**Open questions:** None.
---

### Session 9 — Build 6: Finance module complete

**Build queue progress: 10/19 builds complete (53%)**

**Files created:**

| File | Description |
|---|---|
| `apps/back-office/src/modules/finance/index.tsx` | Module routing — single FinancePage route |
| `apps/back-office/src/modules/finance/pages/FinancePage.tsx` | Two-tab page (Receivables / Payables) with PageHeader |
| `apps/back-office/src/modules/finance/pages/receivables/ReceivablesTab.tsx` | Debit Notes DataTable (outstanding/settled badges, Bulk Receipt button) + Receipts DataTable (approve/reject/reverse actions) |
| `apps/back-office/src/modules/finance/pages/receivables/PostReceiptSheet.tsx` | Single + bulk receipt posting; debit note summary with per-note breakdown, payment date/method/reference/bank/amount/notes |
| `apps/back-office/src/modules/finance/pages/payables/PayablesTab.tsx` | Credit Notes DataTable (source badges: Claim DV/Endorsement/Commission/RI FAC) + Payments DataTable (Approve/Reject/Reverse) |

**Figma:** Finance page created (id: `75:2`)
- `Finance / Receivables` (node `75:3`): debit notes table with outstanding/settled status badges, Bulk Receipt button, Receivables/Payables tab bar

**Decisions made:**
- Finance is split into Receivables (debit notes → receipts) and Payables (credit notes → payments) tabs — mirrors the accounting conceptual split that finance officers use.
- PostReceiptSheet accepts `bulk: boolean` prop and `debitNoteIds: string[]` — same component handles single and bulk posting, showing a summary/breakdown when bulk mode is active.
- Credit notes have source type badges: CLAIM → "Claim DV", ENDORSEMENT → "Endorsement", COMMISSION → "Commission", REINSURANCE → "RI FAC" — finance officers need to know the originating module at a glance.
- PayablesTab `useState` for selectedCn was removed since the Process Payment action is currently a no-op placeholder — will be wired when a ProcessPaymentSheet is built.

**GitHub:** commit `f12aa22` | **Vercel:** auto-deploy triggered via GitHub Actions

**Open questions:** None.
---

### Session 10 — Build 7: Endorsements module complete

**Build queue progress: 11/19 builds complete (58%)**

**Files created:**

| File | Description |
|---|---|
| `apps/back-office/src/modules/endorsements/index.tsx` | Module routing: list, detail (/:id), Debit Note Analysis report |
| `apps/back-office/src/modules/endorsements/pages/EndorsementsListPage.tsx` | DataTable with type badge (blue, all 10 types), pro-rata (red when negative), status variants, Debit Note Analysis + New Endorsement buttons |
| `apps/back-office/src/modules/endorsements/pages/create/CreateEndorsementSheet.tsx` | Type-driven form: type selection reshapes fields — period dates / new SI with indicative pro-rata / item description / info banners for cancellation and reversal |
| `apps/back-office/src/modules/endorsements/pages/detail/EndorsementDetailPage.tsx` | 2-column cards (details + premium impact), approval timeline with step indicators, debit/credit note generation note |
| `apps/back-office/src/modules/endorsements/pages/reports/DebitNoteAnalysisPage.tsx` | By period + by type tables; StatCards; Export CSV button |
| `packages/api-client/src/modules/endorsement.ts` | `EndorsementDto`, `EndorsementStatus`, `EndorsementType` (10 values) |

**Figma:** Endorsements page created (id: `81:2`)
- `Endorsements / List` (node `81:3`): blue type badges, red negative pro-rata values, all 4 status variants

**Decisions made:**
- `EndorsementDto` was missing from `@cia/api-client` — added `endorsement.ts` and exported it from `modules/index.ts`.
- CreateEndorsementSheet uses conditional rendering (not tabs) to reshape fields based on type: `showPeriodFields`, `showSIFields`, `showItemFields`, `showCancelFields`, `showReversalNote` derived from `endorsementType` watch.
- Pro-rata premium for Decrease SI shown as a credit (red, negative) in the premium impact card on EndorsementDetailPage.
- `calcProRata()` function uses `(annualPremium / 365) × daysAffected` — indicative only; final calculation on the server.
- Figma connection timed out on first attempt (script too long); fixed by reducing verbosity and loading all fonts upfront.

**GitHub:** commit `03d0234` | **Vercel:** auto-deploy triggered via GitHub Actions

**Open questions:** None.
---

### Session 11 — Build 8: Claims module complete

**Build queue progress: 12/19 builds complete (63%)**

**Files created:**

| File | Description |
|---|---|
| `apps/back-office/src/modules/claims/index.tsx` | Module routing: list, detail (/:id), bulk |
| `apps/back-office/src/modules/claims/pages/ClaimsListPage.tsx` | StatCard row (Open/Reserve/Paid YTD) + DataTable with 6 status variants, reserve + paid columns, status-conditional row actions |
| `apps/back-office/src/modules/claims/pages/register/RegisterClaimSheet.tsx` | Full claim registration: policy, dates, nature/cause selects, location, description, estimated loss, contact |
| `apps/back-office/src/modules/claims/pages/detail/ClaimDetailPage.tsx` | 5-tab layout: Summary (incident + financial cards), Processing (reserves/expenses/comments), Documents (checklist + upload), Inspection (assign/approve/override), DV (Own Damage/Third Party/Ex-gratia type selection, amount, generate, execute) |
| `apps/back-office/src/modules/claims/pages/bulk/BulkClaimPage.tsx` | CSV drag-and-drop, validation results, template download |

**Figma:** Claims page created (id: `84:2`)
- `Claims / List` (node `84:3`): 3 StatCards, DataTable with all status variants, paid amount in teal for settled claim

**Decisions made:**
- StatCard row on ClaimsListPage gives financial overview without navigating — underwriters and claims officers need reserve totals at a glance.
- Missing docs count shown in two places: page header badge AND Processing tab trigger — ensuring the missing document state is impossible to miss.
- DV generation uses local state (`dvGenerated`, `dvType`, `dvAmount`) to simulate the generate → execute flow. When backend is wired, Generate DV posts to `/api/v1/claims/:id/dv` and Execute DV updates the DV record to EXECUTED.
- `canGenDv` variable removed (unused after status check was inlined) — TypeScript strict mode catches this.

**GitHub:** commit `8b5633b` | **Vercel:** auto-deploy triggered via GitHub Actions

**Open questions:** None.
---

### Session 12 — Build 9: Reinsurance module complete

**Build queue progress: 13/19 builds complete (68%)**

**Files created:**

| File | Description |
|---|---|
| `apps/back-office/src/modules/reinsurance/index.tsx` | Module routing — single ReinsurancePage |
| `apps/back-office/src/modules/reinsurance/pages/ReinsurancePage.tsx` | 4-tab layout: Treaties, Allocations, Facultative, Returns & Reports |
| `apps/back-office/src/modules/reinsurance/pages/treaties/TreatiesTab.tsx` | Treaty DataTable (colour-coded Surplus/QS/XOL chips, retention, capacity, reinsurer shares) + treaty summary cards + Batch Reallocation button |
| `apps/back-office/src/modules/reinsurance/pages/treaties/TreatySheet.tsx` | Type-driven form: limits hidden for QS; useFieldArray reinsurers with running total; Save disabled until total = 100% |
| `apps/back-office/src/modules/reinsurance/pages/allocations/AllocationsTab.tsx` | Allocations DataTable (4 status variants); conditional alert banners for pending confirmation and excess capacity |
| `apps/back-office/src/modules/reinsurance/pages/fac/FACTab.tsx` | Outward FAC sub-tab (offer status, credit note action) + Inward FAC sub-tab (ceding company, our share, renew/extend) |
| `apps/back-office/src/modules/reinsurance/pages/reports/ReportsTab.tsx` | Bordereaux (premium + claims tables), Recoveries, and Returns (quarterly list) sub-tabs |

**Figma:** Reinsurance page created (id: `87:2`)
- `Reinsurance / Treaties` (node `87:3`): treaty list with Surplus/QS/XOL type chips, 4-tab header

**Decisions made:**
- TreatySheet Save button is disabled when reinsurer shares don't sum to 100% — enforced in the UI before the API call so users can't accidentally create an underweight or overweight treaty.
- AllocationsTab shows alert banners conditionally: "pending confirmation" banner only when `AUTO_ALLOCATED` count > 0; "excess capacity" banner only when `EXCESS_CAPACITY` count > 0. No noise when everything is clean.
- FACTab uses Tabs within the main Reinsurance Tabs (nested tabs) — this is intentional since Outward and Inward FAC are distinct enough to warrant separation.
- Figma screenshot API returned a remote URL instead of inline image this session — frame was created successfully (confirmed by non-null pageId/shellId).

**GitHub:** commit `c988d30` | **Vercel:** auto-deploy triggered via GitHub Actions

**Open questions:** None.
---

### Session 12b — FAC Sheets: CreateFACOfferSheet + AddInwardFACSheet

**Files created/modified:**

| File | Change |
|---|---|
| `apps/back-office/src/modules/reinsurance/pages/fac/CreateFACOfferSheet.tsx` | New — Outward FAC form: excess policy select, SI split (total/retention/FAC with auto-compute), reinsurer, premium rate, commission, offer validity, cover period, live net premium preview |
| `apps/back-office/src/modules/reinsurance/pages/fac/AddInwardFACSheet.tsx` | New — Inward FAC form: ceding company, their reference, class, risk description, our share %, premium rate, ceding commission, live financial position preview (our SI / gross premium / commission / net receivable), cover period, contact |
| `apps/back-office/src/modules/reinsurance/pages/fac/FACTab.tsx` | Modified — wired both sheets via useState; "Create FAC Offer" and "Add Inward FAC" buttons now open the respective sheets |

**Decisions made:**
- `CreateFACOfferSheet` auto-computes `facSumInsured = totalSumInsured - retention` when the retention field changes, so the user doesn't have to manually enter the FAC SI.
- `AddInwardFACSheet` shows a financial position card (our SI, gross premium, ceding commission deduction, net receivable) whenever totalSumInsured + ourShare + premiumRate are all filled — same live preview pattern as SingleRiskQuoteSheet.
- Ceding companies in AddInwardFACSheet will eventually pull from `/api/v1/setup/organisations/reinsurers` (where inward FAC ceding companies are registered).
- FAC sheets use `<> ... </>` fragment wrapper because the Tabs component plus the two Sheet portals must share a single JSX return root.

**GitHub:** commit `0083c7f` | **Vercel:** auto-deploy triggered

**Open questions:** None.
---

### Session 12c — CreateFACOfferSheet: Direct vs Broker placement toggle

**File modified:** `apps/back-office/src/modules/reinsurance/pages/fac/CreateFACOfferSheet.tsx`

**Change:** Added `placedThrough: 'DIRECT' | 'BROKER'` toggle (card-style selector).
- **DIRECT** → Reinsurer select (9 companies: Munich Re, Swiss Re, African Re, Lloyd's syndicates, ZEP-RE, GIC Re, Trans-Atlantic Re, Continental Re)
- **BROKER** → FAC Broker select (7 entries: Marsh Re, Aon Re, Willis TW, SCIB Nigeria, Gras Savoye Willis, Brokerage International, Anchor) + optional "Target Markets" text field
- Commission label adapts: "Reinsurer Commission %" vs "Brokerage %"
- Submit button adapts: "Send FAC Offer" vs "Send to Broker"
- `counterpartyId` and `brokerMarkets` are cleared when placement type is switched

**Decision:** The broker-arranged FAC path needs a "Target Markets" field because the broker approaches multiple reinsurance markets on the cedant's behalf — the underwriter can specify preferred markets (e.g. "Lloyd's, Munich Re") or leave blank to let the broker decide. This field maps to a `brokerInstructions` field on the backend FAC record.

**GitHub:** commit `cb5d9db` | **Vercel:** auto-deploy triggered

**Open questions:** None.

---

## 2026-04-24 (continued)

### Session 13 — AllocationsTab: Fix 4 broken interaction buttons

**Files modified/created:**

| File | Change |
|---|---|
| `apps/back-office/src/modules/reinsurance/pages/allocations/AllocationsTab.tsx` | Modified — wired all 4 interactions via local useState; policy numbers now open PolicyAllocationSheet; "Confirm All" opens Dialog with list of pending allocations; "Create FAC" banner button and row action open CreateFACOfferSheet; "Batch Reallocation" opens BatchReallocationSheet |
| `apps/back-office/src/modules/reinsurance/pages/allocations/PolicyAllocationSheet.tsx` | New — right-side Sheet showing policy detail card + RI allocation with visual retention/ceding split bar; Confirm button (AUTO_ALLOCATED), Approve + Decline buttons (CONFIRMED), FAC info banner (EXCESS_CAPACITY) |
| `apps/back-office/src/modules/reinsurance/pages/allocations/BatchReallocationSheet.tsx` | New — multi-select checkbox list of reallocatable policies (non-APPROVED), "Select all (N)" shortcut, new treaty select, effective date, reason field; submit button disabled until at least one policy selected, label shows count |

**Decisions made:**
- Policy number cell in the table is a clickable `<button>` that opens PolicyAllocationSheet — consistent with the "click row to drill down" pattern used in Claims and Policy modules.
- `pendingConfirmation` and `excessCapacity` are now arrays (not counts) so the "Confirm All" dialog can render the full list of affected policies inline.
- PolicyAllocationSheet gets `allocation: Allocation | null` — returns null when nothing selected; the Sheet `open` prop derives from `viewAllocation !== null`, keeping the guard clean.
- BatchReallocationSheet filters `allocations.filter(a => a.status !== 'APPROVED')` — APPROVED allocations cannot be reallocated without a reversal first.
- Added `treatyYear: number` to PolicyAllocationSheet's `Allocation` interface (was missing, caused TS2551 on line 104).

**GitHub:** pending commit | **Vercel:** auto-deploy will trigger after push

**Open questions:** None.

---

### Session 14 — Reinsurance: wire Treaties + FAC tab interactions

**Files modified/created:**

| File | Change |
|---|---|
| `apps/back-office/src/modules/reinsurance/pages/treaties/TreatiesTab.tsx` | Modified — "Batch reallocation" row action now opens `BatchReallocationSheet` scoped to the selected treaty's allocations; "Deactivate/Activate" row action now opens an inline confirmation Dialog with context-appropriate wording and button variant |
| `apps/back-office/src/modules/reinsurance/pages/fac/FACTab.tsx` | Modified — wired all 5 previously silent row actions: Generate Credit Note → `FACCreditNoteDialog`; Download Offer Slip → `FACOfferSlipDialog`; Cancel FAC → inline confirm Dialog; Renew → `InwardFACActionSheet` mode=RENEW; Extend Period → `InwardFACActionSheet` mode=EXTEND; Cancel (inward) → inline confirm Dialog |
| `apps/back-office/src/modules/reinsurance/pages/fac/FACCreditNoteDialog.tsx` | New — Dialog showing full credit note breakdown: FAC reference, policy, reinsurer, gross premium, commission (5% placeholder), net premium due; Submit to Finance + Download PDF actions |
| `apps/back-office/src/modules/reinsurance/pages/fac/FACOfferSlipDialog.tsx` | New — Dialog showing offer slip summary: policy, reinsurer, SI, premium rate, gross premium, offer date, status badge; Download PDF action |
| `apps/back-office/src/modules/reinsurance/pages/fac/InwardFACActionSheet.tsx` | New — Single sheet handling both RENEW and EXTEND modes via `mode` prop. Shows current cover summary (read-only), then amendable fields: new period dates (both for RENEW, end date only for EXTEND), our share %, premium rate with live financial preview. `useEffect` resets form defaults whenever `open+fac+mode` changes. |

**Decisions made:**
- Single `InwardFACActionSheet` with `mode: 'RENEW' | 'EXTEND'` prop avoids duplicating near-identical forms. Title, description, and visible date fields change per mode.
- `useEffect([open, fac?.id, mode])` pattern resets RHF form when a different record is selected; `impliedRate()` back-calculates the premium rate from the existing ourPremium/ourShare so the form is pre-filled with meaningful values.
- TreatiesTab stores `MOCK_TREATY_ALLOCATIONS` keyed by treaty ID so BatchReallocationSheet shows only the allocations belonging to the selected treaty (not all allocations).
- Deactivate confirmation Dialog uses `variant="destructive"` for the confirm button when deactivating ACTIVE treaties, and `variant="default"` for reactivating — matching the severity of the action.
- Cancel FAC and Cancel Inward FAC are also handled with inline confirmation Dialogs (not a separate file) since they need no form input.

**GitHub:** pending push | **Vercel:** auto-deploy will trigger after push

**Open questions:** None.

---

### Session 15 — Finance: wire Receivables + Payables tab interactions

**Files modified/created:**

| File | Change |
|---|---|
| `pages/receivables/DebitNoteDetailDialog.tsx` | New — Dialog showing debit note + linked policy details (product, class, cover period). "Post Receipt" button hands off to PostReceiptSheet; "Close" available for SETTLED/read-only notes. Debit note number in table is also a clickable link that opens this dialog. |
| `pages/receivables/ReceivablesTab.tsx` | Modified — "View policy" and "Post Receipt" row actions now both open DebitNoteDetailDialog (policy context before action); Debit note number cell is clickable; "Reverse" on approved receipts opens ReverseTransactionDialog with full receipt details + cannot-undo warning |
| `pages/payables/CreditNoteDetailDialog.tsx` | New — Dialog showing credit note + source details (source type badge, reference, description, policy, beneficiary). "Process Payment" button hands off to ProcessPaymentSheet. Both "Process Payment" and "View source" row actions open this dialog. Credit note number is also a clickable link. |
| `pages/payables/ProcessPaymentSheet.tsx` | New — Sheet form: amount (pre-filled from credit note), payment method (Bank Transfer/Cheque/Cash/Online), bank name, reference/transaction ID, notes. Confirms payment on submit. |
| `pages/payables/PayablesTab.tsx` | Modified — "Process Payment" and "View source" both open CreditNoteDetailDialog; "Reverse" on approved payments opens ReverseTransactionDialog; credit note number cell clickable |
| `pages/ReverseTransactionDialog.tsx` | New — Shared dialog for reversing both receipts and payments. Shows transaction details + "cannot be undone" warning banner. Confirm Reversal button (destructive). Accepts a `ReverseTarget` union covering both receipt and payment shapes. |

**Decisions made:**
- Both "View policy" and "Post Receipt" route through DebitNoteDetailDialog — the finance officer always sees context before committing. Dialog closes then PostReceiptSheet opens (no nested modals).
- Same pattern in Payables: "View source" and "Process Payment" both open CreditNoteDetailDialog, which shows the source origin before processing.
- ReverseTransactionDialog is shared at `pages/` level (not inside a tab subfolder) since it's used by both Receivables and Payables. Takes a `ReverseTarget` interface with `type: 'RECEIPT' | 'PAYMENT'` to adapt labels.
- `z.enum([...])` params changed: dropped `required_error` which is not valid in Zod 4 — enum validation already produces a clear "invalid enum value" error.

**GitHub:** pending commit | **Vercel:** auto-deploy will trigger after push

**Open questions:** None.

---

### Session 16 — Claims: wire all silent interactions

**Files modified/created:**

| File | Change |
|---|---|
| `pages/bulk/BulkClaimPage.tsx` | Modified — "browse" button now triggers a hidden `<input type="file" accept=".csv">` via ref; drag-drop also correctly calls processFile(); was previously skipping straight to results state |
| `pages/ClaimsListPage.tsx` | Modified — "Submit for approval" row action opens `SubmitClaimDialog`; "Cancel claim" row action opens `CancelClaimDialog` |
| `pages/detail/ClaimDetailPage.tsx` | Modified — "Submit for Approval" header button → `SubmitClaimDialog`; "Cancel Claim" → `CancelClaimDialog`; "Add Reserve" → `AddReserveDialog`; "Add Expense" → `AddExpenseDialog`; "Add Comment" → `AddCommentDialog`; Documents "Upload" buttons → `UploadDocumentDialog` with correct doc name; "Decline Report" button added to Inspection tab → inline confirmation Dialog; Processing tab shows advisory banner (editable/locked) based on claim status |
| `pages/detail/SubmitClaimDialog.tsx` | New — Full claim summary (policy, customer, incident date, reserve, description); amber "cannot be undone" warning banner; Submit + Cancel buttons; used from both list and detail pages |
| `pages/detail/CancelClaimDialog.tsx` | New — Claim summary + free-text reason textarea (min 5 chars to enable submit); red "cannot be undone" warning banner; "Cancel Claim" destructive button |
| `pages/detail/AddReserveDialog.tsx` | New — RHF form: reserve category (select from 9 types), amount, notes; advisory text that reserves are locked after submission |
| `pages/detail/AddExpenseDialog.tsx` | New — RHF form: expense type (select from 8 types), amount, invoice reference; advisory text about lock |
| `pages/detail/AddCommentDialog.tsx` | New — Textarea dialog; character counter; disabled until ≥3 chars |
| `pages/detail/UploadDocumentDialog.tsx` | New — Real file picker: hidden `<input type="file">` + drag-drop zone; shows selected filename + size + remove option; accepts PDF/JPG/PNG/Word; Upload button disabled until file selected |

**Decisions made:**
- `canEdit = c.status === 'PROCESSING'` gates Add Reserve/Expense buttons and the advisory banner. Comments have no gate (the Add Comment button stays visible always — auditors can still comment after approval).
- Processing tab shows two different banners: amber "editable" advisory when still PROCESSING, grey "locked" notice once submitted — matching the insurance system pattern where the four-eyes principle freezes financial records on submission.
- "Decline Report" on inspection tab was missing entirely — added with an inline Dialog (not a separate file, no form input needed) that carries the "locked after submission" warning.
- BulkClaimPage file input and UploadDocumentDialog are both noted as stubs — the backend upload endpoint (`POST /api/v1/claims/{id}/documents`) is a TODO. The file is selected client-side; actual upload will be wired when the backend is ready.

**GitHub:** pending commit | **Vercel:** auto-deploy will trigger after push

**Open questions:** None.

---

### Session 17 — Claims Inspection tab: Approve, Override, Download dialogs

**File modified:** `apps/back-office/src/modules/claims/pages/detail/ClaimDetailPage.tsx`

**Changes:**

| Button | Before | After |
|---|---|---|
| Approve Inspection Report | Silent (no action) | Opens confirmation Dialog showing inspection details (claim, surveyor, assigned date, status) + amber "cannot be modified after submission" warning |
| Override Requirement | Silent (no action) | Opens Dialog with mandatory reason textarea (min 10 chars to enable confirm) + amber "locked after submission" warning; reason recorded in audit trail |
| Download Report | Silent (no action) | Opens Dialog listing all 3 inspection documents (Inspection Report PDF, Repair Cost Estimate PDF, Photo Evidence ZIP) each with individual Download button + "Download All" footer button |

**Decisions made:**
- Approve and Override dialogs both carry the amber "Cannot be modified after submission" banner — same pattern as the Decline dialog added in Session 16 — to reinforce the four-eyes principle consistently across all inspection decisions.
- Override requires a reason ≥ 10 characters (longer than cancel claim's 5-char minimum) because an override waives a compliance control and must be auditable.
- Download Report dialog shows all files as a list with PDF/ZIP type badges, file size, and date — this is a stub; actual file list will come from `GET /api/v1/claims/{id}/inspection/documents`. Individual Download + Download All buttons both have TODO backend calls.
- All three dialogs are inline in ClaimDetailPage (no separate files) — they're specific to the inspection tab, have no reuse elsewhere, and two of them (Approve, Download) have no form state that warrants a separate component.

**GitHub:** pending commit | **Vercel:** auto-deploy will trigger after push

**Open questions:** None.

---

### Session 18 — Fix Download Report dialog alignment

**File modified:** `apps/back-office/src/modules/claims/pages/detail/ClaimDetailPage.tsx`

**Change:** Fixed misaligned layout in the Download Inspection Reports dialog.

**Root cause:** The left text group had `min-w-0` but no `flex-1`, so it couldn't consume available horizontal space. Combined with `justify-between` on the parent, the Download button had no reliable anchor point, causing it to stack or misalign when filenames are long on the `sm:max-w-md` (448px) dialog.

**Fix:**
- Dialog width: `sm:max-w-md` → `sm:max-w-lg` (512px, more breathing room)
- Row layout: removed `justify-between`; switched to a flat `flex items-center gap-3 px-4 py-3` row
- Text area: `min-w-0` → `flex-1 min-w-0` — allows the text to consume remaining space, enabling reliable truncation
- Button: removed `ml-3`; spacing handled by parent `gap-3`; kept `shrink-0`
- Container: replaced separate bordered cards (`space-y-2` + `border`) with a single `rounded-lg border overflow-hidden divide-y divide-border` block — cleaner visual hierarchy and eliminates the border-gap-border stacking

**Confirmed intact:** BulkClaimPage validation results (validating spinner → done card with valid/error badge counts, error detail row, Re-upload + Register 8 Claims buttons) were not deleted in Session 16 and remain fully functional as stub state for backend wiring.

**GitHub:** pending commit | **Vercel:** auto-deploy will trigger after push

**Open questions:** None.

---

### Session 19 — Performance: fix 8s/5s load times

**Root cause (diagnosed):** Five compounding issues caused slow loads:

1. **`@import` in CSS** (biggest): `tokens.css` had `@import url('https://fonts.googleapis.com/...')`. CSS `@import` is render-blocking and sequential: browser parsed HTML → fetched CSS → then fetched the Google Fonts CSS → then fetched the actual woff2 files. 3-hop chain, all blocking render.
2. **No Vercel cache headers**: Every revisit re-downloaded all JS/CSS. `Cache-Control` was absent, so Vercel defaulted to short caches.
3. **Single monolithic vendor bundle**: All node_modules in one chunk. Any dependency update busted the entire vendor cache. Large parse cost per visit.
4. **ReactQueryDevtools in production bundle**: ~60-80KB of devtools code shipped to prod users.
5. **No browser preconnect**: Browser didn't pre-warm DNS + TLS to Google Fonts origins.

**Fixes applied:**

| Fix | File | Expected gain |
|---|---|---|
| Remove `@import`, load Google Fonts via `<link rel="stylesheet">` in HTML + `preconnect` hints | `tokens.css`, `index.html` | Fonts load in parallel with main CSS (not after); eliminates 3-hop blocking chain; ~3-4s first-paint improvement |
| `Cache-Control: public, max-age=31536000, immutable` on `/assets/**` and `/fonts/**` | `vercel.json` | Repeat visits serve all JS/CSS from disk cache; ~4-5s improvement on return visits |
| `Cache-Control: max-age=0, must-revalidate` on `/index.html` | `vercel.json` | Ensures index.html always revalidates (new deploy = new asset hashes) |
| Manual chunk splitting: vendor-react, vendor-router, vendor-tanstack, vendor-radix, vendor-icons, vendor-forms, vendor-misc | `vite.config.ts` | React/Radix/icons each cache independently; partial deploys don't bust unrelated chunks |
| Tree-shake ReactQueryDevtools from prod bundle via lazy import + compile-time `import.meta.env.DEV` guard | `main.tsx` | Removes ~60-80KB from prod bundle; devtools still work in dev |
| Fix tsconfig.node.json: add `"types": ["node"]` and `"DOM"` to lib | `tsconfig.node.json`, `package.json` | Required for `path`/`__dirname` in vite.config.ts manualChunks; was a pre-existing bug exposed by the chunk config |

**Note on font strategy:** The agent initially wrote self-hosted `@font-face` pointing to `/public/fonts/` (correct long-term approach) but those woff2 files don't exist yet. Adjusted to the `<link rel="stylesheet">` + `preconnect` approach — same render-unblocking benefit, no font files required. Self-hosting can be added later as an incremental improvement.

**GitHub:** pending commit | **Vercel:** auto-deploy will trigger after push

**Open questions:** None.

---

### Session 20 — Fix blank page after Session 19 perf deploy

**Root cause:** In `keycloak.ts`, production mode used `onLoad: 'login-required'`. This calls `window.location.href = keycloakLoginUrl` (a full browser redirect to `http://localhost:8180/...`). Since there is no Keycloak server deployed, the browser navigates to an unreachable host and shows a connection-refused error page. The app appeared blank because the page was redirected away, not because of a rendering error.

**Secondary bug:** `configureKeycloak()` used `Object.assign(keycloak, { url: '...' })` but keycloak-js stores the URL as `authServerUrl` internally, not `url`. So even if `VITE_KEYCLOAK_URL` had been set on Vercel, the Keycloak instance would still have used `localhost:8180`. Fixed by also assigning `authServerUrl` directly.

**Why it looked like it worked before:** `onLoad: 'login-required'` with no reachable Keycloak server → browser redirects to localhost:8180 → connection refused error page. Before the perf-commit deploy, the user was likely testing at `localhost:5173` (DevAuthProvider) and not the Vercel URL. The previous Vercel build had the same bug but it went unnoticed.

**Fixes:**
1. `main.tsx` — gated `AuthWrapper` on `VITE_KEYCLOAK_URL` being set, not on `import.meta.env.DEV`. Without the env var, always uses `DevAuthProvider`. When `VITE_KEYCLOAK_URL` is set in Vercel env vars (when Keycloak is deployed), `AuthProvider` is used automatically.
2. `keycloak.ts` — `onLoad` now uses `'check-sso'` (no redirect) when `VITE_KEYCLOAK_URL` is not configured. Removed the `silentCheckSsoRedirectUri` which referenced a `silent-check-sso.html` that doesn't exist. Fixed `configureKeycloak` to also set `authServerUrl` directly.

**GitHub:** pending commit | **Vercel:** auto-deploy will trigger after push

**Open questions:** None.

---

### Session 21 — Revert manualChunks to restore page load

**Problem:** After the performance commit (`5a7eaf2`), the deployed page stopped loading entirely. All server-side checks passed (all assets return 200, correct content-types, HTML is valid, DevAuthProvider is active in the bundle, no 404s). The issue could not be reproduced locally without a browser. The `manualChunks` configuration is the most structurally complex change introduced and cannot be debugged without browser console access.

**Fix:** Removed the `manualChunks` rollupOptions from `vite.config.ts`. Vite's default chunking strategy is used instead (single vendor bundle per entry point). All other performance improvements from Session 19 are kept: font loading strategy (preconnect + link rel=stylesheet), devtools tree-shake, auth fix (Session 20), cache headers in vercel.json.

**What's retained from Session 19:** Font loading fix, devtools tree-shake, `chunkSizeWarningLimit: 600`, Vercel cache headers, auth fix.

**What's reverted:** Only `manualChunks` rollupOptions. Can be re-introduced after verifying the app loads in the browser and a chunk-splitting approach that doesn't cause module loading issues is confirmed.

**GitHub:** pending commit | **Vercel:** auto-deploy will trigger after push

**Open questions:** None.

**Outcome confirmed:** App loaded in under 2 seconds after deploying `408af8a`. The `manualChunks` was causing a browser-side module initialization ordering issue — confirmed by the fact that reverting it immediately fixed the blank page. The remaining 3 performance improvements (font loading, devtools tree-shake, Vercel cache headers) are working and producing the measurable improvement.

---

## 2026-04-24 (continued)

### Session 22 — Build 10: Audit & Compliance module

**Files created/modified:**

| File | Change |
|---|---|
| `modules/audit/index.tsx` | Updated — replaced placeholder with `export { default } from './pages/AuditPage'` |
| `modules/audit/pages/AuditPage.tsx` | New — main page: PageHeader + 4 StatCards (Events Today, Failed Logins 24h, Open Alerts, Data Changes 7d) + Tabs (Audit Log \| Login & Sessions \| Reports \| Alerts with open-alert count badge) |
| `modules/audit/pages/audit-log/AuditLogTab.tsx` | New — filter bar (entity type, action, user, entity ref, date from/to); 15 mock entries across POLICY/CLAIM/CUSTOMER/ENDORSEMENT/QUOTE/RECEIPT/PAYMENT/USER/REINSURANCE/PARTNER_APP; entity ref column is clickable → AuditEventDetailSheet; client-side CSV export via Blob + createObjectURL; filtered count shown on Export button |
| `modules/audit/pages/audit-log/AuditEventDetailSheet.tsx` | New — full event details (entity type, ref, action, user, IP, session ID, timestamp) + side-by-side before/after JSON panels in scrollable pre blocks |
| `modules/audit/pages/login-log/LoginLogTab.tsx` | New — filter by event type (ALL/LOGIN/LOGOUT/LOGIN_FAILED/PASSWORD_RESET/ACCOUNT_LOCKED), user/email, date range; 12 mock entries including 3 consecutive failed logins + account lock; CSV export |
| `modules/audit/pages/reports/ReportsTab.tsx` | New — 6 sub-tabs: Actions by User (ranked by total), Actions by Module (with today/week/month counts), Approval Audit Trail, Data Change History (field-level old→new), Login Security (with Low/Medium/High risk badge), User Activity Summary (activity score); Export CSV button on each |
| `modules/audit/pages/alerts/AlertsTab.tsx` | New — DataTable of alerts (OPEN/ACKNOWLEDGED) with severity badges; open-alerts banner; Acknowledge confirmation Dialog; alert threshold summary cards; Configure Alerts button → AlertConfigDialog |
| `modules/audit/pages/alerts/AlertConfigDialog.tsx` | New — RHF+Zod form: failed login threshold, bulk delete threshold, large approval threshold (₦), business hours start/end, retention years, email alert toggle + recipients; System Admin only |

**Decisions made:**
- CSV export is client-side (Blob + createObjectURL) — no backend round-trip needed for the stub. Both AuditLogTab and LoginLogTab export filtered rows only, with today's date in the filename.
- Entity ref cells in AuditLogTab are `<button>` elements that open the detail Sheet — the standard pattern used throughout (policy number in PolicyListPage, debit note in ReceivablesTab, etc.).
- `onRowClick` does NOT exist on `DataTable` — row drill-down is always via a clickable cell or row-actions menu.
- The before/after JSON diff shows both panels side-by-side even when one is null (shows "No data" placeholder). Full JSON is in a scrollable `max-h-64` `pre` block.
- AlertConfigDialog resets to defaults on cancel/close — prevents stale form state if the dialog is reopened.

**Build Queue update:**
- Build 10 (Audit & Compliance) → all 5 sub-pages marked `[x]`
- Phase 2 count: 9/9 complete
- Progress Summary: 14/19 (74%)

**GitHub:** pending commit | **Vercel:** auto-deploy will trigger after push

**Open questions:** None.

---

### Session 23 — Figma sync: all module screens, dialogs, and sheets

**Figma file:** `Zaiu2K7NvEJ7Cjj6z1xt2D` (BackOffice design file)

**Pages updated / created:**

| Page | Frames added |
|---|---|
| Dashboard | Pre-existing `BackOffice / Dashboard` — verified, looks correct |
| Setup | `BackOffice / Setup` — Users management DataTable, status badges, active sidebar state |
| Customers | `BackOffice / Customers` — Customer list with KYC status badges; `BackOffice / Customer / Chioma Okafor / Summary` — customer detail with summary card + policy history |
| Quotation | `BackOffice / Quotation` — Quote list with version info, status, premium |
| Policies | `BackOffice / Policies` — Policy list; `BackOffice / Policy / POL-2026-00001 / Summary` — policy detail with 5-tab nav, policy details + financial summary cards; `Sheet: Create Policy` — tab toggle (From Quote / Direct Entry) + form fields |
| Finance | `BackOffice / Finance` — Receivables tab with debit notes; `Dialog: Debit Note Detail` — policy info + amount due + Post Receipt CTA; `Sheet: Post Receipt` — amount, method, bank, reference |
| Endorsements | `BackOffice / Endorsements` — Endorsements list with types, pro-rata amounts; `Sheet: Create Endorsement` — type select, new SI, effective date, pro-rata preview card |
| Claims | `BackOffice / Claims` — List with 3 stat cards; `BackOffice / Claims / Detail — Processing` — Processing tab with reserves table, advisory banner, comments feed; `Sheet: Register Claim`; `Dialog: Submit for Approval`; `Dialog: Add Reserve` |
| Reinsurance | `BackOffice / Reinsurance` — Treaties tab with sub-tab bar; `Sheet: Treaty Setup` — treaty form + reinsurer share rows; `Dialog: FAC Credit Note` — gross/commission/net breakdown; `Sheet: Policy Allocation Detail` — policy info + retention/ceding split bar + Approve/Decline actions |
| Audit | `BackOffice / Audit` — Stat cards + 4-tab layout + audit log table; `Sheet: Audit Event Detail` — event metadata card + side-by-side Before/After JSON diff panels; `Dialog: Alert Config` — thresholds, business hours, retention, email toggle |
| Audit (new page) | Created the Audit Figma page (was missing entirely) |

**Key technical decisions:**
- Initial auto-layout approach caused text overflow and overlap when `clipsContent=false` and frames exceeded their parent bounds. Fixed by switching to `layoutMode='NONE'` (absolute positioning) + `clipsContent=true` for all Sheet and Dialog frames. This gives pixel-precise layout without overflow.
- `String.prototype.sub()` bug: `cell?.sub` was truthy for ALL strings (because strings have a `sub()` method). Fixed by guarding with `typeof cell === 'object' && cell !== null && 'sub' in cell`.
- Each frame positioned with explicit `x`/`y` relative to parent frame (absolute layout) rather than auto-layout spacing chains, which avoids the common Figma API overflow issue.

**Figma node IDs created (key screens):**
- Setup main: `107:2` | Customers main: `107:162`
- Quotation: `109:2` | Policies: `109:184`
- Finance: `111:2` | Endorsements: `111:162`
- Claims list: `112:2` | Claims detail: `118:2` | Reinsurance: `112:190`
- Audit main: `114:2` | Policy Detail: `121:2` | Customer Detail: `122:2`

**Open questions:** None.

---

### Session 24 — Fix Finance, Claims, Reinsurance Figma screens

**Figma file:** `Zaiu2K7NvEJ7Cjj6z1xt2D`

**Diagnosis:**
| Screen | Issues found |
|---|---|
| Finance (111:2) | Missing Receivables/Payables tab bar, missing stat cards, no section subheader |
| Claims (112:2) | Missing 3 stat cards, Description column text overflowed into Reserve column (Figma text has no native overflow clipping) |
| Reinsurance (112:190) | Missing "Add Treaty" action button in page header |
| All three | Stale duplicate frames (75:3, 84:3, 87:3) stacked at same position (80,80); orphaned fragments (116:8 "pc", 116:99 "tp", 116:105 "tp") at (0,0) |

**Fixes applied:**
- Deleted 6 stale/orphaned frames across all three pages
- **Finance**: Rebuilt Content with Receivables/Payables tab bar, 3 stat cards (Total Outstanding, Receipts Pending, Outstanding Credit Notes), "Outstanding Debit Notes" section subheader + "Bulk Receipt (3)" button
- **Claims**: Rebuilt Content with 3 stat cards (Open Claims 4, Total Reserve ₦2,375,000, Total Paid YTD ₦265,000), rebuilt table with Description cells as CLIPPING FRAMES (`clipsContent=true`) to prevent text overflow into Reserve column, shorter description strings, three-dot action column
- **Reinsurance**: Rebuilt Content with "Add Treaty" button in page header, tab bar (Treaties/RI Allocations/FAC Outward/FAC Inward/Reports), treaty type coloured pills (Surplus=green, Quota Share=amber, XOL=gray), status badges

**Key lesson:** Figma text nodes never clip automatically regardless of container size. When using `layoutMode='NONE'` (absolute positioning), long text overflows into adjacent columns. Fix: wrap the text node in a fixed-size frame with `clipsContent=true`. Applied to the Description column in the Claims table.

**Open questions:** None.

---

### Session 25 — Build 2 complete: Policy Specifications (Setup module)

**Files created:**
- `cia-frontend/apps/back-office/src/modules/setup/pages/policy-specs/PolicySpecificationsPage.tsx` — page shell: PageHeader + two Tabs (Clause Bank, Templates)
- `cia-frontend/apps/back-office/src/modules/setup/pages/policy-specs/clause-types.ts` — shared types: ClauseRow, ClauseType, ClauseApplicability, ClauseSavePayload, PRODUCTS, CLAUSE_TYPES (extracted to avoid circular import between ClauseSheet and ClauseBankTab)
- `cia-frontend/apps/back-office/src/modules/setup/pages/policy-specs/ClauseBankTab.tsx` — Clause Bank tab: DataTable + hand-rolled toolbar (search + product filter + type filter), 8 mock clauses covering all 4 types and both applicability values, ClauseSheet CRUD, delete confirm dialog
- `cia-frontend/apps/back-office/src/modules/setup/pages/policy-specs/ClauseSheet.tsx` — create/edit clause drawer: react-hook-form + Zod, Switch for mandatory/optional toggle, FormField-wrapped Checkbox list for multi-product selection
- `cia-frontend/apps/back-office/src/modules/setup/pages/policy-specs/template-types.ts` — shared types: TemplateRow, TemplateType, TEMPLATE_TYPES (6 types)
- `cia-frontend/apps/back-office/src/modules/setup/pages/policy-specs/TemplatesTab.tsx` — Templates tab: product selector, custom grid card list, archive/delete/replace confirm dialogs, DropdownMenu row actions
- `cia-frontend/apps/back-office/src/modules/setup/pages/policy-specs/TemplateUploadSheet.tsx` — upload drawer: drag-and-drop zone, file validation (.docx/.pdf, 10 MB max), Replace mode locks type field

**Files modified:**
- `cia-frontend/apps/back-office/src/modules/setup/layout/SetupLayout.tsx` — added "Policy Specifications" nav item under Products group
- `cia-frontend/apps/back-office/src/modules/setup/index.tsx` — added lazy import + route for `/setup/policy-specifications`
- `CLAUDE.md` — marked Policy Specifications `[x]`, Build 2 fully `[x]`, Build Progress Summary updated

**Decisions made:**
- Clause types: Standard / Exclusion / Special Condition / Warranty
- Mandatory clauses auto-apply to new policies; Optional available in picker on Policy Detail Document tab
- Template types: Policy Document / Certificate / Schedule / Debit Note / Endorsement / Other
- Multiple templates per product; each has type + Active/Archived status
- Replacing a template archives the previous version atomically (single setTemplates call)
- Shared types in clause-types.ts and template-types.ts to avoid circular imports
- DataTable toolbar hand-rolled (not built-in toolbar prop) — three coordinated filters need unified state
- columns wrapped in useMemo; type filter derived from CLAUSE_TYPES constant
- `openEdit` and `openDuplicate` wrapped in `useCallback` so useMemo empty-dep-array columns captures stable references
- File input value explicitly reset (`fileInputRef.current.value = ''`) on sheet close to prevent same-file reselection edge case

**Figma sync:** Policy Specifications screens created in file `Zaiu2K7NvEJ7Cjj6z1xt2D` (Setup page)

- `137:2` — "BackOffice / Policy Specifications" — Clause Bank tab active; full toolbar (search + product filter + type filter + Add Clause button); 8-row DataTable with Mandatory/Optional badges; all 4 clause types represented; paginator strip
- `141:2` — "Sheet: Add Clause" — right-side drawer; Title, Clause Text, Type, Applicability toggle (Mandatory helper text), multi-product checkbox list with chip previews
- `143:2` — "BackOffice / Policy Specifications / Templates" — Templates tab active; product selector showing "Private Motor Comprehensive"; 2-active-templates hint; Upload Template button; 3-row custom card list (Policy Document blue, Certificate amber, Schedule neutral/archived at 55% opacity)

**Open questions:** None.

---

### Session 26 — Figma re-sync: Finance, Claims, Reinsurance (pixel-perfect screenshots)

**Context:** Sessions 24 deleted the old programmatic Figma frames for Finance, Claims, and Reinsurance due to alignment/overlap/placement issues. This session re-captured all screens as pixel-perfect screenshots from the live app (localhost:5173) and created new named frames — one frame per view — across the three module pages.

**Figma file:** `Zaiu2K7NvEJ7Cjj6z1xt2D`

**Upload pattern used:** `upload_assets` (count N) → multipart/form-data sequential curl → get `imageHash` per file → `use_figma` applies hash as `IMAGE` fill to named frame → auto-created frames deleted.

**Finance page (node 75:2) — 4 frames:**

| Node ID | Frame name | Screen |
|---|---|---|
| `146:2` | BackOffice / Finance / Receivables | Receivables tab — Outstanding Debit Notes table + Receipts section |
| `146:3` | BackOffice / Finance / Payables | Payables tab — Outstanding Credit Notes table + Payments section |
| `146:4` | Sheet: Post Receipt | Post Receipt sheet — payment method, bank, amount, reference |
| `146:5` | Sheet: Process Payment | Process Payment sheet — bank details, amount, reference |

**Claims page (node 84:2) — 7 frames:**

| Node ID | Frame name | Screen |
|---|---|---|
| `153:2` | BackOffice / Claims / List | Claims list — 3 stat cards + DataTable with 5 mock claims |
| `153:3` | BackOffice / Claims / Detail / Summary | Claim detail: Summary tab — claim info, policy link, contact |
| `153:4` | BackOffice / Claims / Detail / Processing | Processing tab — Reserves table, Expenses table, Comments feed |
| `153:5` | BackOffice / Claims / Detail / Documents | Documents tab — missing docs badge, document checklist |
| `153:6` | BackOffice / Claims / Detail / Inspection | Inspection tab — assign surveyor, report upload, override |
| `153:7` | BackOffice / Claims / Detail / DV | DV tab — claim type cards (Own Damage / Third Party / Ex-gratia), Generate DV |
| `153:8` | Sheet: Register Claim | Register Claim sheet — policy select, incident date, loss details, contact |

**Reinsurance page (node 87:2) — 9 frames:**

| Node ID | Frame name | Screen |
|---|---|---|
| `162:2` | BackOffice / Reinsurance / Treaties | Treaties tab — treaty DataTable with Surplus/QS/XOL type chips + Treaty Summary cards |
| `162:3` | BackOffice / Reinsurance / Allocations | Allocations tab — RI Allocations table, confirm banner, excess capacity banner |
| `162:4` | BackOffice / Reinsurance / FAC / Outward | Facultative tab → Outward sub-tab |
| `162:5` | BackOffice / Reinsurance / FAC / Inward | Facultative tab → Inward sub-tab |
| `162:6` | BackOffice / Reinsurance / Reports / Bordereaux | Returns & Reports tab → Bordereaux sub-tab |
| `162:7` | BackOffice / Reinsurance / Reports / Recoveries | Returns & Reports tab → Recoveries sub-tab |
| `162:8` | BackOffice / Reinsurance / Reports / Returns | Returns & Reports tab → Returns sub-tab |
| `162:9` | Sheet: Add Treaty | Add Treaty sheet — treaty type, class, retention, capacity, reinsurers |
| `162:10` | Sheet: Batch Reallocation | Batch Reallocation sheet — multi-select allocations, new treaty, effective date |

**Issue fixed:** Previous session had non-deterministic parallel curl ordering that mis-assigned imageHashes to frames (e.g. Finance/Receivables frame was showing Post Receipt Sheet content). Fixed by uploading images sequentially (no background `&`) so hash order matches file order.

**Open questions:** None.

---

### Session 27 — Build 11: Reports & Analytics module (backend + frontend)

**Build completed:** Build 11 — Module 11: Reports & Analytics

---

**Backend files created (`cia-backend/cia-reports/`):**

| File | Purpose |
|---|---|
| `pom.xml` | Maven module — depends on cia-common, cia-auth; adds PDFBox, commons-csv, JFreeChart |
| `domain/ReportCategory.java` | Enum: UNDERWRITING, CLAIMS, FINANCE, REINSURANCE, CUSTOMER, REGULATORY |
| `domain/ReportType.java` | Enum: SYSTEM, CUSTOM |
| `domain/DataSource.java` | Enum: POLICIES, CLAIMS, FINANCE, REINSURANCE, CUSTOMERS, ENDORSEMENTS |
| `domain/ReportField.java` | POJO: key, label, type, computed flag |
| `domain/ReportFilter.java` | POJO: key, label, type, required flag |
| `domain/ReportChart.java` | POJO: type (BAR/LINE/PIE/TABLE_ONLY), xAxis, yAxis |
| `domain/ReportConfig.java` | Root JSONB POJO: fields, filters, groupBy, sortBy, sortDir, chart |
| `domain/ReportConfigConverter.java` | JPA AttributeConverter — serializes ReportConfig ↔ JSONB string |
| `domain/ReportDefinition.java` | JPA entity — extends BaseEntity; config column uses ReportConfigConverter |
| `domain/ReportPin.java` | JPA entity — user ↔ report pin with display_order |
| `domain/ReportAccessPolicy.java` | JPA entity — category-level or report-level access per access group |
| `repository/ReportDefinitionRepository.java` | JpaRepository + JpaSpecificationExecutor |
| `repository/ReportPinRepository.java` | Pin CRUD + findByUserIdOrderByDisplayOrderAsc |
| `repository/ReportAccessPolicyRepository.java` | Category-level and report-level policy lookup |
| `service/ReportAccessService.java` | Resolves effective permissions: report-level > category-level > deny |
| `service/ReportDefinitionService.java` | CRUD + clone (SYSTEM → CUSTOM); delete blocked for SYSTEM type |
| `service/ReportQueryBuilder.java` | Builds + executes native SQL from ReportConfig; post-processes computed fields (loss_ratio, combined_ratio, etc.); sanitizes ORDER BY with whitelist |
| `service/ReportCsvRenderer.java` | Streams RFC 4180 CSV via StreamingResponseBody; UTF-8 BOM for Excel |
| `service/ReportPdfRenderer.java` | PDFBox 3.x branded PDF — header, subtitle, zebra-striped table, footer; never throws |
| `service/ReportRunnerService.java` | Orchestrates run → JSON/CSV/PDF; pin management |
| `controller/dto/ReportDefinitionDto.java` | Response DTO with from() factory |
| `controller/dto/ReportRunRequest.java` | { reportId, filters Map, format } |
| `controller/dto/ReportResultDto.java` | { columns, rows, totalRows } |
| `controller/dto/CreateReportRequest.java` | Create/update payload |
| `controller/dto/AccessPolicyUpdateRequest.java` | Upsert access policy payload |
| `controller/ReportController.java` | 14 REST endpoints under /api/v1/reports/ |

**Backend files modified:**

| File | Change |
|---|---|
| `cia-backend/pom.xml` | Added `cia-reports` to `<modules>` and `<dependencyManagement>` |
| `cia-backend/cia-api/pom.xml` | Added `cia-reports` dependency |

**Flyway migrations created:**

| File | Purpose |
|---|---|
| `V17__create_reports_tables.sql` | Creates report_definition, report_pin, report_access_policy + indexes |
| `V18__seed_system_report_definitions.sql` | Inserts all 55 SYSTEM report definitions (12+13+9+8+5+8) |

---

**Frontend files created (`cia-frontend/apps/back-office/src/modules/reports/`):**

| File | Purpose |
|---|---|
| `types/report.types.ts` | All TypeScript types + CATEGORY_LABELS + CATEGORY_COLORS + DATA_SOURCE_OPTIONS |
| `hooks/useReportDefinitions.ts` | useReportDefinitions(category?) + useReportDefinition(id) |
| `hooks/useRunReport.ts` | useRunReport + useExportCsv + useExportPdf (blob download) |
| `hooks/useReportPins.ts` | useReportPins + usePinReport + useUnpinReport |
| `hooks/useReportAccessPolicies.ts` | useReportAccessPolicies + useUpsertAccessPolicy |
| `pages/home/ReportsHomePage.tsx` | Pinned row, recently run, quick-access grid by category (6 × 4 cards) |
| `pages/library/ReportLibraryPage.tsx` | Search + category tab filter + card list with Run / Clone & Edit actions |
| `pages/viewer/ReportViewerPage.tsx` | Breadcrumb, dynamic filter form, result table + chart, export bar |
| `pages/viewer/ReportFilterForm.tsx` | Dynamic form built from config.filters — date inputs, required validation |
| `pages/viewer/ReportResultTable.tsx` | Plain HTML table — ₦ money formatting, % formatting, date formatting |
| `pages/viewer/ReportChart.tsx` | Recharts wrapper — BAR/LINE/PIE driven by config.chart; returns null for TABLE_ONLY |
| `pages/viewer/ReportExportBar.tsx` | Export CSV + Export PDF + Pin/Unpin (Bookmark01Icon / BookmarkRemove01Icon) |
| `pages/builder/CustomReportBuilderPage.tsx` | 3-step stepper shell + save mutation → navigate to viewer |
| `pages/builder/steps/Step1DataSource.tsx` | Data source card selector (6 options) |
| `pages/builder/steps/Step2FieldsFilters.tsx` | Field picker checkboxes + computed badge + date filter toggles |
| `pages/builder/steps/Step3Visualisation.tsx` | Chart type cards + axis selects + report name + category |
| `pages/setup/ReportAccessSetupPage.tsx` | Access group selector + expandable category/report permission matrix |
| `index.tsx` | Module routes: / library custom custom/:id run/:id setup |

**Frontend files modified:**

| File | Change |
|---|---|
| `app/router.tsx` | Added ReportsModule lazy import + `/reports/*` route |
| `app/layout/Sidebar.tsx` | Added BarChartIcon import + REPORTS nav group |
| `apps/back-office/package.json` | Added recharts ^3.8.1 |

---

**Key decisions:**
- `cia-reports` has zero dependency on any business module — `ReportQueryBuilder` uses `EntityManager.createNativeQuery()` directly. Adding a new pre-built report is a Flyway data migration, not a code change.
- `ReportConfig` stored as JSONB via `AttributeConverter<ReportConfig, String>` — avoids Hibernate Types library dependency.
- Computed fields (loss_ratio, combined_ratio, etc.) are post-processed in Java after raw SQL returns — keeps SQL simple while supporting formulas.
- ORDER BY in `ReportQueryBuilder` uses a whitelist sanitizer (`replaceAll("[^a-zA-Z0-9_.]", "")`) to prevent SQL injection on the sort column.
- Badge `"secondary"` is not a valid variant in `@cia/ui` — valid values are: default, outline, active, pending, rejected, draft, cancelled.
- `Pin01Icon` does not exist in hugeicons v4.1.1 — use `Bookmark01Icon` / `BookmarkRemove01Icon`.
- `Breadcrumb` in `@cia/ui` takes `items: BreadcrumbItem[]` prop — not sub-components.
- `Table`/`TableBody`/etc. are not exported from `@cia/ui` — use plain HTML `<table>` with Tailwind classes.

**Typecheck:** `pnpm --filter @cia/back-office typecheck` exits 0.

**Build Queue update:** Build 11 (Reports & Analytics) marked `[x]` complete. Phase 2 now 10/10 complete. Total 15/20 (75%).

**Open questions:** None.

---

### Session 28 — Docs: Module 11 architecture diagram in SKILL.md + CLAUDE.md update

**Files modified:**

| File | Change |
|---|---|
| `.claude/skills/cia/SKILL.md` | Added full Module 11 architecture section, updated module/feature counts, extended Data Model and Development Conventions |
| `CLAUDE.md` | Added `cia-reports` API Design section under Development Standards; fixed Phase 1 note "10 modules" → "11 modules" |
| `cia-log.md` | This entry |

**What was added to SKILL.md:**
- Module inventory description for Module 11 (20 features)
- Feature count: 158 → 178 features across 11 modules
- Module description count: 10 → 11 modules in frontmatter
- New `## Module 11 Architecture — Reports & Analytics` section covering:
  - Backend: full `ReportController` endpoint map (14 endpoints + required authorities), `ReportRunnerService` pipeline, `ReportQueryBuilder` SQL construction + computed field post-processing, `ReportAccessService` resolution rules, `ReportConfig` JSONB shape, computed fields formula table, 55 SYSTEM report catalogue summary by category with IDs
  - Frontend: route tree with component hierarchy, React Query hooks table (10 hooks)
- Data Model additions: `report_definition`, `report_pin`, `report_access_policy` entities; 2 new key relationships
- Development Conventions: `cia-reports` isolation rule + access resolution rule (invisible-not-denied pattern)

**What was added to CLAUDE.md:**
- `### Reports API Design (cia-reports specific)` section with 12 actionable conventions covering: zero-dependency rule, adding reports via migration, SYSTEM report immutability, computed fields pattern, ORDER BY SQL injection prevention, access resolution (invisible not denied), DB constraint rules, pin uniqueness, regulatory report `is_pinnable=false`, chart TABLE_ONLY handling

**Open questions:** None.

---

### Session 29 — Figma sync: Module 11 Reports & Analytics screens

**Figma file:** `Zaiu2K7NvEJ7Cjj6z1xt2D` (BackOffice design file)

**Pre-sync:**
- Confirmed 5 commits were not pushed to GitHub remote
- Pushed to `origin/main` → triggered GitHub Actions (`Vercel Deploy — NubSure Back Office`)
- Run ID `24936225992` completed with `success`
- Latest Vercel deployment (3m ago): `back-office-60plichri-razormvps-projects.vercel.app` — `● Ready` (Production)
- Screenshots taken from local dev server (localhost:5173) using DevAuthProvider — backend not required

**New Figma page created:** `Reports` (node `229:2`)

**Frames created:**

| Node ID | Frame name | Screen |
|---|---|---|
| `229:3` | BackOffice / Reports / Home | Reports home — Quick Access grid (6 categories with colour labels), empty pin state, New Custom Report CTA |
| `229:4` | BackOffice / Reports / Library | Report Library — search bar, category tab row (All + 6 categories), empty state |
| `229:5` | BackOffice / Reports / Builder — Step 1 Data Source | 3-step stepper, Step 1 active (teal), 6 data source cards with descriptions |
| `229:6` | BackOffice / Reports / Builder — Step 2 Fields | Step 2 active, field picker checkboxes (11 fields inc. computed badges), Date Filters row |
| `229:7` | BackOffice / Reports / Access Setup | Report Access Control — group selector, empty state before group selected |

**Upload method:** `upload_assets` (single file per call, sequential) → multipart curl → `imageHash` → `use_figma` IMAGE fill. All 5 uploads successful.

**Note:** Report Viewer (`/reports/run/:id`) was not synced — renders blank without a live backend to resolve the report definition. Will be captured in a future session once backend integration is complete.

**Open questions:** None.

---

### Session 30 — Fix dev stack: Vite proxy port + DevSecurityConfig

**Files modified:**

| File | Change |
|---|---|
| `cia-frontend/apps/back-office/vite.config.ts` | Corrected Vite proxy target from `localhost:8080` to `localhost:8090` to match the Spring Boot default port in `application.yml` |
| `cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/DevSecurityConfig.java` | New `@Profile("dev") @Order(1)` security chain that permits all requests without JWT validation |

**Why:**
- Backend was already running on port 8090 (default in `application.yml`); Vite proxy was pointing to 8080 causing all API calls to fail silently
- `DevAuthProvider` in the frontend sends no JWT token, so the backend's `SecurityConfig` returned 401 on every request
- `DevSecurityConfig` bypasses JWT validation in dev mode — safe because `TenantIdentifierResolver` already defaults to `"public"` schema when no tenant ID is present, and the `report_definition` table (V17/V18) lives in `public`

**Result:** After rebuilding the backend and restarting both servers, `localhost:5173/reports/library` will show all 55 pre-built SYSTEM report definitions.

**Restart steps (for reference):**
1. Stop current backend (Ctrl+C)
2. `cd cia-backend && mvn install -DskipTests -q`
3. `mvn spring-boot:run -pl cia-api -Pdev -q`
4. Restart Vite: `pnpm --filter @cia/back-office dev`

**Open questions:** None.

---

### Session 31 — Fix: 55 pre-built reports loading in browser

**Root cause chain:**
1. **Jackson deserialization error (500):** `ReportChart.xAxis`/`yAxis` fields — Lombok getter `getXAxis()` + `Introspector.decapitalize("XAxis")` produced property name `XAxis`, not `xAxis`, so Jackson couldn't match the JSON stored in V18 migration. Fixed with `@JsonProperty("xAxis")` and `@JsonProperty("yAxis")`.
2. **ObjectMapper resilience:** Added `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES=false` to `ReportConfigConverter` so future JSON schema additions never cause hard crashes.
3. **Browser calling wrong port:** `apiClient` was initialized with absolute base URL `http://localhost:8080` (the `main.tsx` default). Created `.env.local` with `VITE_API_BASE_URL=` (empty) so `apiClient` uses relative paths that go through the Vite proxy (`/api` → `localhost:8090`). Proxy config was already updated to 8090 in Session 30.

**Files modified:**

| File | Change |
|---|---|
| `cia-backend/cia-reports/src/.../domain/ReportChart.java` | Added `@JsonProperty("xAxis")` and `@JsonProperty("yAxis")` |
| `cia-backend/cia-reports/src/.../domain/ReportConfigConverter.java` | Added `FAIL_ON_UNKNOWN_PROPERTIES=false` to ObjectMapper |
| `cia-frontend/apps/back-office/.env.local` | Created: `VITE_API_BASE_URL=` (empty, dev-only, gitignored) |

**Verification:** `localhost:5173/reports/library` shows "55 reports available" with all category badges, descriptions, Run Report and Clone & Edit actions.

**Open questions:** None.

---

### Session 31 (addendum) — Add .env.local to .gitignore

Added `.env.local` to `cia-frontend/apps/back-office/.gitignore` so the dev-only `VITE_API_BASE_URL=` override is never accidentally committed to the repo.

---

### Session 32 — Audit + fix: ReportQueryBuilder critical issues

**Audit findings (superpowers:code-reviewer):**
- **2 Critical**, 4 Important, 4 Minor issues found across the full build.

**Critical fixes applied (both in `ReportQueryBuilder.java`):**

1. **Datasource-aware filter aliases** — `date_from`/`date_to` filter clauses were unconditionally using `p.created_at` (POLICIES alias). For CUSTOMERS, CLAIMS, FINANCE, REINSURANCE, and ENDORSEMENTS datasources, the `p` alias either does not exist or refers to a joined table, causing a PostgreSQL runtime error. Fixed by adding `createdAtCol(DataSource)`, `statusCol(DataSource)`, and `hasCobJoin(DataSource)` helpers that dispatch to the correct table alias per datasource. Running `Active Customers` or `KYC Status Report` with a date filter would have returned 500 before this fix.

2. **Missing `utilisation_pct` computed field** — The `Treaty Utilisation` SYSTEM report (R03) defines `utilisation_pct` as a computed field, but the switch in `applyComputedFields()` had no case for it. Every row showed null for the Utilisation % column. Fixed by adding `case "utilisation_pct"` using `computeRatio(map, "ceded_amount", "retained_amount")`.

**Important issues noted (not fixed this session — tracked for future):**
- No row limit on JSON endpoint (could OOM on large tenants)
- `Clone & Edit` navigates to blank builder instead of pre-populated clone
- `ReportAccessSetupPage` uses hardcoded mock access groups
- No unit tests in `cia-reports` module

**Minor issues noted:**
- `recentlyRun` is hardcoded to empty array
- V18 idempotency comment is misleading
- `MULTI_SELECT` filter renders as plain text input
- JPA positional parameter syntax (`?1`, `?2`) — valid but unusual

**File modified:**
- `cia-backend/cia-reports/src/.../service/ReportQueryBuilder.java` — added 3 helper methods + `utilisation_pct` case

**Open questions:** None.

---

### Session 33 — Module 11 polish: Clone & Edit + real access groups (audit I2 + I3)

**Files modified:**

| File | Change |
|---|---|
| `modules/reports/hooks/useReportDefinitions.ts` | Added `useCloneReport` mutation — calls `POST /api/v1/reports/definitions/:id/clone`, invalidates definitions cache on success |
| `modules/reports/pages/library/ReportLibraryPage.tsx` | Refactored `LibraryCard` to accept `onClone`/`cloning` props; `ReportLibraryPage` holds the `useCloneReport` mutation + `cloningId` state; on success navigates to `/reports/custom/:clonedId` |
| `modules/reports/pages/builder/CustomReportBuilderPage.tsx` | Added `useReportDefinition(id)` fetch when `id` in params; `useEffect` seeds `BuilderState` from fetched definition (only on first load via `seeded` flag); shows skeleton while loading; added `stateFromDefinition()` mapping helper |
| `modules/reports/pages/setup/ReportAccessSetupPage.tsx` | Replaced fabricated UUID mock groups with same IDs/names as `AccessGroupsPage` (`ag1`–`ag5`: System Admin, Underwriter, Claims Officer, Finance Officer, System Auditor) |

**Decisions:**
- `useEffect` + `seeded` flag pattern for async-seeded forms: seeds state once when definition loads, never overwrites user edits on re-renders
- `stateFromDefinition()` extracted as a pure mapping helper — keeps the component clean and testable
- `cloningId` tracks which specific card is cloning so only that button shows "Cloning…" (not all buttons)
- Access groups remain mock (consistent with all other Setup module pages) — will all move to real API together in a future session

**Typecheck:** exits 0.

**Audit items resolved:** I2 (Clone & Edit), I3 (consistent mock groups)

**Open questions:** None.

---

### Session 34 — Dashboard enhancement: 8 stat cards, approval queue, loss ratio, renewals strip

**Backend files created (`cia-backend/cia-api/src/main/java/com/nubeero/cia/dashboard/`):**

| File | Purpose |
|---|---|
| `DashboardStatsDto.java` | 8 KPI fields: activePolicies, openClaims, pendingApprovals, premiumsMtd, claimsReserveTotal, renewalsDue30Days, outstandingPremium, riUtilisationPct |
| `ApprovalQueueDto.java` | Count by entity type: policies, quotes, endorsements, claims, receipts, payments; `total()` helper |
| `LossRatioMonthDto.java` | Per-month: month label, premium, claims, lossRatioPct |
| `RenewalDayDto.java` | Per-day for 7-day strip: date, day label, count |
| `DashboardService.java` | Native SQL aggregations against tenant schema; `sanitize()` whitelist for table/column names; `generate_series` CTE for loss ratio; always returns 7 days for renewals strip (fills 0 for empty days); individual try/catch on each stat so one failure never blocks the others |
| `DashboardController.java` | 4 GET endpoints under `/api/v1/dashboard/` — stats, approval-queue, loss-ratio, renewals-due; `isAuthenticated()` guard |

**Bug fixed during verification:** `DashboardService.lossRatioTrend()` used `p.premium` — `policies` table has `total_premium` not `premium` (which lives on `policy_risks`). Fixed to `p.total_premium`.

**Frontend files created:**

| File | Purpose |
|---|---|
| `hooks/useDashboard.ts` | 4 React Query hooks: `useDashboardStats`, `useApprovalQueue`, `useLossRatioTrend`, `useRenewalsDue`; staleTime 1 min |
| `components/StatCardRow.tsx` | 8 cards in 2×4 grid (2-col mobile, 4-col desktop); each has icon badge with colour-coded accent; Skeleton loading state; `formatNaira()` for B/M/K suffixes |
| `components/ApprovalQueueWidget.tsx` | 6 rows (Policies, Quotes, Endorsements, Claims, Receipts, Payments); each is a `<Link>` to the relevant module; pending badge count; empty state when all clear |
| `components/LossRatioSparkline.tsx` | Recharts `BarChart` with colour-coded bars (teal <75%, amber 75-99%, red ≥100%); reference lines at 75% and 100%; custom tooltip; skeleton loading |
| `components/RenewalsDueStrip.tsx` | 7-day horizontal grid; today's column highlighted red if policies expiring; urgency colours (amber if >5, blue if any, gray if 0); each day links to `/policies?expiry=YYYY-MM-DD` |

**Files modified:**
- `DashboardPage.tsx` — fully replaced; now fetches all 4 data sets in parallel and renders all components

**Bug fixed:** `Receipt01Icon` doesn't exist in hugeicons v4.1.1 — replaced with `Invoice01Icon`.

**API verification (all 200 OK with empty tenant data):**
- `GET /api/v1/dashboard/stats` ✅
- `GET /api/v1/dashboard/approval-queue` ✅
- `GET /api/v1/dashboard/loss-ratio` ✅ (returns 6 months, 0-value rows for empty tenant)
- `GET /api/v1/dashboard/renewals-due` ✅ (returns 7 days)

**Typecheck:** `tsc --noEmit` exits 0.

**Open questions:** None.

---

### Session 35 — Figma sync: Enhanced Dashboard screen

**Figma file:** `Zaiu2K7NvEJ7Cjj6z1xt2D` (BackOffice design file)

**Page updated:** Dashboard (no existing frames deleted)

**New frame added:**

| Node ID | Frame name | Screen |
|---|---|---|
| `236:2` | BackOffice / Dashboard — Enhanced | New dashboard — 8 stat cards, approval queue widget, loss ratio 6-month sparkline, renewals due 7-day strip |

**Position:** x=80, y=1060 — directly below the original `BackOffice / Dashboard` (6:2) at y=80.

**Method:** `npx playwright screenshot` → `upload_assets` → `use_figma` IMAGE fill. Auto-placed duplicate frame (235:2) removed.

**All existing 6 frames on the Dashboard page preserved:**
BackOffice / Dashboard (6:2) · reports-home (223:2) · reports-library (224:2) · reports-builder-step1 (226:2) · reports-builder-step2 (227:2) · reports-access-setup (228:2)

**Open questions:** None.

---

### Session 36 — Dashboard fixes: topbar labels, notification badge, help link, recent activity, global search

**All 5 dashboard items from the connectivity audit addressed:**

**Files modified/created:**

| File | Change |
|---|---|
| `app/layout/Topbar.tsx` | Added `reports: 'Reports & Analytics'` to routeLabels; replaced static search input with `<SearchBar />`; help icon now links to Confluence PRD; notification bell wired to `useApprovalQueue` with badge count + dropdown panel listing pending counts by entity type |
| `app/layout/SearchBar.tsx` | New component — debounced input (300ms), React Query `useQuery` against `/api/v1/dashboard/search?q=`, floating dropdown with typed results (Policy/Claim/Customer/Quote) and coloured icons, keyboard Escape to close, `useClickOutside` to dismiss |
| `hooks/useClickOutside.ts` | New shared hook — mousedown + touchstart listener, cleans up on unmount |
| `modules/dashboard/hooks/useDashboard.ts` | Added `RecentActivity` type + `useRecentActivity` hook (`/api/v1/dashboard/recent-activity`, staleTime 30s) |
| `modules/dashboard/components/RecentActivityFeed.tsx` | New component — renders last 10 audit log entries; Badge variant derived from action (APPROVE/CREATE→active, REJECT/DELETE→rejected, else pending); skeleton loading state; empty state |
| `modules/dashboard/DashboardPage.tsx` | Restored Recent Activity feed section (section 4) |
| `cia-api/dashboard/RecentActivityDto.java` | New DTO: entityType, entityId, action, userName, timeAgo, statusGroup |
| `cia-api/dashboard/SearchResultDto.java` | New DTO: id, type, label, sub, path |
| `cia-api/dashboard/DashboardService.java` | Added `search(term)` — UNION ALL across policies/claims/customers/quotes, 5 params, catches SQL exceptions; added `recentActivity()` — native SQL on audit_log ORDER BY timestamp DESC LIMIT 10; `timeAgo()` helper; `actionToStatus()` helper |
| `cia-api/dashboard/DashboardController.java` | Added `GET /api/v1/dashboard/recent-activity` and `GET /api/v1/dashboard/search?q=` endpoints |

**Bugs fixed during verification:**
- Search SQL used `customer` (wrong) → corrected to `customers`
- Search SQL used `full_name` (wrong) → corrected to `COALESCE(company_name, first_name || ' ' || last_name)`
- Import paths in Topbar used `../../../` (3 levels up) instead of `../../` (2 levels up from `src/app/layout/`)

**All 6 dashboard API endpoints verified 200 OK:**
`stats` · `approval-queue` · `loss-ratio` · `renewals-due` · `recent-activity` · `search?q=POL`

**Typecheck:** `tsc --noEmit` exits 0.

**Open questions:** None.

---

### Session 37 — Topbar: VITE_HELP_URL env var for configurable help link

**Files modified:**

| File | Change |
|---|---|
| `cia-frontend/apps/back-office/src/app/layout/Topbar.tsx` | Help icon `href` now reads `import.meta.env.VITE_HELP_URL ?? '...confluence-fallback...'` |
| `cia-frontend/apps/back-office/src/vite-env.d.ts` | Added full `ImportMetaEnv` type declarations for all `VITE_*` variables; `VITE_HELP_URL` typed as `optional string` |
| `cia-frontend/apps/back-office/.env.local` | Added `VITE_HELP_URL` set to current Confluence PRD URL as local default |

**Why:** Help URL should be changeable without a source code commit or Vercel build. Setting `VITE_HELP_URL` as a Vercel environment variable and redeploying is sufficient — no code change required.

**Fallback:** If `VITE_HELP_URL` is unset, the Confluence PRD URL is used automatically.

**Typecheck:** exits 0.

**Open questions:** None.

---

### Session 38 — 2026-04-26: Docs site currency audit

**Question asked:** Is `https://cia-docs.vercel.app/` up to date with all recent changes including Swagger docs?

**Findings:**

**Last docs deployment:** 23 April 2026 (commit `feat(docs): replace internal Scalar explorer with static markdown API…`)
**Trigger:** `docs-deploy.yml` only fires when `docs-site/**` or `cia-backend/cia-partner-api/docs/openapi.json` changes are pushed. Neither changed since April 23 despite 20+ feature commits since then.

**What is out of date:**
1. `cia-reports` module (Module 11) missing entirely from `docs/architecture/modules.md` — only 10 modules listed
2. 6 new Dashboard API endpoints missing from `docs-site/static/internal-api.json`:
   - `GET /api/v1/dashboard/stats`
   - `GET /api/v1/dashboard/approval-queue`
   - `GET /api/v1/dashboard/loss-ratio`
   - `GET /api/v1/dashboard/renewals-due`
   - `GET /api/v1/dashboard/recent-activity`
   - `GET /api/v1/dashboard/search`
3. No Module 11 architecture page exists in `docs/`

**What is current:**
- Partner API Swagger (`openapi.json`) — 15 paths in source match 15 paths in docs-site static exactly ✅
- Audit module docs (Module 10) — added April 23 ✅

**Next action:** Update `modules.md`, add Module 11 docs page, update `internal-api.json` with dashboard endpoints, and trigger docs redeployment.

**Open questions:** None — work approved by user, pending execution.

---

### Session 39 — 2026-04-26: Update docs site — Module 11 + Dashboard API

**Files modified in `docs-site/`:**

| File | Change |
|---|---|
| `docs/architecture/modules.md` | Added `cia-reports/` to module inventory tree + dependency table row |
| `docs/architecture/reports-module.md` | New page — full Module 11 architecture: design decisions, package layout, 14 REST endpoints, ReportConfig JSONB shape, computed fields table, 55-report catalogue, access control resolution, Flyway migrations, dev conventions |
| `static/internal-api.json` | Added 6 Dashboard API paths (stats, approval-queue, loss-ratio, renewals-due, recent-activity, search) + 6 new schemas (DashboardStats, ApprovalQueue, LossRatioMonth, RenewalDay, RecentActivity, SearchResult). Total paths: 15 → 21. |
| `sidebars.ts` | Added `architecture/reports-module` to the Architecture sidebar category |

**Deployment trigger:** Committing to `docs-site/**` triggers `docs-deploy.yml` → builds Docusaurus → deploys to `https://cia-docs.vercel.app/`.

**Open questions:** None.

---

### Session 40 — 2026-04-26: Add Gate 9 (Docs Site) to SESSION COMPLETION GATE

**File modified:** `.claude/skills/cia/SKILL.md`

**Change:** Added **Gate 9 — Docs Site (`https://cia-docs.vercel.app/`)** as a mandatory gate item in the SESSION COMPLETION GATE. This gate fires whenever a session introduces backend or architecture changes.

**Gate 9 covers:**
- New Maven module → update `docs-site/docs/architecture/modules.md`
- New module architecture → create module doc page + sidebar entry
- New internal REST endpoints → add to `docs-site/static/internal-api.json`
- Partner API changes → ensure `cia-partner-api/docs/openapi.json` is updated (auto-synced on deploy)
- New env vars → update environment-variables.md
- New Flyway migrations → update database-migrations.md
- Security/auth changes → update security.md

**Critical note documented:** `docs-deploy.yml` hardcodes `VERCEL_PROJECT_ID: prj_KgaDZ7fSkBNu3r6GEdiV8vAoZyAC` (cia-docs project). The shared `VERCEL_PROJECT_ID` secret points to back-office — using it silently deploys docs content to the wrong project (root cause of the April 23–April 26 gap discovered in Session 38–39).

**Also fixed in same session:** `docs-deploy.yml` workflow — corrected the cia-docs project ID issue and confirmed `https://cia-docs.vercel.app/` deployed successfully with Module 11 docs and Dashboard API spec.

**Open questions:** None.

---

### Session 41 — 2026-04-26: Customer onboarding — KYC document upload + expiry dates

**Scope:** Individual and Corporate customer onboarding — both frontend and backend.

**Requirements implemented:**
- Individual: ID document upload (JPG/PNG, max 5MB) + expiry date mandatory for Driver's Licence and Passport (must be ≥ today)
- Corporate: CAC certificate upload (JPG/PNG, max 5MB) + issued date mandatory; per-director ID document upload + same expiry date rule as individual
- Backend: real `multipart/form-data` endpoints replacing `console.log` placeholders; files stored in MinIO via `DocumentStorageService`; expiry date validation at service layer

**Backend files changed:**

| File | Change |
|---|---|
| `cia-customer/pom.xml` | Added `cia-storage` dependency |
| `V19__customer_kyc_document_fields.sql` | New Flyway migration — adds `id_document_url`, `id_expiry_date` to `customers` and `customer_directors`; adds `cac_certificate_url`, `cac_issued_date` to `customers` |
| `Customer.java` | Added `idDocumentUrl`, `idExpiryDate`, `cacCertificateUrl`, `cacIssuedDate` fields |
| `CustomerDirector.java` | Added `idDocumentUrl`, `idExpiryDate` fields |
| `IndividualCustomerRequest.java` | Added `idExpiryDate` field |
| `CorporateCustomerRequest.java` | Added `cacIssuedDate` field |
| `CustomerDirectorRequest.java` | Added `idExpiryDate` field |
| `CustomerDirectorResponse.java` | Added `idDocumentUrl`, `idExpiryDate` fields |
| `CustomerResponse.java` | Added `idDocumentUrl`, `idExpiryDate`, `cacCertificateUrl`, `cacIssuedDate` fields |
| `CustomerService.java` | Injected `DocumentStorageService`; `createIndividual` and `createCorporate` now accept `MultipartFile`; added `validateExpiryDate()` (mandatory + must be ≥ today for DL/Passport), `uploadKycDocument()` (MinIO upload via `DocumentStorageService`); `addDirectors()` sets `idExpiryDate` on directors |
| `CustomerController.java` | Changed both POST endpoints to `consumes = MULTIPART_FORM_DATA_VALUE`; uses `@ModelAttribute` + `@RequestPart` for file parts |

**Frontend files changed:**

| File | Change |
|---|---|
| `IndividualOnboardingSheet.tsx` | Added Zod `superRefine` for expiry date validation; conditional `idExpiryDate` input (visible only for DL/Passport, min=today); drag-and-drop file upload zone with client-side type + size validation; `useMutation` submitting real `FormData` to `POST /api/v1/customers/individual`; error message on failure; cache invalidation on success |
| `CorporateOnboardingSheet.tsx` | Added CAC certificate upload zone + `cacIssuedDate` date input; per-director ID upload zones + conditional expiry date; `dirFileRefs` ref array pattern (avoids hooks-in-map violation); `useMutation` submitting real `FormData` to `POST /api/v1/customers/corporate` with indexed director fields |

**Key decisions:**
- Files stored in MinIO at path `customers/{customerId}/kyc/{docKey}.ext` — consistent with other document flows
- Expiry validation runs at both Zod (frontend, instant feedback) and `CustomerService` (backend, defence in depth)
- `dirFileRefs.current[i]` via callback ref (`ref={el => { dirFileRefs.current[i] = el; }}`) — avoids the React hooks-in-map violation of calling `useRef()` inside `.map()`
- Unused `i` variable in `onSubmit` eliminated by consolidating validation into a single `values.directors.map()` call

**Typecheck:** `tsc --noEmit` exits 0. Backend `mvn install -pl cia-customer` builds cleanly.

**Open questions:** None.

---

### Session 42 — 2026-04-26: Update cia-docs logo and favicon with Nubeero branding

**Files updated in `docs-site/static/`:**

| File | Change |
|---|---|
| `static/img/logo.png` | Replaced with Nubeero Icon_roundBorder.png (3726×3726 RGBA PNG) — Docusaurus navbar logo |
| `static/img/favicon.png` | Same Nubeero logo — used as PNG favicon (`favicon: "img/favicon.png"` in docusaurus.config.ts) |
| `static/favicon.ico` | Generated from Nubeero logo via Pillow at 16×16, 32×32, 48×48 — browser tab favicon fallback |

**Source file:** `/Users/razormvp/Documents/Nubeero_Images/nubeeroLogo/Nubeero Icon_roundBorder.png`

**Docusaurus config already correct** — `logo.alt: "Nubeero Logo"`, `logo.src: "img/logo.png"`, `favicon: "img/favicon.png"` — no config changes needed.

**Open questions:** None.

---

### Session 43 — 2026-04-26: Fill internal-api.json gaps + enforce Gate 9

**Root cause identified:** Sessions 34, 36, and 41 added endpoints that were never added to `docs-site/static/internal-api.json`. The session gate wording was too vague ("endpoints aren't currently documented") and allowed the gap to go unfixed across multiple sessions.

**internal-api.json updated:** 21 → 37 paths

**New paths added:**

*Customer API (9 paths):*
- `GET /customers` — list with type/kycStatus filters
- `GET /customers/search` — search by name/email/phone
- `POST /customers/individual` — multipart/form-data with `idDocument` file; expiry date rules documented
- `POST /customers/corporate` — multipart/form-data with `cacCertificate` + `directorIdDocuments[]`; all constraints documented
- `GET /customers/{id}` — customer detail
- `PUT /customers/{id}` — update contact fields
- `POST /customers/{id}/retrigger-kyc`
- `POST /customers/{id}/blacklist`
- `DELETE /customers/{id}/blacklist`

*Reports API (14 paths):*
- `GET /reports/definitions` (with category filter)
- `POST /reports/definitions` (create custom)
- `GET /reports/definitions/{id}`
- `PUT /reports/definitions/{id}`
- `DELETE /reports/definitions/{id}`
- `POST /reports/definitions/{id}/clone`
- `POST /reports/run` (JSON result)
- `POST /reports/run/csv` (streaming download)
- `POST /reports/run/pdf` (PDF download)
- `GET /reports/pins`
- `POST /reports/pins/{id}`
- `DELETE /reports/pins/{id}`
- `GET /reports/access-policies`
- `PUT /reports/access-policies`

**New schemas added:** CustomerSummary, CustomerDetail, CustomerDirector, CustomerDocument, ReportDefinition, ReportResult, ReportAccessPolicy

**Gate 9 in SKILL.md strengthened:**
- Added 9a — explicit trigger table (any new `@*Mapping` → update spec)
- Added 9b — Python audit script to run before closing any backend session
- Added 9c — path naming convention (suffix after `/api/v1/`, not full URL)
- Added 9d — deployment note with CRITICAL warning about `VERCEL_PROJECT_ID`
- Added 9e — 7-point verification checklist (replaces the old 5-point one)

**Open questions:** None.

---

### Session 44 — 2026-04-26: Complete internal-api.json — all 119 paths documented

**Context:** Comprehensive audit of all backend controllers revealed 82 paths missing from `internal-api.json`. Previous sessions only documented audit, dashboard, customer, and reports endpoints.

**internal-api.json:** 37 → 119 paths (+82)

**New paths added by module:**

| Module | Paths | Key endpoints |
|---|---|---|
| Claims | 17 | search, get/update, assign-surveyor, reserve, submit/approve/reject/withdraw/settle, reserves, documents, expenses |
| Customers (extensions) | 2 | customer document get/delete |
| Documents | 2 | document-templates get/update |
| Endorsements | 7 | get/update, submit/approve/reject/cancel, premium-preview |
| Finance | 13 | debit-notes get/update/cancel/void, receipts get/reverse, credit-notes get/update/cancel, payments get/reverse |
| Partner Apps | 4 | get/update/revoke, activate |
| Policies | 10 | search, get/update, bind-from-quote, submit/approve/reject/cancel/reinstate, naicom-upload |
| Quotation | 6 | search, get/update, submit/approve/reject |
| Reinsurance | 15 | allocations get/update/confirm/cancel, fac-covers get/update/confirm/cancel, treaties get/update/activate/expire/cancel/participants |
| Setup | 62 | company-settings, access-groups, approval-groups, banks, currencies, cause-of-loss, claim-reserve-categories, nature-of-loss, branches, brokers, insurance-companies, reinsurance-companies, relationship-managers, sbus, surveyors, products, classes-of-business, vehicle-makes/models, vehicle-types |

**New schemas added:** ClaimSummary, EndorsementSummary, DebitNote, CreditNote, Receipt, Payment, PolicySummary, QuoteSummary, RiAllocation, RiFacCover, RiTreaty, SetupEntity, PartnerApp

**Partner API swagger (openapi.json):** 15 paths — confirmed complete against cia-partner-api controllers ✅

**API version bumped:** `1.0.0` → `2.0.0` to reflect comprehensive documentation scope.

**Open questions:** None.
