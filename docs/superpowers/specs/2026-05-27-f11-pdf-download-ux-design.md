# F11 — PDF download UX + bulk operations (design spec)

**Bundle origin:** Drains F9 (PDF download surface ergonomics) + F10 (bulk receipt-PDF email send) and pulls in three items that were originally out-of-scope for those rows:

1. Server-side cross-device "recent downloads" history
2. Bulk-download (not just bulk-email) — single browser save via backend ZIP
3. Bulk-email cancellation that signals in-flight Temporal workflows

**Goal:** Cut clicks for two operator workflows — "send N receipts/payment vouchers to customers" and "re-find / re-download the PDFs I sent earlier today" — and surface PDF actions visibly without breaking the existing "row click = detail dialog" muscle memory.

**Status:** Designed 2026-05-27 (Session 130 brainstorm). Awaiting user review of this spec before plan writing.

---

## Architecture

**Pure frontend story for F9-original, real backend additions for the pulled-in items.** Three backend pieces and seven frontend pieces.

1. **`pdf_download_log` table** — small append-only audit-style table separate from `audit_log` (which keeps high-volume PDF downloads from polluting compliance auditing). Written by `ReceiptController.downloadPdf` + `PaymentController.downloadPdf` after a successful `storage.download(...)` call. Queried by a new `GET /api/v1/finance/pdf-downloads?days=N` endpoint scoped to the calling JWT's user. 30-day retention enforced by a weekly Temporal cleanup workflow.

2. **`PdfZipService` + bulk-download endpoint** — `POST /api/v1/finance/pdfs/bulk-download` accepts `{ items: [{ type: 'RECEIPT'|'PAYMENT', id: UUID }] }`, validates each item exists + has `pdf_path != null` + caller has `FINANCE_VIEW`, streams an in-memory ZIP of the PDFs as `application/zip` with `Content-Disposition: attachment; filename="cia-pdfs-{timestamp}.zip"`. Maximum 50 items per request; 400 with errorCode `BULK_DOWNLOAD_TOO_MANY` if exceeded.

3. **Workflow cancellation signal** — both `SendReceiptEmailWorkflow` and `SendPaymentVoucherEmailWorkflow` gain a `cancel()` `@SignalMethod`. Workflow impls maintain a `cancelled: boolean` flag that's checked before each activity invocation; when set, the workflow exits successfully (no exception) and writes an audit row `action=CANCEL` with `{ workflowId, cancelledBy }`. Two new REST endpoints `POST /api/v1/debit-notes/{dnId}/receipts/{id}/email/cancel` + `POST /api/v1/credit-notes/{cnId}/payments/{id}/email/cancel` look up the running workflow by id (`"send-receipt-email-<receiptId>"` / `"send-payment-voucher-email-<paymentId>"` per slice γ convention) and signal it. Cancellation is **best-effort** — an `emailService.sendEmail(...)` call already in progress finishes; only the next retry attempt aborts.

4. **Frontend UX layer** — `DownloadIconButton` inline next to the reference cell on both list pages + both detail dialogs (replaces the "Download" row-action / detail-dialog button); `RecentDownloadsPanel` Sheet triggered from the page header (queries the new endpoint with `useQuery`, 30s stale time, Re-download row-action); `BulkEmailSheet` Sheet driven by TanStack-table checkbox selection (serial runner, Cancel button that fires the cancel-email mutation per in-flight row); `BulkDownloadButton` toolbar action that POSTs the selection to the ZIP endpoint and saves the response Blob.

Both bulk operations are symmetric — wired on receipts AND payments list pages with parallel hook + button shapes.

---

## File structure

### Backend (new + modified)

| Action | File | Responsibility |
|---|---|---|
| Create | `cia-api/src/main/resources/db/migration/V58__create_pdf_download_log_table.sql` | Schema: `(id, tenant_id, user_id, entity_type, entity_id, reference, parent_ref, recipient_name, downloaded_at, created_by)`. Indexes on `(user_id, downloaded_at DESC)` for the listing query and on `(downloaded_at)` for the retention purge. |
| Create | `cia-finance/src/main/java/com/nubeero/cia/finance/audit/PdfDownloadLog.java` | JPA entity. Extends BaseEntity. |
| Create | `cia-finance/.../audit/PdfDownloadLogRepository.java` | Spring Data: `findByUserIdAndDownloadedAtAfterOrderByDownloadedAtDesc(String userId, Instant from, Pageable pageable)` + `deleteByDownloadedAtBefore(Instant cutoff)`. |
| Create | `cia-finance/.../audit/PdfDownloadLogService.java` | `log(EntityType, UUID, String reference, String parentRef, String recipientName)` — internal append. `listForUser(int days, int limit)` — query. Internal `@Transactional(REQUIRES_NEW)` on log so a write failure can't roll back the download. |
| Create | `cia-finance/.../audit/PdfDownloadLogResponse.java` | DTO carrying `id, entityType, entityId, reference, parentRef, recipientName, downloadedAt`. |
| Create | `cia-finance/.../audit/PdfDownloadLogController.java` | `GET /api/v1/finance/pdf-downloads?days=N` (N default 1, max 30). `@PreAuthorize("hasRole('FINANCE_VIEW')")`. Returns ApiResponse with the list. |
| Modify | `cia-finance/.../ReceiptController.java` (`downloadPdf`) | After successful `storage.download(...)`, call `pdfDownloadLogService.log(EntityType.RECEIPT, receiptId, receipt.getReceiptNumber(), dn.getDebitNoteNumber(), dn.getCustomerName())`. Wrap in try/catch so a log failure doesn't block the download response. |
| Modify | `cia-finance/.../PaymentController.java` (`downloadPdf`) | Mirror. Use the credit note's `beneficiaryName` as `recipientName`. |
| Create | `cia-finance/.../bulk/PdfZipService.java` | `byte[] buildZip(String tenantId, List<BulkDownloadItem> items)` — looks up each receipt/payment via repository, calls `storage.download(tenantId, pdfPath)` for each, streams into a `ZipOutputStream` backed by `ByteArrayOutputStream`, returns bytes. Skips items with null `pdfPath` (logs WARN) — empty ZIP if all skipped. |
| Create | `cia-finance/.../bulk/BulkDownloadRequest.java` | DTO: `List<Item> items` where `Item = { EntityType type, UUID id }`. Bean validation: `@Size(max=50)` on the list. |
| Create | `cia-finance/.../bulk/BulkPdfDownloadController.java` | `POST /api/v1/finance/pdfs/bulk-download`. `@PreAuthorize("hasRole('FINANCE_VIEW')")`. Returns ResponseEntity<byte[]> with `application/zip` + `Content-Disposition: attachment; filename="cia-pdfs-{yyyy-MM-dd-HHmmss}.zip"`. 400 with errorCode `BULK_DOWNLOAD_TOO_MANY` if list exceeds 50; 400 with `BULK_DOWNLOAD_EMPTY` if list is empty. |
| Modify | `cia-finance/.../email/SendReceiptEmailWorkflow.java` | Add `@SignalMethod void cancel()`. |
| Modify | `cia-finance/.../email/SendReceiptEmailWorkflowImpl.java` | Add `private boolean cancelled` + signal handler. Before invoking the activity, check `if (cancelled) return;`. (No exception thrown — clean termination.) |
| Modify | `cia-finance/.../email/SendPaymentVoucherEmailWorkflow.java` | Mirror — add `@SignalMethod void cancel()`. |
| Modify | `cia-finance/.../email/SendPaymentVoucherEmailWorkflowImpl.java` | Mirror. |
| Modify | `cia-finance/.../ReceiptService.java` | New method `void cancelEmail(UUID receiptId)`. Looks up workflow id by convention `"send-receipt-email-<receiptId>"`, calls `workflowClient.newWorkflowStub(SendReceiptEmailWorkflow.class, workflowId).cancel()`, writes `AuditAction.CANCEL` audit row with `{ workflowId, cancelledBy: currentUser() }`. Throws `EmailPreflightException("WORKFLOW_NOT_FOUND", ...)` if Temporal can't find the workflow (already finished or never started). |
| Modify | `cia-finance/.../PaymentService.java` | Mirror — `cancelEmail(UUID paymentId)`. |
| Modify | `cia-finance/.../ReceiptController.java` | `POST /api/v1/debit-notes/{dnId}/receipts/{id}/email/cancel`. `@PreAuthorize("hasAuthority('FINANCE_UPDATE')")`. Returns 202 Accepted with `{ cancelled: true }`; 404 with errorCode `WORKFLOW_NOT_FOUND` if no in-flight workflow. |
| Modify | `cia-finance/.../PaymentController.java` | Mirror. |
| Create | `cia-finance/.../bulk/PdfDownloadLogRetentionWorkflow.java` + `Impl` | `@WorkflowInterface` with `@WorkflowMethod void purge();` runs weekly. Activity deletes rows >30 days old via the repository. Registers on `EMAIL_QUEUE` (it's a finance background queue; small repurposing is fine — alternatively a new `RETENTION_QUEUE` if we want isolation). |
| Modify | `cia-finance/.../email/EmailWorkerConfig.java` | Register `PdfDownloadLogRetentionWorkflowImpl` + activities on `EMAIL_QUEUE`. Cron schedule via `@Scheduled` or Temporal cron — pick Temporal cron (weekly Sunday 02:00 UTC) so it survives restarts. |

### Backend ITs

| Action | File | Tests |
|---|---|---|
| Create | `cia-api/src/test/.../finance/audit/PdfDownloadLogControllerIT.java` | 4 tests: (a) GET returns rows for today scoped to user; (b) days=7 returns last week's rows; (c) downloading via /pdf adds a row; (d) caller without FINANCE_VIEW gets 403. |
| Create | `cia-api/src/test/.../finance/bulk/BulkPdfDownloadControllerIT.java` | 3 tests: (a) ZIP contains N PDFs with correct filenames; (b) 400 with BULK_DOWNLOAD_TOO_MANY when >50 items; (c) skips items with null pdfPath but still returns 200. |
| Create | `cia-api/src/test/.../finance/email/CancelEmailWorkflowIT.java` | 2 tests: (a) signal flips cancelled flag → workflow exits cleanly without sending; (b) signalling a non-existent workflow throws WORKFLOW_NOT_FOUND. |
| Create | `cia-api/src/test/.../finance/email/CancelEmailControllerIT.java` | 2 tests: (a) POST /email/cancel returns 202 + writes CANCEL audit row; (b) caller without FINANCE_UPDATE → 403. |

### Frontend (new + modified)

| Action | File | Responsibility |
|---|---|---|
| Modify | `cia-frontend/packages/api-client/src/modules/finance.ts` | New types `PdfDownloadLogEntrySchema` + `BulkDownloadItem` + `EmailCancelResponseSchema`. New fetchers: `listRecentDownloads(days)` (validatedList), `bulkDownloadZip(items)` (blob response), `cancelReceiptEmail(dnId, receiptId)` + `cancelPaymentEmail(cnId, paymentId)` (validatedPost). |
| Modify | `cia-frontend/apps/back-office/src/modules/finance/hooks/useReceipts.ts` | New hooks: `useCancelReceiptEmail()` — POST mutation with success/error toast; success invalidates the receipts list query. |
| Modify | `cia-frontend/.../finance/hooks/usePayments.ts` | Mirror — `useCancelPaymentEmail()`. |
| Create | `cia-frontend/.../finance/hooks/useRecentDownloads.ts` | `useRecentDownloads(days = 1)` — useQuery against the new endpoint, 30-second stale time, queryKey `['finance', 'pdf-downloads', days]`. |
| Create | `cia-frontend/.../finance/hooks/useBulkDownloadZip.ts` | Mutation — POSTs item list, expects Blob response, synthesizes filename `cia-pdfs-{ISO timestamp}.zip`, fires anchor click. |
| Create | `cia-frontend/.../finance/components/DownloadIconButton.tsx` | Small icon button (hugeicons `Download04Icon` or similar). Props: `{ type: 'RECEIPT'|'PAYMENT', id, parentId, reference, pdfPath }`. Disabled when `pdfPath === null`. Click → matching download mutation → success → no extra action needed (the backend logs the download server-side). |
| Create | `cia-frontend/.../finance/components/RecentDownloadsPanel.tsx` | Right-edge Sheet. Trigger button in the FinancePage header showing badge with today's count. Renders entries with reference + type icon + recipient name + relative timestamp. Each row has a "Re-download" Button. |
| Create | `cia-frontend/.../finance/pages/BulkEmailSheet.tsx` | Side-Sheet driven by TanStack table selection. Lists selected rows with status badge per row (`Queued` / `Sending…` / `Sent` / `Failed` / `Cancelled`). Fires mutations serially via an async runner. Cancel button visible while any row is `Sending…`; click → fires `cancelEmail` for the current in-flight row + marks all remaining `Queued` rows as `Cancelled` (no further mutations fire). On all-done, shows summary `Sent: N · Failed: M · Cancelled: K` + Close button. |
| Create | `cia-frontend/.../finance/pages/BulkDownloadButton.tsx` | Toolbar button visible when ≥1 row selected AND each selected row has `pdfPath !== null`. Click → `useBulkDownloadZip` mutation with the selected items → browser save. |
| Modify | `cia-frontend/.../finance/pages/receivables/ReceiptsListSection.tsx` | (a) Add row-checkbox column via TanStack `getCanSelect: true`; (b) replace "Download PDF" row action with the inline `<DownloadIconButton>` next to the reference cell; (c) add a header-toolbar slot showing `<BulkEmailSheet trigger>` + `<BulkDownloadButton>` when ≥1 row selected; (d) add `<RecentDownloadsPanel trigger>` next to the status filter. |
| Modify | `cia-frontend/.../finance/pages/payables/PaymentsListSection.tsx` | Mirror. |
| Modify | `cia-frontend/.../finance/pages/receivables/DebitNoteDetailDialog.tsx` | Replace the existing "Download" + "Email" buttons per row with the inline `<DownloadIconButton>` icon (Email stays as it is, since γ already wired it). |
| Modify | `cia-frontend/.../finance/pages/payables/CreditNoteDetailDialog.tsx` | Mirror. |

### Docs

| Action | File | Detail |
|---|---|---|
| Modify | `CLAUDE.md` | Module 8 row gains the F11 paragraph (PdfDownloadLog table + bulk-download ZIP endpoint + cancel-email signals + RecentDownloadsPanel + BulkEmailSheet + BulkDownloadButton on both list pages). New Development Standards bullet for the **PDF download server-side audit pattern** (`pdf_download_log` separate from `audit_log` to keep compliance audits clean) + **Workflow cancellation signal pattern** (best-effort, signal-based, no exception). |
| Modify | `docs-site/static/internal-api.json` | +4 endpoints: GET pdf-downloads, POST bulk-download, 2× POST .../email/cancel. +1 new schema `PdfDownloadLogResponse`. |
| Append | `cia-log.md` | Session 130 (brainstorm + spec). Session 131 (plan + execute). Backlog table: drain F9 + F10; F11 is implicit (the whole slice). |

---

## Data flows

### Download flow (single PDF)

```
Operator clicks DownloadIconButton on a row
  → useDownloadReceiptPdf (or Payment) mutation fires
    → GET /api/v1/debit-notes/{dnId}/receipts/{id}/pdf
      → ReceiptController.downloadPdf
        → service.findOrThrow → check pdf_path → storage.download → stream bytes
        → pdfDownloadLogService.log(RECEIPT, receiptId, ...) [REQUIRES_NEW, swallows on failure]
      → 200 + application/pdf + Content-Disposition: attachment
    → Frontend: blob → URL.createObjectURL → anchor click → save dialog
    → RecentDownloadsPanel's useQuery key is stale-time-throttled to 30s; manual refetch on the panel open will pick up the new row
```

The synchronous log-write happens on the backend; the frontend doesn't need to push anywhere.

### Recent downloads view

```
Operator clicks "Recent (today)" header trigger
  → RecentDownloadsPanel Sheet opens
  → useRecentDownloads(days=1) — useQuery if not cached, manual refetch on open
  → GET /api/v1/finance/pdf-downloads?days=1
  → PdfDownloadLogController.list
    → service.listForUser(currentJwtSub, days, limit=50)
    → repository.findByUserIdAndDownloadedAtAfterOrderByDownloadedAtDesc
  → 200 + list of entries
  → Sheet renders entries; per-row "Re-download" → useDownloadReceiptPdf/Payment mutation
    → (re-download writes another log row — that's correct, two distinct events)
```

### Bulk email flow

```
Operator ticks N checkboxes on the list page
  → toolbar shows "Email N selected" button
  → click → BulkEmailSheet opens with the N selected rows + "Send all" button
  → operator clicks Send all
    → serial runner: for each row in order
      → row.status = 'Sending…'
      → useEmailReceipt/Payment mutation fires
      → on resolve: row.status = 'Sent' or 'Failed'
      → next iteration (no waiting between)
    → on each iteration, check the Cancel state; if set:
      → for currently-Sending row: useCancelReceiptEmail/Payment mutation
      → for all subsequent rows: status = 'Cancelled' (no mutation fired)
      → break the loop
    → on completion: show summary + Close button

Operator clicks Cancel mid-run
  → cancelState.set(true)
  → in-flight mutation may have already POSTed to the workflow — the cancel signal arrives moments later
  → Backend: workflow checks cancelled flag before next activity attempt → exits cleanly + writes CANCEL audit row
```

### Bulk download flow

```
Operator ticks N checkboxes on the list page
  → toolbar shows "Download N as ZIP" button (gated on each row having pdfPath != null)
  → click → useBulkDownloadZip mutation
    → POST /api/v1/finance/pdfs/bulk-download with items: [{type, id}, ...]
    → BulkPdfDownloadController validates size + role
    → PdfZipService walks items, calls storage.download per item, streams into ZipOutputStream
    → returns ZIP bytes
  → Frontend: blob → URL.createObjectURL → anchor click → single save dialog with cia-pdfs-{timestamp}.zip
  → Backend: each download stream call also writes a pdf_download_log row, so the bulk download appears as N rows in the recent panel (matches operator expectation: "I downloaded those 30 receipts at 14:23")
```

---

## Error handling

| Scenario | Behaviour |
|---|---|
| `pdf_download_log` write failure (DB issue) | Internal `@Transactional(REQUIRES_NEW)` rolls back the log write only; the actual download streams successfully. Server logs WARN. |
| Bulk-download > 50 items | 400 + errorCode `BULK_DOWNLOAD_TOO_MANY`. UI gates the button at selection time (disabled with tooltip when count > 50). |
| Bulk-download with all items missing pdf_path | 200 with empty ZIP + server-side WARN. UI behaviour: still saves the empty ZIP (rare edge case). |
| Bulk-download with some items missing pdf_path | Skips those silently; ZIP contains only the resolvable ones. Server WARN per skip. Operator sees fewer files in the ZIP than rows selected — acceptable. |
| Cancel-email on a workflow that already completed | 404 + errorCode `WORKFLOW_NOT_FOUND`. UI swallows (the in-flight row likely flipped to Sent before the cancel landed). |
| Cancel-email signal arrives after the activity already started `emailService.sendEmail(...)` | Best-effort: the send completes, the audit row gets written, the cancel flag is set, the workflow exits BEFORE the next retry would have fired. The operator sees the row as Sent (not Cancelled) for that one — the next bulk-attempt would have been the one to cancel. Documented in workflow Javadoc. |
| Frontend selection cleared on filter / pagination change | TanStack table default behaviour; we don't preserve across pages. Matches operator expectation "what I see is what I selected". |

---

## Testing

| Test | Type | Coverage |
|---|---|---|
| `PdfDownloadLogControllerIT` (4 tests) | Spring `@SpringBootTest` extending `FinanceWebItSupport` | (a) GET returns today's entries scoped to JWT user; (b) days=7 returns last week; (c) GET /pdf adds an entry; (d) 403 without FINANCE_VIEW. |
| `BulkPdfDownloadControllerIT` (3 tests) | Same shape | (a) ZIP contains N PDFs with correct filenames; (b) 400 BULK_DOWNLOAD_TOO_MANY when >50; (c) skips null-pdfPath items but 200s. |
| `CancelEmailWorkflowIT` (2 tests) | Extends the existing `SendReceiptEmailWorkflowIT` pattern (TestWorkflowEnvironment) | (a) Signal flips flag → workflow exits cleanly + CANCEL audit row written; (b) signalling non-existent workflow throws. |
| `CancelEmailControllerIT` (2 tests) | Spring + `FinanceWebItSupport` | (a) 202 + CANCEL audit row exists; (b) 403 without FINANCE_UPDATE. |
| `useRecentDownloads.test.ts` | Vitest unit | useQuery returns server data; refetch fires on demand. (No localStorage mocks — server-side now.) |
| `BulkEmailSheet serial-runner.test.ts` | Vitest unit | Mock 3 mutations: 2 succeed + 1 fails → status badges flip in order; cancel midway → Cancelled status on remaining. |

No new docs-site integration tests; the OpenAPI spec is the contract surface.

---

## Out of scope (genuine — not pulled back in)

- **Server-side bulk-download progress streaming** — backend streams once, frontend sees a single Blob. No progress bar for the ZIP build itself.
- **Cross-tenant bulk download** — items must all belong to the calling JWT's tenant (enforced by Hibernate's MultiTenantConnectionProvider; cross-tenant attempts hit "row not found" → 404).
- **Email-failure auto-retry from the bulk panel** — if a row fails, the operator manually re-selects it for a fresh bulk run. No "retry failed" button in this slice.
- **`pdf_download_log` retention configurable per tenant** — hardcoded 30-day cutoff. If a tenant needs different retention, that's a follow-up backlog item.

---

## Migration version

`V58` — next free after V57 (slice γ).

---

## Spec self-review

**Placeholder scan:** None. Every component named with concrete file paths and method signatures.

**Internal consistency:** Workflow cancellation uses `AuditAction.CANCEL` (already exists in the enum from slice α). The download log uses `@Transactional(REQUIRES_NEW)` matching the existing `AuditService.log(...)` pattern. Frontend hooks layer on top of the same `apiClient` + `validatedPost`/`validatedList` infrastructure as slice γ.

**Scope check:** ~24 tasks. Comparable to F7-γ. Honest about the size in the user-facing brainstorm. Two distinct workstreams (server-side audit + bulk + cancel) but each touches the same set of controllers + the same list-page UI, so the work clusters cleanly.

**Ambiguity check:** Bulk-email cancellation semantics are documented explicitly — best-effort, in-flight workflows finish their current activity attempt. Cancel-on-completed-workflow returns 404 — UI swallows. Bulk-download size cap (50) is hardcoded with a clear errorCode. ZIP filename format is deterministic.

---

## Execution handoff

After user approves this spec, write the implementation plan to `docs/superpowers/plans/2026-05-27-f11-pdf-download-ux-implementation.md` and use the **`superpowers:subagent-driven-development`** sub-skill to execute it.
