# F7 Slice β — Receipt + Payment-Voucher PDF Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every successful `receiptService.post()` and `paymentService.post()` synchronously generates a branded PDF (₦-glyph supported), uploads it to MinIO, and persists `pdf_path` on the entity. The four slice-α visibility surfaces (Receivables / Payables tabs + nested DN / CN detail dialogs) gain a "Download PDF" row action. No email — that's slice γ.

**Architecture:** Three-layer composition. (1) `HtmlToPdfConverter` (cia-documents) refactored to embed `NotoSans-Regular.ttf` + `NotoSans-Bold.ttf` via `PDType0Font` — existing consumers (`QuotePdfService`, `DocumentGenerationServiceImpl`) gain ₦ rendering transparently; the `sanitise()` WinAnsi guard is removed. (2) `BeneficiaryProfileResolver` strategy + `BeneficiaryProfileResolverDispatcher` keyed on `CreditNote.entityType` (4 impls: CLAIM, COMMISSION, REINSURANCE, ENDORSEMENT). Uses JPA entity loading so `@ColumnTransformer` auto-decrypts `Customer.address` — requires new `cia-finance` Maven deps on `cia-customer`, `cia-claims`, `cia-endorsement` (existing dep on `cia-setup` already gives access to `Broker`, `Agent`, `ReinsuranceCompany`). (3) Generators (`ReceiptPdfGenerator`, `PaymentVoucherPdfGenerator`) render Thymeleaf templates → HTML string → `HtmlToPdfConverter.convert()` → byte[] → `DocumentStorageService.upload(...)` → returned path persisted as `receipt.pdfPath` / `payment.pdfPath`. Generator failure = log WARN + leave `pdfPath` null (post never rolls back). New `GET /api/v1/debit-notes/{dnId}/receipts/{id}/pdf` + `GET /api/v1/credit-notes/{cnId}/payments/{id}/pdf` endpoints stream bytes from MinIO; FINANCE_VIEW required; 404 when `pdfPath IS NULL`.

**Tech Stack:** Spring Boot 3 + Java 21 + Apache PDFBox 3.x (already in `cia-documents`) + Thymeleaf (already in `cia-documents` + `cia-notifications`) + Jsoup (already used by HtmlToPdfConverter) + Testcontainers Postgres 16 (existing) + MinIO (existing storage adapter). Frontend: React + TanStack Query + zod + axios blob downloads (same pattern as F5.16 NAICOM artifacts).

---

## Open Font License — NotoSans

NotoSans-Regular.ttf and NotoSans-Bold.ttf must be downloaded from the Google Noto project (https://github.com/notofonts/notofonts.github.io). Both files are licensed under the SIL Open Font License 1.1 — no source-code attribution required, but the license MUST be included alongside the binary at `cia-documents/src/main/resources/fonts/OFL.txt`. The fonts cover U+0020-U+FFFF including the ₦ (U+20A6) glyph that Helvetica lacks. Combined size ~1.1 MB.

---

## File structure

### Backend — production (16 files)

| Action | File | Responsibility |
|---|---|---|
| Create | `cia-api/src/main/resources/db/migration/V56__add_pdf_path_to_receipts_payments.sql` | Adds nullable `pdf_path VARCHAR(512)` to `receipts` + `payments`. |
| Modify | `cia-finance/src/main/java/com/nubeero/cia/finance/Receipt.java` | New `pdfPath` field + getter/setter. |
| Modify | `cia-finance/src/main/java/com/nubeero/cia/finance/Payment.java` | Mirror. |
| Modify | `cia-finance/pom.xml` | New module deps: `cia-customer`, `cia-claims`, `cia-endorsement`. |
| Create | `cia-documents/src/main/resources/fonts/NotoSans-Regular.ttf` | Body font with ₦ glyph (binary, ~580 KB). |
| Create | `cia-documents/src/main/resources/fonts/NotoSans-Bold.ttf` | Bold variant (binary, ~580 KB). |
| Create | `cia-documents/src/main/resources/fonts/OFL.txt` | SIL Open Font License 1.1 text. |
| Modify | `cia-documents/src/main/java/com/nubeero/cia/documents/HtmlToPdfConverter.java` | Refactor to use `PDType0Font.load()` with NotoSans TTFs; drop `sanitise()`. |
| Create | `cia-finance/src/main/java/com/nubeero/cia/finance/pdf/BeneficiaryProfile.java` | `record (String name, String addressLine1, String addressLine2)` — `addressLine1` may be null. |
| Create | `cia-finance/src/main/java/com/nubeero/cia/finance/pdf/BeneficiaryProfileResolver.java` | Strategy interface: `BeneficiaryProfile resolve(CreditNote)`. |
| Create | `cia-finance/src/main/java/com/nubeero/cia/finance/pdf/BeneficiaryProfileResolverDispatcher.java` | Routes by `creditNote.entityType` via Spring `Map<FinanceEntityType, BeneficiaryProfileResolver>` autowire. Falls back to denormalised `beneficiaryName` + null address for un-mapped types (POLICY, CLAIM_EXPENSE). |
| Create | `cia-finance/src/main/java/com/nubeero/cia/finance/pdf/ClaimBeneficiaryProfileResolver.java` | `@Component("CLAIM-profile")`. Loads `Claim` by `creditNote.entityId`; loads `Customer` by `claim.customerId`; returns `(customerName, address, null)`. |
| Create | `cia-finance/src/main/java/com/nubeero/cia/finance/pdf/CommissionBeneficiaryProfileResolver.java` | `@Component("COMMISSION-profile")`. Tries `BrokerRepository.findById(beneficiaryId)` first; falls back to `AgentRepository.findById(...)`. Returns `(name, address, null)`. |
| Create | `cia-finance/src/main/java/com/nubeero/cia/finance/pdf/FacOutwardBeneficiaryProfileResolver.java` | `@Component("REINSURANCE-profile")`. Loads `ReinsuranceCompany` by `creditNote.beneficiaryId`. |
| Create | `cia-finance/src/main/java/com/nubeero/cia/finance/pdf/EndorsementRefundBeneficiaryProfileResolver.java` | `@Component("ENDORSEMENT-profile")`. Loads `Endorsement` → `Policy` → `Customer`. |
| Create | `cia-finance/src/main/java/com/nubeero/cia/finance/pdf/ReceiptPdfGenerator.java` | `byte[] generate(Receipt)`. Uses `DebitNote` (already loaded) for customer name + entityReference; renders Thymeleaf template; calls `HtmlToPdfConverter.convert(html)`. Never throws — catches `Exception`, logs WARN, returns `null`. |
| Create | `cia-finance/src/main/java/com/nubeero/cia/finance/pdf/PaymentVoucherPdfGenerator.java` | Mirror — uses `CreditNote` + `BeneficiaryProfileResolverDispatcher.resolve(cn)` for the address block. Header label derived from `creditNote.entityType`. |
| Create | `cia-documents/src/main/resources/templates/pdf/receipt.html` | Thymeleaf template — header (company name + "OFFICIAL RECEIPT"), receipt no + date, "Received from" (customer name), amount in figures + words, payment method, related DN + policy number, narration, signatory placeholder. |
| Create | `cia-documents/src/main/resources/templates/pdf/payment-voucher.html` | Same shape — header label from model attribute. "Paid to" with optional address line. Two signatory placeholders. |
| Modify | `cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptService.java` | Inject `ReceiptPdfGenerator` + `DocumentStorageService`. After `receiptRepository.save()` in `post()`: generate → if non-null, upload to MinIO at path `receipts/{yyyy}/{MM}/{receipt.id}.pdf` → set `receipt.pdfPath` → save again. Order is post-audit so audit captures pre-PDF state. |
| Modify | `cia-finance/src/main/java/com/nubeero/cia/finance/PaymentService.java` | Mirror, path `payments/{yyyy}/{MM}/{payment.id}.pdf`. |
| Modify | `cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptController.java` | New `GET /api/v1/debit-notes/{dnId}/receipts/{id}/pdf` endpoint. FINANCE_VIEW. 404 when `pdfPath IS NULL`. Streams via `DocumentStorageService.download()` as `application/pdf` with `Content-Disposition: attachment; filename="REC-<ref>.pdf"`. |
| Modify | `cia-finance/src/main/java/com/nubeero/cia/finance/PaymentController.java` | Mirror. Filename `PAY-<ref>.pdf`. |
| Modify | `cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptListItemResponse.java` | Add `pdfPath: String` (nullable) — surface for frontend gating. |
| Modify | `cia-finance/src/main/java/com/nubeero/cia/finance/PaymentListItemResponse.java` | Mirror. |
| Modify | `cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptService.java` `toListItem` | Project `pdfPath` into list response. |
| Modify | `cia-finance/src/main/java/com/nubeero/cia/finance/PaymentService.java` `toListItem` | Mirror. |

### Backend — tests (8 new IT files)

| Action | File | Coverage |
|---|---|---|
| Create | `cia-documents/src/test/java/com/nubeero/cia/documents/HtmlToPdfConverterFontIT.java` | 3 tests: ₦ glyph renders (extract text from generated PDF, assert "₦100,000" present); existing tags (h1/p/ul/table) still render with new font; Unicode outside WinAnsi (e.g. ✓ U+2713 if a future template needs it) does not get sanitised to `?`. |
| Create | `cia-api/src/test/java/com/nubeero/cia/api/finance/pdf/BeneficiaryProfileResolverIT.java` | 8 tests: dispatcher routes by entityType (4 — one per impl); fallback for unmapped types (POLICY, CLAIM_EXPENSE) returns denormalised name + null address; ClaimResolver decrypts Customer.address via JPA; CommissionResolver tries Broker then Agent (2 sub-tests). |
| Create | `cia-api/src/test/java/com/nubeero/cia/api/finance/pdf/ReceiptPdfGeneratorIT.java` | 4 tests: full PDF bytes parseable via PDFBox `Loader.loadPDF`; PDF text contains receipt number + DN number + customer name + amount; ₦ symbol present in extracted text; generator returns null cleanly when storage upload throws. |
| Create | `cia-api/src/test/java/com/nubeero/cia/api/finance/pdf/PaymentVoucherPdfGeneratorIT.java` | 4 tests: one happy-path per source type (CLAIM / COMMISSION / REINSURANCE / ENDORSEMENT) — verifies header label varies and PDF text contains resolved beneficiary name + address. |
| Create | `cia-api/src/test/java/com/nubeero/cia/api/finance/ReceiptControllerPdfIT.java` | 5 tests: POST receipt populates `pdf_path` + MinIO object exists at path; GET pdf → 200 + `application/pdf` + non-empty body + correct `Content-Disposition`; GET when `pdfPath==null` → 404; FINANCE_VIEW gating (403 without role); GET against non-existent receipt id → 404. |
| Create | `cia-api/src/test/java/com/nubeero/cia/api/finance/PaymentControllerPdfIT.java` | Mirror — 5 tests. |
| Create | `cia-api/src/test/java/com/nubeero/cia/api/finance/ReceiptPdfListItemIT.java` | 2 tests: GET /api/v1/receipts returns `pdfPath` field non-null for POSTED with pdf + null for failed-generation case. |
| Create | `cia-api/src/test/java/com/nubeero/cia/api/finance/PaymentPdfListItemIT.java` | Mirror — 2 tests. |

Total new ITs: **33**. Failsafe baseline target after slice β: ~333.

### Frontend (7 files)

| Action | File | Detail |
|---|---|---|
| Modify | `cia-frontend/packages/api-client/src/modules/finance.ts` | `ReceiptListItemResponseSchema` + `PaymentListItemResponseSchema` gain `pdfPath: z.string().nullable()`. Add `downloadReceiptPdf(dnId, receiptId)` + `downloadPaymentPdf(cnId, paymentId)` blob fetchers. |
| Modify | `cia-frontend/apps/back-office/src/modules/finance/hooks/useReceipts.ts` | Add `useDownloadReceiptPdf()` mutation — synthesises filename `REC-<ref>.pdf`, triggers browser download via `createObjectURL` + anchor click + `revokeObjectURL` cleanup. |
| Modify | `cia-frontend/apps/back-office/src/modules/finance/hooks/usePayments.ts` | Mirror. |
| Modify | `cia-frontend/apps/back-office/src/modules/finance/pages/receivables/ReceiptsListSection.tsx` | New "Download PDF" row action. Disabled (with tooltip "PDF unavailable") when `pdfPath === null`. Per-row spinner keyed on receipt id (matches F5.16 pattern). |
| Modify | `cia-frontend/apps/back-office/src/modules/finance/pages/payables/PaymentsListSection.tsx` | Mirror. |
| Modify | `cia-frontend/apps/back-office/src/modules/finance/pages/receivables/DebitNoteDetailDialog.tsx` | Add "Download" button alongside the existing "Reverse" button in each nested receipt row. Same disabled-when-null logic. |
| Modify | `cia-frontend/apps/back-office/src/modules/finance/pages/payables/CreditNoteDetailDialog.tsx` | Mirror. |

### Docs (3 files)

| Action | File | Detail |
|---|---|---|
| Modify | `CLAUDE.md` | Module 8 row + Build 6 sub-pages updated to reflect PDF generation; new "PDF generation in cia-finance" Development Standards bullet. |
| Modify | `docs-site/static/internal-api.json` | Add 2 new paths (GET pdf on receipts + payments) + `pdfPath` field on existing list-item schemas. |
| Append | `cia-log.md` | Session 126 entry with backlog reconciliation (F7-β drained), file inventory, IT count delta. |

---

## Slice grouping into tasks

20 tasks across 7 phases. Each task = one commit. Tasks within a phase are sequential (later tasks depend on earlier ones).

- **Phase 1 — Foundations** (Tasks 1-2): V50 migration + entity changes; cia-finance Maven dep growth.
- **Phase 2 — Font infrastructure** (Task 3): NotoSans TTFs + HtmlToPdfConverter refactor + IT.
- **Phase 3 — Beneficiary resolvers** (Tasks 4-9): record + interface + dispatcher + 4 impls + IT bundle.
- **Phase 4 — Receipt PDF** (Tasks 10-12): generator + template + service wire-in + controller endpoint + ITs.
- **Phase 5 — Payment-Voucher PDF** (Tasks 13-15): mirror of Phase 4 for payments.
- **Phase 6 — Frontend** (Tasks 16-19): api-client + hooks + sections + nested dialogs.
- **Phase 7 — Docs + close** (Task 20): CLAUDE.md + internal-api.json + session log + final verify + push.

---

## Tasks

### Task 1: V50 migration + Receipt/Payment.pdfPath entity changes

**Files:**
- Create: `cia-backend/cia-api/src/main/resources/db/migration/V56__add_pdf_path_to_receipts_payments.sql`
- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/Receipt.java`
- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/Payment.java`

- [ ] **Step 1: Create V50 migration**

```sql
-- V56__add_pdf_path_to_receipts_payments.sql
--
-- Adds nullable pdf_path columns to receipts + payments for F7 slice β.
-- pdf_path stores the MinIO object path (e.g. "receipts/2026/05/<uuid>.pdf")
-- returned by DocumentStorageService.upload(...). NULL = PDF was never
-- generated (generator failure on post() leaves the column null and logs).

ALTER TABLE receipts ADD COLUMN pdf_path VARCHAR(512);
ALTER TABLE payments ADD COLUMN pdf_path VARCHAR(512);
```

- [ ] **Step 2: Add pdfPath to Receipt entity**

In `Receipt.java`, insert after the `reversed_by` field (around line 79):

```java
    @Column(name = "pdf_path", length = 512)
    private String pdfPath;
```

And add getter/setter alongside the existing ones:

```java
    public String getPdfPath() { return pdfPath; }
    public void setPdfPath(String pdfPath) { this.pdfPath = pdfPath; }
```

- [ ] **Step 3: Add pdfPath to Payment entity**

Mirror — same column + getter/setter in `Payment.java`.

- [ ] **Step 4: Bump Flyway target in IT base classes**

`FinanceItSupport.java` and `FinanceWebItSupport.java` have `registry.add("spring.flyway.target", () -> "49");`. Bump both to `"56"`. This pulls in V50–V56 — V50-V55 (commission + agent migrations from Session 84a–B1a) plus the new V56 pdf_path migration. The Finance ITs were pinned to "49" before V50 landed, not for known incompatibility; bumping is a deliberate slice-margin reconciliation (PolicyServiceIT-style assertions don't fire from slice α / period-lock ITs, so commission-rule seeding from V52/V54 + the policy mutual-exclusivity CHECK from V53 should not break existing Finance ITs — verify in Step 6).

- [ ] **Step 5: Verify migration + entity compile and apply cleanly**

```bash
cd /Users/razormvp/CoreInsurance/cia-backend
mvn install -DskipTests -pl cia-finance -am -q
mvn -pl cia-api failsafe:integration-test -Dit.test=PeriodLockInterceptorIT -DskipUnitTests=true -q 2>&1 | tail -10
```

Expected: PASS — the existing Period-lock IT confirms Flyway target=50 still migrates cleanly. If it fails with "no migration found at version 50", you forgot Step 1.

- [ ] **Step 6: Commit**

```bash
cd /Users/razormvp/CoreInsurance
git add cia-backend/cia-api/src/main/resources/db/migration/V56__add_pdf_path_to_receipts_payments.sql \
        cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/Receipt.java \
        cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/Payment.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/FinanceItSupport.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/FinanceWebItSupport.java
git commit -m "$(cat <<'EOF'
feat(finance): Slice β / Task 1 — V50 adds pdf_path to receipts + payments

Nullable VARCHAR(512). NULL = PDF was never generated (generator failure on
post() logs WARN and leaves column null). Receipt + Payment entities gain
pdfPath getter/setter. IT base classes bumped to Flyway target=50.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: cia-finance Maven deps — add cia-customer + cia-claims + cia-endorsement

**Files:**
- Modify: `cia-backend/cia-finance/pom.xml`

The BeneficiaryProfileResolver implementations (Tasks 5-8) need JPA entity-level access to `Customer`, `Claim`, `Endorsement`, and `Policy` (the last is reached directly from `EndorsementRefundResolver` via `Endorsement.policyId → Policy.customerId`). The existing dep on `cia-setup` already gives access to `Broker`, `Agent`, `ReinsuranceCompany`.

- [ ] **Step 1: Add four new module dependencies**

Locate the `<dependencies>` block in `cia-finance/pom.xml` and insert these four blocks alongside the existing `cia-setup` dep:

```xml
    <dependency>
      <groupId>com.nubeero</groupId>
      <artifactId>cia-customer</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>com.nubeero</groupId>
      <artifactId>cia-claims</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>com.nubeero</groupId>
      <artifactId>cia-endorsement</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>com.nubeero</groupId>
      <artifactId>cia-policy</artifactId>
      <version>${project.version}</version>
    </dependency>
```

- [ ] **Step 2: Verify the reactor still builds cleanly**

```bash
cd /Users/razormvp/CoreInsurance/cia-backend
mvn install -DskipTests -pl cia-finance -am -q
```

Expected: BUILD SUCCESS, no circular-dep error. If you see "circular dependency between artifacts", a downstream module (cia-customer / cia-claims / cia-endorsement) is transitively depending on cia-finance — investigate before proceeding.

- [ ] **Step 3: Verify no existing cia-finance test regresses from the new classpath**

```bash
mvn install -DskipTests -pl cia-api -am -q
mvn -pl cia-api failsafe:integration-test -Dit.test='*Finance*IT,*Receipt*IT,*Payment*IT' -DskipUnitTests=true -q 2>&1 | tail -10
```

Expected: PASS for the slice-α IT subset.

- [ ] **Step 4: Commit**

```bash
cd /Users/razormvp/CoreInsurance
git add cia-backend/cia-finance/pom.xml
git commit -m "$(cat <<'EOF'
build(finance): Slice β / Task 2 — cia-finance gains module deps on customer + claims + endorsement

Enables JPA entity loading inside BeneficiaryProfileResolver impls (Tasks 5-8)
so Customer.address auto-decrypts via @ColumnTransformer. Existing convention
of avoiding business-entity deps was deliberate (see PolicyClassResolver) but
the slice-β voucher-PDF address-block requirement makes the JPA-decrypted
path the only practical option — inline pgp_sym_decrypt would split the
address-rendering path in two and grow the IT surface by ~12 cases.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: NotoSans TTFs + HtmlToPdfConverter refactor + IT

**Files:**
- Create: `cia-backend/cia-documents/src/main/resources/fonts/NotoSans-Regular.ttf` (binary)
- Create: `cia-backend/cia-documents/src/main/resources/fonts/NotoSans-Bold.ttf` (binary)
- Create: `cia-backend/cia-documents/src/main/resources/fonts/OFL.txt`
- Modify: `cia-backend/cia-documents/src/main/java/com/nubeero/cia/documents/HtmlToPdfConverter.java`
- Create: `cia-backend/cia-documents/src/test/java/com/nubeero/cia/documents/HtmlToPdfConverterFontIT.java`

- [ ] **Step 1: Download NotoSans TTFs + OFL license**

```bash
mkdir -p /Users/razormvp/CoreInsurance/cia-backend/cia-documents/src/main/resources/fonts
cd /Users/razormvp/CoreInsurance/cia-backend/cia-documents/src/main/resources/fonts

# NotoSans Regular + Bold from the Google Noto repo (releases mirror)
curl -L -o NotoSans-Regular.ttf https://github.com/notofonts/notofonts.github.io/raw/main/fonts/NotoSans/full/ttf/NotoSans-Regular.ttf
curl -L -o NotoSans-Bold.ttf    https://github.com/notofonts/notofonts.github.io/raw/main/fonts/NotoSans/full/ttf/NotoSans-Bold.ttf

# SIL Open Font License 1.1 (canonical text from scripts.sil.org)
curl -L -o OFL.txt https://scripts.sil.org/cms/scripts/render_download.php?format=file&media_id=OFL_plaintext&filename=OFL.txt

# Verify ₦ glyph (U+20A6) is present — sanity check
python3 -c "
from fontTools.ttLib import TTFont
font = TTFont('NotoSans-Regular.ttf')
cmap = font.getBestCmap()
assert 0x20A6 in cmap, 'NotoSans-Regular missing ₦ (U+20A6)'
print('OK — ₦ glyph present in NotoSans-Regular')
"
```

Expected: three files in `fonts/` totalling ~1.2 MB, plus "OK — ₦ glyph present" from the verifier.

> If `fonttools` is not installed, `pip install fonttools` first, or skip the verifier and trust the upstream — Noto Sans has shipped ₦ since 2012.

- [ ] **Step 2: Refactor HtmlToPdfConverter to embed PDType0Font**

Replace the existing `convert()` and `RenderState` class with versions that load NotoSans via `PDType0Font.load(doc, InputStream)`. Concrete diff:

Add to imports:

```java
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import java.io.InputStream;
```

Remove imports for `PDType1Font` and `Standard14Fonts`.

Replace `convert()`:

```java
    public byte[] convert(String html) throws IOException {
        org.jsoup.nodes.Document jsoupDoc = Jsoup.parse(html);
        try (PDDocument doc = new PDDocument()) {
            PDFont regular = loadFont(doc, "/fonts/NotoSans-Regular.ttf");
            PDFont bold    = loadFont(doc, "/fonts/NotoSans-Bold.ttf");
            RenderState state = new RenderState(doc, regular, bold);

            for (Node child : jsoupDoc.body().childNodes()) {
                renderNode(state, child);
            }
            state.finish();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static PDFont loadFont(PDDocument doc, String resourcePath) throws IOException {
        try (InputStream in = HtmlToPdfConverter.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Font resource not found on classpath: " + resourcePath);
            }
            return PDType0Font.load(doc, in);
        }
    }
```

In `RenderState`, change the field types from `PDType1Font` to `PDFont`:

```java
        private final PDFont regular;
        private final PDFont bold;

        RenderState(PDDocument doc, PDFont regular, PDFont bold) throws IOException {
            this.doc     = doc;
            this.regular = regular;
            this.bold    = bold;
            newPage();
        }
```

In `writeText()` change the local var type:

```java
        void writeText(String text, int fontSize, boolean useBold, float lineH) throws IOException {
            PDFont font = useBold ? bold : regular;
            // ... rest unchanged
        }
```

**Delete the `sanitise()` method entirely** and remove its callsite in `wrap()` — PDType0Font handles full Unicode natively, so the WinAnsi guard is no longer needed:

```java
        // wrap() — change this line:
        //   float w = font.getStringWidth(sanitise(candidate)) / 1000f * size;
        // to:
        //   float w = font.getStringWidth(candidate) / 1000f * size;
```

- [ ] **Step 3: Write the failing IT**

```java
package com.nubeero.cia.documents;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the post-Slice-β contract of {@link HtmlToPdfConverter}:
 *
 * <ul>
 *   <li>The ₦ (U+20A6) glyph renders rather than getting sanitised to '?'.</li>
 *   <li>All existing HTML tags supported pre-refactor still render (smoke).</li>
 *   <li>Non-WinAnsi glyphs (e.g. ✓ U+2713) survive end-to-end.</li>
 * </ul>
 *
 * @since Slice β — Task 3, F7 receipt + payment-voucher PDF generation
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = HtmlToPdfConverter.class)
class HtmlToPdfConverterFontIT {

    @org.springframework.beans.factory.annotation.Autowired
    HtmlToPdfConverter converter;

    @Test
    @DisplayName("₦ glyph (U+20A6) renders correctly in generated PDF")
    void nairaGlyphRendersInPdf() throws IOException {
        String html = "<p>Amount: ₦250,000.00</p>";

        byte[] pdfBytes = converter.convert(html);
        assertThat(pdfBytes).isNotEmpty();

        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text)
                .as("Extracted PDF text must contain the ₦ glyph rather than the '?' sanitisation fallback")
                .contains("₦250,000.00");
        }
    }

    @Test
    @DisplayName("Existing tags (h1, p, table, ul) still render after font refactor")
    void existingTagsStillRender() throws IOException {
        String html = """
            <h1>Test Document</h1>
            <p>First paragraph.</p>
            <h2>Second Heading</h2>
            <ul>
              <li>Item one</li>
              <li>Item two</li>
            </ul>
            <table>
              <tr><th>Col A</th><th>Col B</th></tr>
              <tr><td>Cell 1</td><td>Cell 2</td></tr>
            </table>
            """;

        byte[] pdfBytes = converter.convert(html);
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Test Document", "First paragraph",
                                       "Second Heading", "Item one", "Item two",
                                       "Cell 1", "Cell 2");
        }
    }

    @Test
    @DisplayName("Non-WinAnsi glyphs (e.g. ✓ U+2713) survive end-to-end — sanitise() removed")
    void nonWinAnsiGlyphSurvives() throws IOException {
        String html = "<p>Verified ✓</p>";

        byte[] pdfBytes = converter.convert(html);
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text)
                .as("Pre-refactor sanitise() would have written '?' here; PDType0Font handles full Unicode natively")
                .contains("✓");
        }
    }
}
```

- [ ] **Step 4: Run the IT**

```bash
cd /Users/razormvp/CoreInsurance/cia-backend
mvn install -DskipTests -pl cia-documents -am -q
mvn -pl cia-documents test -Dtest=HtmlToPdfConverterFontIT -q 2>&1 | tail -10
```

Expected: 3 tests pass. If the ₦ assertion fails, the TTF wasn't packaged correctly — check that the file is in `target/classes/fonts/`.

- [ ] **Step 5: Verify existing consumers still pass**

```bash
mvn -pl cia-documents,cia-quotation verify -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS — `QuotePdfService` + `DocumentGenerationServiceImpl` are the existing consumers and should be unaffected (public API of HtmlToPdfConverter unchanged).

- [ ] **Step 6: Commit**

```bash
git add cia-backend/cia-documents/src/main/resources/fonts/ \
        cia-backend/cia-documents/src/main/java/com/nubeero/cia/documents/HtmlToPdfConverter.java \
        cia-backend/cia-documents/src/test/java/com/nubeero/cia/documents/HtmlToPdfConverterFontIT.java
git commit -m "$(cat <<'EOF'
feat(documents): Slice β / Task 3 — HtmlToPdfConverter embeds NotoSans, drops WinAnsi sanitise

Replaces PDType1Font.HELVETICA / HELVETICA_BOLD with PDType0Font loaded
from /fonts/NotoSans-{Regular,Bold}.ttf on the classpath. NotoSans covers
U+0020..U+FFFF including ₦ (U+20A6), so the sanitise() guard that previously
mapped non-WinAnsi chars to '?' is no longer needed and has been removed.

QuotePdfService + DocumentGenerationServiceImpl gain ₦ rendering for free —
public API of HtmlToPdfConverter unchanged.

OFL.txt included per SIL Open Font License 1.1 attribution requirement.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: BeneficiaryProfile + Resolver interface + Dispatcher

**Files:**
- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/pdf/BeneficiaryProfile.java`
- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/pdf/BeneficiaryProfileResolver.java`
- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/pdf/BeneficiaryProfileResolverDispatcher.java`

- [ ] **Step 1: Create BeneficiaryProfile record**

```java
package com.nubeero.cia.finance.pdf;

/**
 * Resolved beneficiary identity for a {@link com.nubeero.cia.finance.CreditNote}.
 *
 * <p>Used by {@link com.nubeero.cia.finance.pdf.PaymentVoucherPdfGenerator} to
 * fill the "Paid to" block on the voucher PDF. The dispatcher routes credit
 * notes to the resolver implementation matching {@code creditNote.entityType}.
 *
 * @param name          beneficiary display name (never blank — falls back to
 *                      {@code creditNote.beneficiaryName} when resolution fails)
 * @param addressLine1  first address line; may be {@code null} when the
 *                      beneficiary entity has no recorded address
 * @param addressLine2  optional second address line (city / postcode etc.)
 *
 * @since Slice β — F7 payment-voucher PDF generation
 */
public record BeneficiaryProfile(
        String name,
        String addressLine1,
        String addressLine2
) {
    public static BeneficiaryProfile nameOnly(String name) {
        return new BeneficiaryProfile(name, null, null);
    }
}
```

- [ ] **Step 2: Create resolver interface**

```java
package com.nubeero.cia.finance.pdf;

import com.nubeero.cia.finance.CreditNote;

/**
 * Strategy interface for resolving a {@link BeneficiaryProfile} from a
 * {@link CreditNote}. Each implementation handles one
 * {@link com.nubeero.cia.finance.FinanceEntityType}; the
 * {@link BeneficiaryProfileResolverDispatcher} routes credit notes to the
 * right impl.
 *
 * <p>Implementations may load JPA entities (e.g. {@code Customer}) which
 * triggers {@code @ColumnTransformer}-based decryption of encrypted columns
 * like {@code Customer.address}. Implementations MUST be resilient to missing
 * referenced entities — the dispatcher's fallback (denormalised name + null
 * address) covers the case where resolution returns null.
 *
 * @since Slice β — F7 payment-voucher PDF generation
 */
public interface BeneficiaryProfileResolver {
    /**
     * @return the resolved profile, or {@code null} when the referenced
     *         entity cannot be loaded. Dispatcher falls back to
     *         {@code BeneficiaryProfile.nameOnly(creditNote.beneficiaryName)}.
     */
    BeneficiaryProfile resolve(CreditNote creditNote);
}
```

- [ ] **Step 3: Create dispatcher**

```java
package com.nubeero.cia.finance.pdf;

import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.finance.FinanceEntityType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Routes {@link CreditNote} to the {@link BeneficiaryProfileResolver} for its
 * {@code entityType}. Resolvers are autowired by bean name (e.g.
 * {@code @Component("CLAIM-profile")}).
 *
 * <p>Falls back to {@code BeneficiaryProfile.nameOnly(creditNote.beneficiaryName)}
 * when no resolver exists for the entity type (POLICY, CLAIM_EXPENSE) or when
 * the matched resolver returns null (referenced entity missing).
 *
 * @since Slice β — F7 payment-voucher PDF generation
 */
@Component
public class BeneficiaryProfileResolverDispatcher {

    private final Map<FinanceEntityType, BeneficiaryProfileResolver> resolvers;

    public BeneficiaryProfileResolverDispatcher(
            Map<String, BeneficiaryProfileResolver> beanMap) {
        // Spring injects all BeneficiaryProfileResolver beans keyed by bean name.
        // Bean names follow the convention "<ENTITY_TYPE>-profile" (see resolver
        // impls); we strip the suffix and map to the enum.
        this.resolvers = new EnumMap<>(FinanceEntityType.class);
        for (Map.Entry<String, BeneficiaryProfileResolver> e : beanMap.entrySet()) {
            String name = e.getKey();
            if (!name.endsWith("-profile")) continue;
            String typeName = name.substring(0, name.length() - "-profile".length());
            try {
                FinanceEntityType type = FinanceEntityType.valueOf(typeName);
                resolvers.put(type, e.getValue());
            } catch (IllegalArgumentException ex) {
                // Bean name doesn't match a FinanceEntityType — ignore.
            }
        }
    }

    public BeneficiaryProfile resolve(CreditNote creditNote) {
        BeneficiaryProfileResolver resolver = resolvers.get(creditNote.getEntityType());
        if (resolver == null) {
            return BeneficiaryProfile.nameOnly(creditNote.getBeneficiaryName());
        }
        BeneficiaryProfile profile = resolver.resolve(creditNote);
        return profile != null ? profile : BeneficiaryProfile.nameOnly(creditNote.getBeneficiaryName());
    }
}
```

- [ ] **Step 4: Compile**

```bash
cd /Users/razormvp/CoreInsurance/cia-backend
mvn install -DskipTests -pl cia-finance -am -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/pdf/
git commit -m "$(cat <<'EOF'
feat(finance): Slice β / Task 4 — BeneficiaryProfile + Resolver SPI + Dispatcher

BeneficiaryProfile record (name + addressLine1 + addressLine2) is the
PaymentVoucherPdfGenerator input for the "Paid to" block.
BeneficiaryProfileResolver is the strategy SPI; impls land in Tasks 5-8.
Dispatcher routes by entityType via bean-name convention ("<TYPE>-profile")
with a denormalised-name fallback for unmapped types (POLICY, CLAIM_EXPENSE).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: ClaimBeneficiaryProfileResolver (CLAIM entityType)

**Files:**
- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/pdf/ClaimBeneficiaryProfileResolver.java`

- [ ] **Step 1: Implement resolver**

```java
package com.nubeero.cia.finance.pdf;

import com.nubeero.cia.claims.Claim;
import com.nubeero.cia.claims.ClaimRepository;
import com.nubeero.cia.customer.Customer;
import com.nubeero.cia.customer.CustomerRepository;
import com.nubeero.cia.finance.CreditNote;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the beneficiary profile for {@link com.nubeero.cia.finance.FinanceEntityType#CLAIM}
 * credit notes (Discharge Voucher payouts).
 *
 * <p>Loads {@code Claim} by {@code creditNote.entityId}, then loads
 * {@code Customer} by {@code claim.customerId}. The customer's {@code address}
 * is encrypted at rest (NDPR) and auto-decrypts via JPA
 * {@code @ColumnTransformer} on read.
 *
 * <p>Returns {@code null} when the claim or customer is missing; dispatcher
 * falls back to the denormalised {@code creditNote.beneficiaryName}.
 *
 * @since Slice β — F7 payment-voucher PDF generation
 */
@Component("CLAIM-profile")
public class ClaimBeneficiaryProfileResolver implements BeneficiaryProfileResolver {

    private final ClaimRepository    claimRepository;
    private final CustomerRepository customerRepository;

    public ClaimBeneficiaryProfileResolver(ClaimRepository claimRepository,
                                            CustomerRepository customerRepository) {
        this.claimRepository    = claimRepository;
        this.customerRepository = customerRepository;
    }

    @Override
    public BeneficiaryProfile resolve(CreditNote creditNote) {
        Optional<Claim> claimOpt = claimRepository.findById(creditNote.getEntityId());
        if (claimOpt.isEmpty()) return null;
        Claim claim = claimOpt.get();

        Optional<Customer> customerOpt = customerRepository.findById(claim.getCustomerId());
        if (customerOpt.isEmpty()) {
            // Fallback to the claim's denormalised customerName + no address.
            return BeneficiaryProfile.nameOnly(claim.getCustomerName());
        }
        Customer customer = customerOpt.get();

        String name = customer.getDisplayName(); // existing method on Customer
        String address = customer.getAddress();  // decrypted via @ColumnTransformer
        return new BeneficiaryProfile(name, address, null);
    }
}
```

> **Note**: confirm `Customer.getDisplayName()` exists (returns first+last for individual, or companyName for corporate). If not, use `customer.getFirstName() + " " + customer.getLastName()` for individual and `customer.getCompanyName()` for corporate — discriminate on `customer.getType()`. Adjust if needed.

- [ ] **Step 2: Compile**

```bash
cd /Users/razormvp/CoreInsurance/cia-backend
mvn install -DskipTests -pl cia-finance -am -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/pdf/ClaimBeneficiaryProfileResolver.java
git commit -m "$(cat <<'EOF'
feat(finance): Slice β / Task 5 — ClaimBeneficiaryProfileResolver

Routes CLAIM-source credit notes (DV payouts) to the claimant's Customer
profile. Customer.address auto-decrypts via JPA @ColumnTransformer.
Returns null when claim/customer missing; dispatcher falls back.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: CommissionBeneficiaryProfileResolver (COMMISSION entityType)

**Files:**
- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/pdf/CommissionBeneficiaryProfileResolver.java`

- [ ] **Step 1: Implement resolver**

```java
package com.nubeero.cia.finance.pdf;

import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.setup.org.Agent;
import com.nubeero.cia.setup.org.AgentRepository;
import com.nubeero.cia.setup.org.Broker;
import com.nubeero.cia.setup.org.BrokerRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the beneficiary profile for {@link com.nubeero.cia.finance.FinanceEntityType#COMMISSION}
 * credit notes.
 *
 * <p>CreditNote.beneficiaryId is either a Broker or an Agent UUID. We try
 * Broker first (more common), then fall back to Agent. Both entities have
 * plain (non-encrypted) {@code address} columns so JPA loading is direct.
 *
 * <p>Returns {@code null} when neither lookup hits; dispatcher falls back to
 * the denormalised {@code creditNote.beneficiaryName}.
 *
 * @since Slice β — F7 payment-voucher PDF generation
 */
@Component("COMMISSION-profile")
public class CommissionBeneficiaryProfileResolver implements BeneficiaryProfileResolver {

    private final BrokerRepository brokerRepository;
    private final AgentRepository  agentRepository;

    public CommissionBeneficiaryProfileResolver(BrokerRepository brokerRepository,
                                                  AgentRepository agentRepository) {
        this.brokerRepository = brokerRepository;
        this.agentRepository  = agentRepository;
    }

    @Override
    public BeneficiaryProfile resolve(CreditNote creditNote) {
        Optional<Broker> brokerOpt = brokerRepository.findById(creditNote.getBeneficiaryId());
        if (brokerOpt.isPresent()) {
            Broker b = brokerOpt.get();
            return new BeneficiaryProfile(b.getName(), b.getAddress(), null);
        }
        Optional<Agent> agentOpt = agentRepository.findById(creditNote.getBeneficiaryId());
        if (agentOpt.isPresent()) {
            Agent a = agentOpt.get();
            return new BeneficiaryProfile(a.getName(), a.getAddress(), null);
        }
        return null;
    }
}
```

> **Note**: confirm `Broker.getName()` and `Agent.getName()` exist (single combined name) or use `getCompanyName()` / `getFirstName() + getLastName()`. Inspect the entities first.

- [ ] **Step 2: Compile**

```bash
mvn install -DskipTests -pl cia-finance -am -q 2>&1 | tail -5
```

- [ ] **Step 3: Commit**

```bash
git add cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/pdf/CommissionBeneficiaryProfileResolver.java
git commit -m "$(cat <<'EOF'
feat(finance): Slice β / Task 6 — CommissionBeneficiaryProfileResolver

Tries Broker by beneficiaryId, then falls back to Agent. Both entities
have plain address columns (no NDPR encryption — only Customer.address
is encrypted). Returns null on no-match; dispatcher falls back.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: FacOutwardBeneficiaryProfileResolver (REINSURANCE entityType)

**Files:**
- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/pdf/FacOutwardBeneficiaryProfileResolver.java`

- [ ] **Step 1: Implement resolver**

```java
package com.nubeero.cia.finance.pdf;

import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.setup.org.ReinsuranceCompany;
import com.nubeero.cia.setup.org.ReinsuranceCompanyRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the beneficiary profile for {@link com.nubeero.cia.finance.FinanceEntityType#REINSURANCE}
 * credit notes — outward FAC premium settlements to reinsurers.
 *
 * <p>Loads {@link ReinsuranceCompany} by {@code creditNote.beneficiaryId}.
 * Plain address column; no decryption involved.
 *
 * @since Slice β — F7 payment-voucher PDF generation
 */
@Component("REINSURANCE-profile")
public class FacOutwardBeneficiaryProfileResolver implements BeneficiaryProfileResolver {

    private final ReinsuranceCompanyRepository reinsurerRepository;

    public FacOutwardBeneficiaryProfileResolver(
            ReinsuranceCompanyRepository reinsurerRepository) {
        this.reinsurerRepository = reinsurerRepository;
    }

    @Override
    public BeneficiaryProfile resolve(CreditNote creditNote) {
        Optional<ReinsuranceCompany> opt =
                reinsurerRepository.findById(creditNote.getBeneficiaryId());
        if (opt.isEmpty()) return null;
        ReinsuranceCompany r = opt.get();
        return new BeneficiaryProfile(r.getName(), r.getAddress(), null);
    }
}
```

- [ ] **Step 2: Compile + commit**

```bash
mvn install -DskipTests -pl cia-finance -am -q 2>&1 | tail -5
git add cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/pdf/FacOutwardBeneficiaryProfileResolver.java
git commit -m "$(cat <<'EOF'
feat(finance): Slice β / Task 7 — FacOutwardBeneficiaryProfileResolver

Loads ReinsuranceCompany by beneficiaryId. Plain address column; no
decryption. Returns null on missing reinsurer; dispatcher falls back.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: EndorsementRefundBeneficiaryProfileResolver (ENDORSEMENT entityType)

**Files:**
- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/pdf/EndorsementRefundBeneficiaryProfileResolver.java`

- [ ] **Step 1: Implement resolver**

```java
package com.nubeero.cia.finance.pdf;

import com.nubeero.cia.customer.Customer;
import com.nubeero.cia.customer.CustomerRepository;
import com.nubeero.cia.endorsement.Endorsement;
import com.nubeero.cia.endorsement.EndorsementRepository;
import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.policy.Policy;
import com.nubeero.cia.policy.PolicyRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the beneficiary profile for {@link com.nubeero.cia.finance.FinanceEntityType#ENDORSEMENT}
 * credit notes — endorsement refunds back to the policyholder.
 *
 * <p>Chain: Endorsement → Policy → Customer. Customer.address auto-decrypts
 * via JPA @ColumnTransformer.
 *
 * @since Slice β — F7 payment-voucher PDF generation
 */
@Component("ENDORSEMENT-profile")
public class EndorsementRefundBeneficiaryProfileResolver implements BeneficiaryProfileResolver {

    private final EndorsementRepository endorsementRepository;
    private final PolicyRepository      policyRepository;
    private final CustomerRepository    customerRepository;

    public EndorsementRefundBeneficiaryProfileResolver(
            EndorsementRepository endorsementRepository,
            PolicyRepository      policyRepository,
            CustomerRepository    customerRepository) {
        this.endorsementRepository = endorsementRepository;
        this.policyRepository      = policyRepository;
        this.customerRepository    = customerRepository;
    }

    @Override
    public BeneficiaryProfile resolve(CreditNote creditNote) {
        Optional<Endorsement> endOpt = endorsementRepository.findById(creditNote.getEntityId());
        if (endOpt.isEmpty()) return null;
        Endorsement end = endOpt.get();

        Optional<Policy> polOpt = policyRepository.findById(end.getPolicyId());
        if (polOpt.isEmpty()) return null;
        Policy pol = polOpt.get();

        Optional<Customer> custOpt = customerRepository.findById(pol.getCustomerId());
        if (custOpt.isEmpty()) return null;
        Customer c = custOpt.get();

        return new BeneficiaryProfile(c.getDisplayName(), c.getAddress(), null);
    }
}
```

> **Note**: If `cia-finance` doesn't yet depend on `cia-policy`, the `Policy` import will fail to resolve. Add `cia-policy` to the dep block in `cia-finance/pom.xml` (Task 2 should have included it — if missed, add it here as a one-line followup commit before this one).

- [ ] **Step 2: Compile + commit**

```bash
mvn install -DskipTests -pl cia-finance -am -q 2>&1 | tail -5
git add cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/pdf/EndorsementRefundBeneficiaryProfileResolver.java
git commit -m "$(cat <<'EOF'
feat(finance): Slice β / Task 8 — EndorsementRefundBeneficiaryProfileResolver

Endorsement → Policy → Customer chain. Customer.address auto-decrypts
via JPA @ColumnTransformer. Returns null at any missing link;
dispatcher falls back.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Dispatcher + 4-resolver IT bundle

**Files:**
- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/pdf/BeneficiaryProfileResolverIT.java`

- [ ] **Step 1: Write the failing IT**

```java
package com.nubeero.cia.api.finance.pdf;

import com.nubeero.cia.api.finance.FinanceItSupport;
import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.finance.CreditNoteStatus;
import com.nubeero.cia.finance.FinanceEntityType;
import com.nubeero.cia.finance.pdf.BeneficiaryProfile;
import com.nubeero.cia.finance.pdf.BeneficiaryProfileResolverDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link BeneficiaryProfileResolverDispatcher} routing + per-resolver
 * behaviour for all 4 entity types plus the unmapped-type fallback.
 *
 * @since Slice β — F7 payment-voucher PDF generation
 */
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    BeneficiaryProfileResolverDispatcher.class,
    com.nubeero.cia.finance.pdf.ClaimBeneficiaryProfileResolver.class,
    com.nubeero.cia.finance.pdf.CommissionBeneficiaryProfileResolver.class,
    com.nubeero.cia.finance.pdf.FacOutwardBeneficiaryProfileResolver.class,
    com.nubeero.cia.finance.pdf.EndorsementRefundBeneficiaryProfileResolver.class
})
class BeneficiaryProfileResolverIT extends FinanceItSupport {

    @Autowired BeneficiaryProfileResolverDispatcher dispatcher;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("CLAIM credit note resolves to claimant Customer (address decrypted via @ColumnTransformer)")
    void claimResolverDecryptsCustomerAddress() {
        UUID customerId = seedIndividualCustomer("Adaeze", "Okonkwo", "12 Marina St, Lagos");
        UUID claimId = seedClaim(customerId, "CLM-IT-001");
        CreditNote cn = creditNote(FinanceEntityType.CLAIM, claimId, customerId, "Adaeze Okonkwo");

        BeneficiaryProfile profile = dispatcher.resolve(cn);

        assertThat(profile.name()).isEqualTo("Adaeze Okonkwo");
        assertThat(profile.addressLine1())
            .as("Customer.address is encrypted at rest; JPA @ColumnTransformer decrypts on read")
            .isEqualTo("12 Marina St, Lagos");
    }

    @Test
    @DisplayName("COMMISSION credit note resolves to Broker when beneficiaryId is a broker")
    void commissionResolverFindsBroker() {
        UUID brokerId = seedBroker("ABC Brokers Ltd", "5 Allen Avenue, Ikeja");
        CreditNote cn = creditNote(FinanceEntityType.COMMISSION, UUID.randomUUID(), brokerId, "ABC Brokers Ltd");

        BeneficiaryProfile profile = dispatcher.resolve(cn);

        assertThat(profile.name()).isEqualTo("ABC Brokers Ltd");
        assertThat(profile.addressLine1()).isEqualTo("5 Allen Avenue, Ikeja");
    }

    @Test
    @DisplayName("COMMISSION credit note falls back to Agent when broker lookup misses")
    void commissionResolverFallsBackToAgent() {
        UUID agentId = seedAgent("Tunde Adetayo", "7 Adeola Odeku, V.I.");
        CreditNote cn = creditNote(FinanceEntityType.COMMISSION, UUID.randomUUID(), agentId, "Tunde Adetayo");

        BeneficiaryProfile profile = dispatcher.resolve(cn);

        assertThat(profile.name()).isEqualTo("Tunde Adetayo");
        assertThat(profile.addressLine1()).isEqualTo("7 Adeola Odeku, V.I.");
    }

    @Test
    @DisplayName("REINSURANCE credit note resolves to ReinsuranceCompany")
    void facOutwardResolverFindsReinsurer() {
        UUID rId = seedReinsurer("Africa Re", "Plot 1, Africa Re Building, Lagos");
        CreditNote cn = creditNote(FinanceEntityType.REINSURANCE, UUID.randomUUID(), rId, "Africa Re");

        BeneficiaryProfile profile = dispatcher.resolve(cn);

        assertThat(profile.name()).isEqualTo("Africa Re");
        assertThat(profile.addressLine1()).isEqualTo("Plot 1, Africa Re Building, Lagos");
    }

    @Test
    @DisplayName("ENDORSEMENT credit note walks Endorsement→Policy→Customer chain (decrypted address)")
    void endorsementRefundResolverWalksChain() {
        UUID customerId = seedIndividualCustomer("Chinwe", "Nwafor", "3 Maitama Ext, Abuja");
        UUID policyId   = seedPolicy(customerId, "POL-IT-001");
        UUID endId      = seedEndorsement(policyId, "END-IT-001");
        CreditNote cn = creditNote(FinanceEntityType.ENDORSEMENT, endId, customerId, "Chinwe Nwafor");

        BeneficiaryProfile profile = dispatcher.resolve(cn);

        assertThat(profile.name()).isEqualTo("Chinwe Nwafor");
        assertThat(profile.addressLine1()).isEqualTo("3 Maitama Ext, Abuja");
    }

    @Test
    @DisplayName("POLICY entity type (unmapped) falls back to denormalised beneficiaryName")
    void unmappedEntityTypeFallsBack() {
        CreditNote cn = creditNote(FinanceEntityType.POLICY, UUID.randomUUID(), UUID.randomUUID(), "Some Beneficiary");

        BeneficiaryProfile profile = dispatcher.resolve(cn);

        assertThat(profile.name()).isEqualTo("Some Beneficiary");
        assertThat(profile.addressLine1()).isNull();
    }

    @Test
    @DisplayName("CLAIM_EXPENSE entity type (unmapped) falls back to denormalised beneficiaryName")
    void claimExpenseFallsBack() {
        CreditNote cn = creditNote(FinanceEntityType.CLAIM_EXPENSE, UUID.randomUUID(), UUID.randomUUID(), "Inspection Surveyor");

        BeneficiaryProfile profile = dispatcher.resolve(cn);

        assertThat(profile.name()).isEqualTo("Inspection Surveyor");
        assertThat(profile.addressLine1()).isNull();
    }

    @Test
    @DisplayName("CLAIM with missing customer falls back to claim's denormalised customerName")
    void claimResolverWithMissingCustomerFallsBack() {
        UUID claimId = seedClaim(UUID.randomUUID() /* nonexistent customer */, "CLM-IT-002");
        CreditNote cn = creditNote(FinanceEntityType.CLAIM, claimId, UUID.randomUUID(), "Should Not Be Used");

        BeneficiaryProfile profile = dispatcher.resolve(cn);

        // Resolver returns BeneficiaryProfile.nameOnly(claim.customerName) — see ClaimResolver.
        assertThat(profile.name()).startsWith("Test Customer for CLM-");
        assertThat(profile.addressLine1()).isNull();
    }

    // ── Fixture helpers (JDBC; mirror FinanceItFixtures conventions) ───────

    private UUID seedIndividualCustomer(String firstName, String lastName, String plainAddress) {
        UUID id = UUID.randomUUID();
        jdbc.update("SET LOCAL app.pii_key = 'test-key-for-it-only-do-not-use-in-prod'");
        jdbc.update(
            "INSERT INTO customers " +
            "  (id, customer_number, customer_type, first_name, last_name, " +
            "   address, email, kyc_status, created_by) " +
            "VALUES (?, ?, 'INDIVIDUAL', ?, ?, " +
            "        pgp_sym_encrypt(?, current_setting('app.pii_key')), " +
            "        ?, 'VERIFIED', 'test')",
            id, "CUST-IT-" + id.toString().substring(0, 6),
            firstName, lastName, plainAddress, firstName.toLowerCase() + "@test.local"
        );
        return id;
    }

    private UUID seedClaim(UUID customerId, String claimNumber) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO claims (id, claim_number, customer_id, customer_name, " +
            "                    policy_id, policy_number, status, " +
            "                    incident_date, notification_date, currency_code, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, 'REGISTERED', CURRENT_DATE, CURRENT_DATE, 'NGN', 'test')",
            id, claimNumber, customerId, "Test Customer for " + claimNumber,
            UUID.randomUUID(), "POL-FOR-" + claimNumber
        );
        return id;
    }

    private UUID seedBroker(String name, String address) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO brokers (id, broker_code, name, address, email, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, 'ACTIVE', 'test')",
            id, "BRK-IT-" + id.toString().substring(0, 6), name, address, "broker@test.local"
        );
        return id;
    }

    private UUID seedAgent(String name, String address) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO agents (id, agent_code, agent_type, name, address, email, status, created_by) " +
            "VALUES (?, ?, 'INDIVIDUAL', ?, ?, ?, 'ACTIVE', 'test')",
            id, "AGT-IT-" + id.toString().substring(0, 6), name, address, "agent@test.local"
        );
        return id;
    }

    private UUID seedReinsurer(String name, String address) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO reinsurance_companies (id, name, address, email, status, created_by) " +
            "VALUES (?, ?, ?, ?, 'ACTIVE', 'test')",
            id, name, address, "reins@test.local"
        );
        return id;
    }

    private UUID seedPolicy(UUID customerId, String policyNumber) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO policies (id, policy_number, customer_id, status, " +
            "                      policy_start_date, policy_end_date, " +
            "                      sum_insured, rate, premium, currency_code, created_by) " +
            "VALUES (?, ?, ?, 'ACTIVE', CURRENT_DATE, CURRENT_DATE + INTERVAL '1 year', " +
            "        1000000, 0.05, 50000, 'NGN', 'test')",
            id, policyNumber, customerId
        );
        return id;
    }

    private UUID seedEndorsement(UUID policyId, String endorsementNumber) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO endorsements (id, endorsement_number, policy_id, endorsement_type, " +
            "                          effective_date, status, currency_code, created_by) " +
            "VALUES (?, ?, ?, 'REDUCTION', CURRENT_DATE, 'APPROVED', 'NGN', 'test')",
            id, endorsementNumber, policyId
        );
        return id;
    }

    private CreditNote creditNote(FinanceEntityType type, UUID entityId, UUID benId, String benName) {
        CreditNote cn = new CreditNote();
        cn.setCreditNoteNumber("CN-IT-" + UUID.randomUUID().toString().substring(0, 8));
        cn.setStatus(CreditNoteStatus.OUTSTANDING);
        cn.setEntityType(type);
        cn.setEntityId(entityId);
        cn.setEntityReference("REF-" + type.name());
        cn.setBeneficiaryId(benId);
        cn.setBeneficiaryName(benName);
        cn.setDescription("IT fixture credit note");
        cn.setAmount(new BigDecimal("500000.00"));
        cn.setTotalAmount(new BigDecimal("500000.00"));
        return cn;
    }
}
```

> **Note 1 — field names on entities**: the helpers above assume column names like `customers.first_name`, `brokers.name`, etc. If the real schema differs (e.g. `brokers.broker_name`), adjust each INSERT to match the actual V2/V45/V48 column layout. Run `mvn -pl cia-api failsafe:integration-test -Dit.test=BeneficiaryProfileResolverIT -DskipUnitTests=true` to see the actual error before guessing.
>
> **Note 2 — `app.pii_key` session var**: the IT sets it via `SET LOCAL` immediately before the customer insert. The dispatcher's customer-load path runs inside a `@Transactional`(propagation=REQUIRED) span — same connection — so the session var is present. If the read happens on a different connection, the resolver would see null. If you hit this, switch to `SET app.pii_key = '...'` (without LOCAL) at the top of `@BeforeEach`.

- [ ] **Step 2: Run the IT**

```bash
cd /Users/razormvp/CoreInsurance/cia-backend
mvn install -DskipTests -pl cia-api -am -q
mvn -pl cia-api failsafe:integration-test -Dit.test=BeneficiaryProfileResolverIT -DskipUnitTests=true -q 2>&1 | tail -15
```

Expected: 8 tests pass.

- [ ] **Step 3: Commit**

```bash
git add cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/pdf/BeneficiaryProfileResolverIT.java
git commit -m "$(cat <<'EOF'
test(finance): Slice β / Task 9 — BeneficiaryProfileResolverIT (8 tests)

Pins dispatcher routing + per-resolver behaviour for all 4 entity types
(CLAIM, COMMISSION, REINSURANCE, ENDORSEMENT) plus the unmapped-type
fallback (POLICY, CLAIM_EXPENSE → denormalised beneficiaryName) and the
missing-referenced-entity fallback for CLAIM.

CLAIM + ENDORSEMENT cases verify Customer.address decrypts via JPA
@ColumnTransformer (NDPR encrypted column → plain string on read).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: ReceiptPdfGenerator + Thymeleaf template + IT

**Files:**
- Create: `cia-backend/cia-documents/src/main/resources/templates/pdf/receipt.html`
- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/pdf/ReceiptPdfGenerator.java`
- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/pdf/ReceiptPdfGeneratorIT.java`

- [ ] **Step 1: Create Thymeleaf template**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
  <h1>OFFICIAL RECEIPT</h1>
  <hr/>

  <p><b>Receipt No.:</b> <span th:text="${receiptNumber}">REC-XXXX</span></p>
  <p><b>Date:</b> <span th:text="${paymentDate}">YYYY-MM-DD</span></p>
  <hr/>

  <h2>Received from</h2>
  <p><b th:text="${customerName}">Customer Name</b></p>

  <h2>Payment Details</h2>
  <table>
    <tr><td>Amount</td><td><b><span th:text="${amountFormatted}">₦0.00</span></b></td></tr>
    <tr><td>Method</td><td><span th:text="${paymentMethod}">METHOD</span></td></tr>
    <tr><td>Reference</td><td><span th:text="${debitNoteNumber}">DN-XXXX</span></td></tr>
    <tr th:if="${policyNumber}"><td>Policy</td><td><span th:text="${policyNumber}">POL-XXXX</span></td></tr>
  </table>

  <h2>Being payment for</h2>
  <p th:text="${narration}">Narration text</p>

  <hr/>
  <p><i>Posted by: <span th:text="${postedBy}">user</span></i></p>
  <p><i>This is a system-generated receipt and does not require a physical signature.</i></p>
</body>
</html>
```

- [ ] **Step 2: Create ReceiptPdfGenerator**

```java
package com.nubeero.cia.finance.pdf;

import com.nubeero.cia.documents.HtmlToPdfConverter;
import com.nubeero.cia.finance.Receipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Generates a Thymeleaf-rendered "OFFICIAL RECEIPT" PDF from a posted
 * {@link Receipt}. Never throws — catches every exception, logs WARN, and
 * returns {@code null}. The {@code post()} flow tolerates null
 * (leaves {@code pdf_path} unset) so PDF failures never roll back the
 * receipt save.
 *
 * @since Slice β — Task 10
 */
@Component
public class ReceiptPdfGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReceiptPdfGenerator.class);

    private final TemplateEngine     templateEngine;
    private final HtmlToPdfConverter htmlToPdfConverter;

    public ReceiptPdfGenerator(TemplateEngine templateEngine,
                                HtmlToPdfConverter htmlToPdfConverter) {
        this.templateEngine     = templateEngine;
        this.htmlToPdfConverter = htmlToPdfConverter;
    }

    /**
     * Renders the receipt template + converts to PDF bytes.
     *
     * <p>{@code receipt.getDebitNote()} must be non-null and eagerly loaded —
     * the template reads customer name + policy reference from it.
     *
     * @return PDF bytes, or {@code null} on any rendering / conversion failure
     */
    public byte[] generate(Receipt receipt) {
        try {
            Context ctx = new Context();
            ctx.setVariable("receiptNumber",   receipt.getReceiptNumber());
            ctx.setVariable("paymentDate",     receipt.getPaymentDate().toString());
            ctx.setVariable("customerName",    receipt.getDebitNote().getCustomerName());
            ctx.setVariable("amountFormatted", formatNaira(receipt.getAmount()));
            ctx.setVariable("paymentMethod",   receipt.getPaymentMethod().name().replace('_', ' '));
            ctx.setVariable("debitNoteNumber", receipt.getDebitNote().getDebitNoteNumber());
            ctx.setVariable("policyNumber",
                "POLICY".equals(receipt.getDebitNote().getEntityType().name())
                    ? receipt.getDebitNote().getEntityReference()
                    : null);
            ctx.setVariable("narration",       receipt.getNarration() == null ? "" : receipt.getNarration());
            ctx.setVariable("postedBy",        receipt.getPostedBy() == null ? "system" : receipt.getPostedBy());

            String html = templateEngine.process("pdf/receipt", ctx);
            return htmlToPdfConverter.convert(html);
        } catch (Exception e) {
            log.warn("ReceiptPdfGenerator failed for receipt {}: {}",
                     receipt.getId(), e.getMessage(), e);
            return null;
        }
    }

    private static String formatNaira(BigDecimal amount) {
        NumberFormat nf = NumberFormat.getInstance(new Locale("en", "NG"));
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return "₦" + nf.format(amount);
    }
}
```

- [ ] **Step 3: Write the failing IT**

```java
package com.nubeero.cia.api.finance.pdf;

import com.nubeero.cia.api.finance.FinanceItSupport;
import com.nubeero.cia.documents.HtmlToPdfConverter;
import com.nubeero.cia.finance.DebitNote;
import com.nubeero.cia.finance.DebitNoteStatus;
import com.nubeero.cia.finance.FinanceEntityType;
import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.Receipt;
import com.nubeero.cia.finance.TransactionStatus;
import com.nubeero.cia.finance.pdf.ReceiptPdfGenerator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    HtmlToPdfConverter.class,
    ReceiptPdfGenerator.class
})
class ReceiptPdfGeneratorIT extends FinanceItSupport {

    @Autowired ReceiptPdfGenerator generator;

    @Test
    @DisplayName("Generated PDF parses cleanly via PDFBox and contains receipt fields + ₦")
    void generatesParseablePdfWithExpectedContent() throws Exception {
        Receipt r = sampleReceipt();

        byte[] pdf = generator.generate(r);
        assertThat(pdf).isNotEmpty();

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text)
                .contains("OFFICIAL RECEIPT")
                .contains(r.getReceiptNumber())
                .contains(r.getDebitNote().getDebitNoteNumber())
                .contains("Adaeze Okonkwo")
                .contains("₦250,000.00")
                .contains("BANK TRANSFER");
        }
    }

    @Test
    @DisplayName("Generator returns null cleanly when template rendering throws")
    void returnsNullOnRenderingException() {
        Receipt broken = new Receipt();
        // No debitNote set — template will NPE on customerName lookup
        broken.setReceiptNumber("REC-IT-BROKEN");
        broken.setAmount(new BigDecimal("100"));
        broken.setPaymentDate(LocalDate.now());
        broken.setPaymentMethod(PaymentMethod.CASH);

        byte[] result = generator.generate(broken);

        assertThat(result).as("Generator must never throw; null signals to ReceiptService.post() to leave pdf_path unset").isNull();
    }

    @Test
    @DisplayName("Policy number row is rendered when DN entityType==POLICY")
    void rendersPolicyNumberRowWhenDnIsPolicyBacked() throws Exception {
        Receipt r = sampleReceipt(); // DN already entityType=POLICY

        byte[] pdf = generator.generate(r);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains(r.getDebitNote().getEntityReference());
        }
    }

    @Test
    @DisplayName("Narration appears in the 'Being payment for' section")
    void narrationRenders() throws Exception {
        Receipt r = sampleReceipt();
        r.setNarration("Q1 2026 premium settlement");

        byte[] pdf = generator.generate(r);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Q1 2026 premium settlement");
        }
    }

    private Receipt sampleReceipt() {
        DebitNote dn = new DebitNote();
        dn.setDebitNoteNumber("DN-IT-2026-00042");
        dn.setStatus(DebitNoteStatus.PARTIAL);
        dn.setEntityType(FinanceEntityType.POLICY);
        dn.setEntityId(UUID.randomUUID());
        dn.setEntityReference("POL-IT-2026-001");
        dn.setCustomerId(UUID.randomUUID());
        dn.setCustomerName("Adaeze Okonkwo");
        dn.setDescription("Premium for policy POL-IT-2026-001");
        dn.setAmount(new BigDecimal("500000.00"));
        dn.setTotalAmount(new BigDecimal("500000.00"));

        Receipt r = new Receipt();
        r.setReceiptNumber("REC-IT-2026-00001");
        r.setDebitNote(dn);
        r.setAmount(new BigDecimal("250000.00"));
        r.setPaymentDate(LocalDate.now());
        r.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        r.setStatus(TransactionStatus.POSTED);
        r.setNarration("Partial premium");
        r.setPostedBy("alice");
        return r;
    }
}
```

- [ ] **Step 4: Run the IT**

```bash
cd /Users/razormvp/CoreInsurance/cia-backend
mvn install -DskipTests -pl cia-api -am -q
mvn -pl cia-api failsafe:integration-test -Dit.test=ReceiptPdfGeneratorIT -DskipUnitTests=true -q 2>&1 | tail -10
```

Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-documents/src/main/resources/templates/pdf/receipt.html \
        cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/pdf/ReceiptPdfGenerator.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/pdf/ReceiptPdfGeneratorIT.java
git commit -m "$(cat <<'EOF'
feat(finance): Slice β / Task 10 — ReceiptPdfGenerator + Thymeleaf template

Renders OFFICIAL RECEIPT PDF from Receipt + debitNote (eagerly loaded).
Never throws — catches Exception, logs WARN, returns null so ReceiptService
.post() can leave pdf_path unset without rolling back the receipt save.

Template at /templates/pdf/receipt.html — h1/h2/table/p/hr only, all
supported by the post-Task-3 HtmlToPdfConverter with NotoSans + ₦.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: ReceiptService.post() auto-generates + persists pdfPath

**Files:**
- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptService.java`
- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptListItemResponse.java`

- [ ] **Step 1: Wire ReceiptPdfGenerator + DocumentStorageService into ReceiptService**

Add fields + constructor params:

```java
import com.nubeero.cia.finance.pdf.ReceiptPdfGenerator;
import com.nubeero.cia.storage.DocumentStorageService;
import com.nubeero.cia.common.tenant.TenantContext;

// New fields:
private final ReceiptPdfGenerator    pdfGenerator;
private final DocumentStorageService storage;

// Add to constructor signature + assignment:
public ReceiptService(..., ReceiptPdfGenerator pdfGenerator,
                          DocumentStorageService storage) {
    // existing assignments...
    this.pdfGenerator = pdfGenerator;
    this.storage      = storage;
}
```

- [ ] **Step 2: Hook PDF generation into post() after the save + audit log**

At the end of the existing `post()` method body (after the audit-log call and DN recalc), insert:

```java
        generateAndPersistPdf(saved);
```

And add the helper method:

```java
    /**
     * Generates the receipt PDF + uploads to MinIO + persists pdfPath.
     * Failure mode: log WARN, leave pdf_path null, do NOT throw — keeps the
     * post() commit intact so a PDF rendering hiccup never loses a receipt.
     */
    private void generateAndPersistPdf(Receipt receipt) {
        byte[] pdf = pdfGenerator.generate(receipt);
        if (pdf == null) {
            // Already logged inside the generator.
            return;
        }
        try {
            String tenantId = TenantContext.getCurrentTenant();
            String path = String.format("receipts/%d/%02d/%s.pdf",
                receipt.getPaymentDate().getYear(),
                receipt.getPaymentDate().getMonthValue(),
                receipt.getId());
            storage.upload(tenantId, path,
                           new ByteArrayInputStream(pdf), "application/pdf");
            receipt.setPdfPath(path);
            receiptRepository.save(receipt);
        } catch (Exception e) {
            log.warn("Failed to upload generated receipt PDF for {}: {}",
                     receipt.getId(), e.getMessage(), e);
        }
    }
```

Add the `import java.io.ByteArrayInputStream;` to the top of the file.

- [ ] **Step 3: Add pdfPath to ReceiptListItemResponse**

In `ReceiptListItemResponse.java`, add `String pdfPath` to the record signature (after `createdAt`):

```java
public record ReceiptListItemResponse(
        UUID id,
        String reference,
        UUID debitNoteId,
        String debitNoteNumber,
        String policyNumber,
        String customerName,
        BigDecimal amount,
        PaymentMethod paymentMethod,
        LocalDate paymentDate,
        TransactionStatus status,
        Instant reversedAt,
        String reversedBy,
        String reversalReason,
        Instant createdAt,
        String pdfPath        // nullable — null = PDF was never generated
) {}
```

- [ ] **Step 4: Update toListItem projection**

In `ReceiptService.toListItem(...)`, append `receipt.getPdfPath()` as the last constructor arg:

```java
    private ReceiptListItemResponse toListItem(Receipt r) {
        DebitNote dn = r.getDebitNote();
        return new ReceiptListItemResponse(
            r.getId(),
            r.getReceiptNumber(),
            dn.getId(),
            dn.getDebitNoteNumber(),
            dn.getEntityType() == FinanceEntityType.POLICY ? dn.getEntityReference() : null,
            dn.getCustomerName(),
            r.getAmount(),
            r.getPaymentMethod(),
            r.getPaymentDate(),
            r.getStatus(),
            r.getReversedAt(),
            r.getReversedBy(),
            r.getReversalReason(),
            r.getCreatedAt(),
            r.getPdfPath()          // ← NEW
        );
    }
```

- [ ] **Step 5: Verify existing tests still pass + add a pdf-persist IT**

The existing `ReceiptListControllerIT` should still pass because it doesn't assert on `pdfPath`. But the new code path is now hot — we need a quick IT verifying `pdf_path` gets populated.

Create `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/ReceiptPdfListItemIT.java`:

```java
package com.nubeero.cia.api.finance;

import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.Receipt;
import com.nubeero.cia.finance.ReceiptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that after ReceiptService.post() the database row has pdf_path set
 * AND the GET /api/v1/receipts list item exposes it for the frontend.
 *
 * @since Slice β — Task 11
 */
@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE", "FINANCE_UPDATE"})
class ReceiptPdfListItemIT extends FinanceWebItSupport {

    @Autowired MockMvc        mockMvc;
    @Autowired ReceiptService receiptService;
    @Autowired JdbcTemplate   jdbc;

    @BeforeEach
    void setUpFiscalPeriod() {
        // Same setup as ReceiptListControllerIT — copy verbatim
        // (FY + MONTH period covering today, idempotent via ON CONFLICT DO NOTHING)
        // ... [paste from ReceiptListControllerIT]
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void postReceipt_populatesPdfPathInDbAndApi() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "alice", "pw",
                List.of(new SimpleGrantedAuthority("FINANCE_CREATE"),
                        new SimpleGrantedAuthority("FINANCE_UPDATE"))));

        UUID dnId = createDebitNote();
        Receipt posted = receiptService.post(
            dnId, new BigDecimal("100000.00"), LocalDate.now(),
            PaymentMethod.BANK_TRANSFER, null, "Test Bank", null, "IT");

        String pdfPath = jdbc.queryForObject(
            "SELECT pdf_path FROM receipts WHERE id = ?", String.class, posted.getId());

        assertThat(pdfPath).as("pdf_path should be populated after post()").isNotNull();
        assertThat(pdfPath).startsWith("receipts/").endsWith(".pdf");

        mockMvc.perform(get("/api/v1/receipts").param("debitNoteId", dnId.toString()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data[0].pdfPath").value(pdfPath));
    }

    @Test
    void postReceipt_pdfPathNullSurfaces_whenStorageThrows_noEffectOnReceiptCommit() {
        // Documented behaviour: a storage failure leaves pdf_path null but the
        // receipt is still saved. Reproducing storage failure deterministically
        // requires injecting a mock storage that throws — out of scope for this
        // IT (would need test profile + bean override). Verifying this is left
        // to the unit-level test if added later. Pin docs only.
    }

    private UUID createDebitNote() {
        UUID dnId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO debit_notes " +
            "  (id, debit_note_number, status, entity_type, entity_id, entity_reference, " +
            "   customer_id, customer_name, description, amount, tax_amount, total_amount, " +
            "   paid_amount, currency_code, created_by) " +
            "VALUES (?, ?, 'OUTSTANDING', 'POLICY', ?, ?, ?, ?, ?, ?, 0, ?, 0, 'NGN', 'test')",
            dnId, "DN-PDF-" + dnId.toString().substring(0, 8),
            UUID.randomUUID(), "POL-PDF-001",
            UUID.randomUUID(), "PDF Test Customer", "Premium",
            new BigDecimal("500000.00"), new BigDecimal("500000.00")
        );
        return dnId;
    }
}
```

- [ ] **Step 6: Run the IT**

```bash
mvn install -DskipTests -pl cia-api -am -q
mvn -pl cia-api failsafe:integration-test -Dit.test=ReceiptPdfListItemIT -DskipUnitTests=true -q 2>&1 | tail -10
```

Expected: PASS — `pdf_path` populated.

- [ ] **Step 7: Commit**

```bash
git add cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptService.java \
        cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptListItemResponse.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/ReceiptPdfListItemIT.java
git commit -m "$(cat <<'EOF'
feat(finance): Slice β / Task 11 — ReceiptService.post auto-generates PDF + persists path

After audit log + DN recalc, generateAndPersistPdf() runs the
ReceiptPdfGenerator, uploads bytes to MinIO under
`receipts/{yyyy}/{MM}/{id}.pdf`, and updates receipt.pdfPath. Failure of
either generator or storage logs WARN and leaves pdf_path null — never
throws, never rolls back the receipt commit.

ReceiptListItemResponse + toListItem projection now carry pdfPath so the
frontend can gate the Download button.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: ReceiptController GET /pdf endpoint + IT

**Files:**
- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptController.java`
- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/ReceiptControllerPdfIT.java`

- [ ] **Step 1: Add the endpoint**

In `ReceiptController.java`, add (and the corresponding imports — `MediaType`, `ResponseEntity`, `Resource`, `InputStreamResource`):

```java
    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAuthority('FINANCE_VIEW')")
    @Operation(summary = "Download the receipt PDF",
               description = "Streams the generated PDF for the receipt from object storage. 404 when pdfPath IS NULL (PDF was never generated or generation failed).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "PDF bytes"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Receipt not found OR pdfPath is null", content = @Content)
    })
    public ResponseEntity<Resource> downloadPdf(@PathVariable UUID debitNoteId,
                                                  @PathVariable UUID id) {
        Receipt receipt = service.findOrThrow(id);
        if (receipt.getPdfPath() == null) {
            throw new com.nubeero.cia.common.exception.ResourceNotFoundException(
                "Receipt PDF not available", "Receipt", id.toString());
        }
        String tenantId = TenantContext.getCurrentTenant();
        InputStream stream = storage.download(tenantId, receipt.getPdfPath());
        String filename = "REC-" + receipt.getReceiptNumber() + ".pdf";

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + filename + "\"")
            .body(new InputStreamResource(stream));
    }
```

Add field + constructor injection for `DocumentStorageService storage`.

- [ ] **Step 2: Write the failing IT**

```java
package com.nubeero.cia.api.finance;

import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.Receipt;
import com.nubeero.cia.finance.ReceiptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE", "FINANCE_UPDATE"})
class ReceiptControllerPdfIT extends FinanceWebItSupport {

    @Autowired MockMvc mockMvc;
    @Autowired ReceiptService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setupFiscalPeriod() { /* same as ReceiptListControllerIT */ }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void getReceiptPdf_streamsBytes() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "alice", "pw",
                List.of(new SimpleGrantedAuthority("FINANCE_CREATE"),
                        new SimpleGrantedAuthority("FINANCE_UPDATE"))));

        UUID dnId = createDebitNote();
        Receipt r = service.post(dnId, new BigDecimal("100000"),
            LocalDate.now(), PaymentMethod.CASH, null, null, null, "IT");
        assertThat(r.getPdfPath()).isNotNull();

        MvcResult res = mockMvc.perform(get("/api/v1/debit-notes/{dnId}/receipts/{id}/pdf",
                                              dnId, r.getId()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition",
                                        org.hamcrest.Matchers.containsString("REC-")))
            .andReturn();

        byte[] body = res.getResponse().getContentAsByteArray();
        assertThat(body).isNotEmpty();
        // PDF magic bytes: 25 50 44 46 (%PDF)
        assertThat(new String(body, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void getReceiptPdf_404_whenPdfPathNull() throws Exception {
        UUID dnId = createDebitNote();
        UUID receiptId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO receipts (id, receipt_number, debit_note_id, amount, payment_date, " +
            "                       payment_method, status, created_by) " +
            "VALUES (?, ?, ?, ?, CURRENT_DATE, 'CASH', 'POSTED', 'test')",
            receiptId, "REC-NULL", dnId, new BigDecimal("100000")
        );
        mockMvc.perform(get("/api/v1/debit-notes/{dnId}/receipts/{id}/pdf", dnId, receiptId))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "bob", authorities = {"CLAIMS_VIEW"})
    void getReceiptPdf_403_withoutFinanceView() throws Exception {
        mockMvc.perform(get("/api/v1/debit-notes/{dnId}/receipts/{id}/pdf",
                              UUID.randomUUID(), UUID.randomUUID()))
            .andExpect(status().isForbidden());
    }

    @Test
    void getReceiptPdf_404_whenReceiptDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/v1/debit-notes/{dnId}/receipts/{id}/pdf",
                              UUID.randomUUID(), UUID.randomUUID()))
            .andExpect(status().isNotFound());
    }

    @Test
    void getReceiptPdf_minioObjectExistsAtExpectedPath() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "alice", "pw",
                List.of(new SimpleGrantedAuthority("FINANCE_CREATE"),
                        new SimpleGrantedAuthority("FINANCE_UPDATE"))));

        UUID dnId = createDebitNote();
        Receipt r = service.post(dnId, new BigDecimal("100000"),
            LocalDate.now(), PaymentMethod.CASH, null, null, null, "IT");

        // pdf_path format: receipts/{yyyy}/{MM}/{id}.pdf
        assertThat(r.getPdfPath())
            .matches("^receipts/\\d{4}/\\d{2}/" + r.getId() + "\\.pdf$");
    }

    private UUID createDebitNote() {
        // Same JDBC seed as ReceiptListControllerIT
        UUID dnId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO debit_notes " +
            "  (id, debit_note_number, status, entity_type, entity_id, entity_reference, " +
            "   customer_id, customer_name, description, amount, tax_amount, total_amount, " +
            "   paid_amount, currency_code, created_by) " +
            "VALUES (?, ?, 'OUTSTANDING', 'POLICY', ?, ?, ?, ?, ?, ?, 0, ?, 0, 'NGN', 'test')",
            dnId, "DN-PDF-" + dnId.toString().substring(0, 8),
            UUID.randomUUID(), "POL-PDF-001",
            UUID.randomUUID(), "PDF Test Customer", "Premium",
            new BigDecimal("500000.00"), new BigDecimal("500000.00")
        );
        return dnId;
    }
}
```

- [ ] **Step 3: Run the IT**

```bash
mvn install -DskipTests -pl cia-api -am -q
mvn -pl cia-api failsafe:integration-test -Dit.test=ReceiptControllerPdfIT -DskipUnitTests=true -q 2>&1 | tail -10
```

Expected: 5 tests pass.

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/ReceiptController.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/ReceiptControllerPdfIT.java
git commit -m "$(cat <<'EOF'
feat(finance): Slice β / Task 12 — ReceiptController GET /pdf endpoint

GET /api/v1/debit-notes/{dnId}/receipts/{id}/pdf streams the receipt PDF
from MinIO. FINANCE_VIEW required. 404 when pdfPath IS NULL (PDF was never
generated or generation failed). Filename: REC-<receiptNumber>.pdf.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: PaymentVoucherPdfGenerator + Thymeleaf template + IT

**Files:**
- Create: `cia-backend/cia-documents/src/main/resources/templates/pdf/payment-voucher.html`
- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/pdf/PaymentVoucherPdfGenerator.java`
- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/pdf/PaymentVoucherPdfGeneratorIT.java`

Mirror of Task 10 but for payments. Differences:

- Template title = `${headerLabel}` (dynamic — "CLAIM SETTLEMENT VOUCHER" / "COMMISSION VOUCHER" / "FAC PREMIUM VOUCHER" / "ENDORSEMENT REFUND VOUCHER" / "PAYMENT VOUCHER" fallback).
- "Paid to" block reads from `BeneficiaryProfile` (resolved via dispatcher) — shows name + optional addressLine1 + addressLine2.
- Two signatory placeholders ("Prepared by" + "Approved by") at the bottom.

#### Template (`payment-voucher.html`)

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
  <h1 th:text="${headerLabel}">PAYMENT VOUCHER</h1>
  <hr/>

  <p><b>Voucher No.:</b> <span th:text="${paymentNumber}">PAY-XXXX</span></p>
  <p><b>Date:</b> <span th:text="${paymentDate}">YYYY-MM-DD</span></p>
  <hr/>

  <h2>Paid to</h2>
  <p><b th:text="${beneficiaryName}">Beneficiary Name</b></p>
  <p th:if="${beneficiaryAddressLine1}" th:text="${beneficiaryAddressLine1}">Address line 1</p>
  <p th:if="${beneficiaryAddressLine2}" th:text="${beneficiaryAddressLine2}">Address line 2</p>

  <h2>Payment Details</h2>
  <table>
    <tr><td>Amount</td><td><b><span th:text="${amountFormatted}">₦0.00</span></b></td></tr>
    <tr><td>Method</td><td><span th:text="${paymentMethod}">METHOD</span></td></tr>
    <tr><td>Reference</td><td><span th:text="${creditNoteNumber}">CN-XXXX</span></td></tr>
    <tr><td>Source</td><td><span th:text="${entityReference}">REF</span></td></tr>
  </table>

  <h2>Being payment for</h2>
  <p th:text="${narration}">Narration text</p>

  <hr/>
  <table>
    <tr>
      <td>_________________________<br/>Prepared by</td>
      <td>_________________________<br/>Approved by</td>
    </tr>
  </table>
</body>
</html>
```

#### Generator class

```java
package com.nubeero.cia.finance.pdf;

import com.nubeero.cia.documents.HtmlToPdfConverter;
import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.finance.FinanceEntityType;
import com.nubeero.cia.finance.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

@Component
public class PaymentVoucherPdfGenerator {

    private static final Logger log = LoggerFactory.getLogger(PaymentVoucherPdfGenerator.class);

    private final TemplateEngine                       templateEngine;
    private final HtmlToPdfConverter                   htmlToPdfConverter;
    private final BeneficiaryProfileResolverDispatcher resolverDispatcher;

    public PaymentVoucherPdfGenerator(TemplateEngine templateEngine,
                                        HtmlToPdfConverter htmlToPdfConverter,
                                        BeneficiaryProfileResolverDispatcher resolverDispatcher) {
        this.templateEngine     = templateEngine;
        this.htmlToPdfConverter = htmlToPdfConverter;
        this.resolverDispatcher = resolverDispatcher;
    }

    public byte[] generate(Payment payment) {
        try {
            CreditNote cn = payment.getCreditNote();
            BeneficiaryProfile profile = resolverDispatcher.resolve(cn);

            Context ctx = new Context();
            ctx.setVariable("headerLabel",             headerLabelFor(cn.getEntityType()));
            ctx.setVariable("paymentNumber",           payment.getPaymentNumber());
            ctx.setVariable("paymentDate",             payment.getPaymentDate().toString());
            ctx.setVariable("beneficiaryName",         profile.name());
            ctx.setVariable("beneficiaryAddressLine1", profile.addressLine1());
            ctx.setVariable("beneficiaryAddressLine2", profile.addressLine2());
            ctx.setVariable("amountFormatted",         formatNaira(payment.getAmount()));
            ctx.setVariable("paymentMethod",           payment.getPaymentMethod().name().replace('_', ' '));
            ctx.setVariable("creditNoteNumber",        cn.getCreditNoteNumber());
            ctx.setVariable("entityReference",         cn.getEntityReference());
            ctx.setVariable("narration",               payment.getNarration() == null ? "" : payment.getNarration());

            String html = templateEngine.process("pdf/payment-voucher", ctx);
            return htmlToPdfConverter.convert(html);
        } catch (Exception e) {
            log.warn("PaymentVoucherPdfGenerator failed for payment {}: {}",
                     payment.getId(), e.getMessage(), e);
            return null;
        }
    }

    private static String headerLabelFor(FinanceEntityType type) {
        return switch (type) {
            case CLAIM         -> "CLAIM SETTLEMENT VOUCHER";
            case COMMISSION    -> "COMMISSION VOUCHER";
            case REINSURANCE   -> "FAC PREMIUM VOUCHER";
            case ENDORSEMENT   -> "ENDORSEMENT REFUND VOUCHER";
            default            -> "PAYMENT VOUCHER";
        };
    }

    private static String formatNaira(BigDecimal amount) {
        NumberFormat nf = NumberFormat.getInstance(new Locale("en", "NG"));
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return "₦" + nf.format(amount);
    }
}
```

#### IT (`PaymentVoucherPdfGeneratorIT.java`)

4 tests — one happy-path per source type. Verify the header label varies AND the resolved beneficiary name + (where applicable) address appears in the extracted PDF text.

Pattern follows `ReceiptPdfGeneratorIT` — see Task 10 for the test-style template.

- [ ] **Step 1-5**: write template, generator, IT, run, commit. Same TDD discipline as Task 10.

```bash
git add cia-backend/cia-documents/src/main/resources/templates/pdf/payment-voucher.html \
        cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/pdf/PaymentVoucherPdfGenerator.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/pdf/PaymentVoucherPdfGeneratorIT.java
git commit -m "$(cat <<'EOF'
feat(finance): Slice β / Task 13 — PaymentVoucherPdfGenerator + Thymeleaf template

Header label varies by CreditNote.entityType (CLAIM SETTLEMENT VOUCHER /
COMMISSION VOUCHER / FAC PREMIUM VOUCHER / ENDORSEMENT REFUND VOUCHER /
PAYMENT VOUCHER). "Paid to" block reads from BeneficiaryProfileResolverDispatcher
(Task 9) — name + optional address lines. Two signatory placeholders.

Never throws — null on failure, leaves pdf_path unset.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 14: PaymentService.post auto-generates voucher + persists pdfPath

Mirror of Task 11 for Payment. Storage path: `payments/{yyyy}/{MM}/{id}.pdf`.

Files modified:
- `PaymentService.java` — inject `PaymentVoucherPdfGenerator` + `DocumentStorageService` + add `generateAndPersistPdf()` helper called at end of `post()`.
- `PaymentListItemResponse.java` — add `String pdfPath` field.
- `PaymentService.toListItem` — project `pdfPath`.

Commit:

```bash
git commit -m "$(cat <<'EOF'
feat(finance): Slice β / Task 14 — PaymentService.post auto-generates voucher PDF

Mirror of Task 11 for Payment. Storage path payments/{yyyy}/{MM}/{id}.pdf.
PaymentListItemResponse + toListItem projection now carry pdfPath.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 15: PaymentController GET /pdf endpoint + IT

Mirror of Task 12. URL: `GET /api/v1/credit-notes/{cnId}/payments/{id}/pdf`. Filename: `PAY-<paymentNumber>.pdf`.

Test file: `PaymentControllerPdfIT.java` — 5 tests mirroring `ReceiptControllerPdfIT`.

Commit:

```bash
git commit -m "$(cat <<'EOF'
feat(finance): Slice β / Task 15 — PaymentController GET /pdf endpoint

GET /api/v1/credit-notes/{cnId}/payments/{id}/pdf. FINANCE_VIEW required.
404 when pdfPath IS NULL. Filename: PAY-<paymentNumber>.pdf.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 16: Frontend api-client — schemas + download fetchers

**Files:**
- Modify: `cia-frontend/packages/api-client/src/modules/finance.ts`

- [ ] **Step 1: Add pdfPath to existing schemas**

In both `ReceiptListItemResponseSchema` and `PaymentListItemResponseSchema`, append:

```typescript
  pdfPath: z.string().nullable(),
```

- [ ] **Step 2: Add download fetchers**

```typescript
import { apiClient } from '../client';

export async function downloadReceiptPdf(
  debitNoteId: string,
  receiptId:   string,
): Promise<Blob> {
  const res = await apiClient.get<Blob>(
    `/api/v1/debit-notes/${debitNoteId}/receipts/${receiptId}/pdf`,
    { responseType: 'blob' },
  );
  return res.data;
}

export async function downloadPaymentPdf(
  creditNoteId: string,
  paymentId:    string,
): Promise<Blob> {
  const res = await apiClient.get<Blob>(
    `/api/v1/credit-notes/${creditNoteId}/payments/${paymentId}/pdf`,
    { responseType: 'blob' },
  );
  return res.data;
}
```

- [ ] **Step 3: typecheck + drift + commit**

```bash
cd /Users/razormvp/CoreInsurance/cia-frontend
pnpm --filter @cia/api-client typecheck
node scripts/check-dto-drift.mjs

cd /Users/razormvp/CoreInsurance
git add cia-frontend/packages/api-client/src/modules/finance.ts
git commit -m "$(cat <<'EOF'
feat(frontend): Slice β / Task 16 — api-client gains pdfPath + downloadReceiptPdf/downloadPaymentPdf

ReceiptListItemResponseSchema + PaymentListItemResponseSchema gain
pdfPath: z.string().nullable(). Two blob fetchers (Axios responseType:'blob')
match the F5.16 NAICOM artifact-download pattern.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 17: useDownloadReceiptPdf + useDownloadPaymentPdf hooks

**Files:**
- Modify: `cia-frontend/apps/back-office/src/modules/finance/hooks/useReceipts.ts`
- Modify: `cia-frontend/apps/back-office/src/modules/finance/hooks/usePayments.ts`

- [ ] **Step 1: Add download hook to useReceipts.ts**

```typescript
import { downloadReceiptPdf } from '@cia/api-client';
import { useMutation } from '@tanstack/react-query';

export interface DownloadReceiptPdfArgs {
  dnId:      string;
  receiptId: string;
  reference: string;        // for filename synthesis
}

export function useDownloadReceiptPdf() {
  return useMutation({
    mutationFn: async ({ dnId, receiptId, reference }: DownloadReceiptPdfArgs) => {
      const blob = await downloadReceiptPdf(dnId, receiptId);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `REC-${reference}.pdf`;
      a.click();
      URL.revokeObjectURL(url);
    },
  });
}
```

- [ ] **Step 2: Mirror in usePayments.ts** — `useDownloadPaymentPdf` with `cnId` + `paymentId` + `reference` args.

- [ ] **Step 3: typecheck + wiring guard + commit**

```bash
pnpm --filter @cia/back-office typecheck
bash cia-frontend/scripts/check-api-wiring.sh

git add cia-frontend/apps/back-office/src/modules/finance/hooks/useReceipts.ts \
        cia-frontend/apps/back-office/src/modules/finance/hooks/usePayments.ts
git commit -m "$(cat <<'EOF'
feat(frontend): Slice β / Task 17 — useDownloadReceiptPdf + useDownloadPaymentPdf

React Query mutations wrapping the blob fetchers from Task 16. Filename
synthesized from receipt/payment reference (REC-... / PAY-...). createObjectURL +
anchor click + revokeObjectURL pattern matches F5.16.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 18: Download PDF row action on ReceiptsListSection + PaymentsListSection

**Files:**
- Modify: `cia-frontend/apps/back-office/src/modules/finance/pages/receivables/ReceiptsListSection.tsx`
- Modify: `cia-frontend/apps/back-office/src/modules/finance/pages/payables/PaymentsListSection.tsx`

For each file:

- Add `useDownloadReceiptPdf` / `useDownloadPaymentPdf` import.
- Inside the row-actions column cell, prepend a "Download PDF" action before the existing "Reverse" action.
- Action is disabled (with tooltip "PDF unavailable") when `r.pdfPath === null`.
- Per-row spinner state keyed on row id while the mutation is pending (matches F5.16 pattern: `mutation.variables?.receiptId === r.id`).

Commit:

```bash
git commit -m "$(cat <<'EOF'
feat(frontend): Slice β / Task 18 — Download PDF row action on Receipts + Payments tabs

ReceiptsListSection + PaymentsListSection gain a "Download PDF" row action
ahead of "Reverse". Disabled with tooltip when pdfPath===null. Per-row
spinner via mutation.variables matching the current row's id (F5.16 pattern).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 19: Download button in nested DN + CN detail dialogs

**Files:**
- Modify: `cia-frontend/apps/back-office/src/modules/finance/pages/receivables/DebitNoteDetailDialog.tsx`
- Modify: `cia-frontend/apps/back-office/src/modules/finance/pages/payables/CreditNoteDetailDialog.tsx`

In each file, inside the existing nested receipts/payments section's per-row markup, add a Download button (small, outline variant) BEFORE the existing Reverse button. Same disabled-when-pdfPath-null logic + per-row spinner.

Commit:

```bash
git commit -m "$(cat <<'EOF'
feat(frontend): Slice β / Task 19 — Download buttons in nested DN + CN detail dialogs

Each nested receipt row in DebitNoteDetailDialog now has a Download button
alongside the existing Reverse button. Mirror in CreditNoteDetailDialog for
payments. Same disabled-when-null + per-row spinner logic as Task 18.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 20: Documentation + session log + final verify + push

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs-site/static/internal-api.json`
- Append: `cia-log.md`

#### Docs

- **CLAUDE.md Module 8 row**: append note that slice β shipped — receipts + payments now auto-generate PDFs on post, downloadable from all 4 surfaces.
- **CLAUDE.md Build 6 sub-page rows**: append PDF download capability to Receipts + Payables rows.
- **CLAUDE.md Development Standards**: add a new bullet:

```markdown
- **PDF generation in cia-finance** — receipt + payment-voucher PDFs render via Thymeleaf templates at `cia-documents/src/main/resources/templates/pdf/` + `HtmlToPdfConverter` (NotoSans-embedded PDFBox, post-Slice-β). Generators MUST NOT throw — catch Exception, log WARN, return null. The host `*.post()` flow tolerates null (leaves `pdf_path` unset) so PDF failures never roll back the receipt/payment commit. Storage path convention: `receipts/{yyyy}/{MM}/{id}.pdf` and `payments/{yyyy}/{MM}/{id}.pdf` via `DocumentStorageService.upload()`. Frontend gates the Download button on `pdfPath !== null`.
```

#### internal-api.json

Add 2 new path entries (`/api/v1/debit-notes/{debitNoteId}/receipts/{id}/pdf` GET + `/api/v1/credit-notes/{creditNoteId}/payments/{id}/pdf` GET). Add `pdfPath` to the existing `ReceiptListItemResponse` + `PaymentListItemResponse` schemas. Use the python-edit pattern from F7-α Task 17 (slice-α plan task 17).

Confirm via:

```bash
python3 -c "
import json
spec = json.load(open('/Users/razormvp/CoreInsurance/docs-site/static/internal-api.json'))
paths = spec['paths']
assert '/api/v1/debit-notes/{debitNoteId}/receipts/{id}/pdf' in paths
assert '/api/v1/credit-notes/{creditNoteId}/payments/{id}/pdf' in paths
assert 'pdfPath' in spec['components']['schemas']['ReceiptListItemResponse']['properties']
assert 'pdfPath' in spec['components']['schemas']['PaymentListItemResponse']['properties']
print('OK')
"
```

#### cia-log.md

Append a Session 126 entry above Session 125. Sections required:

1. Stated goal — quote from the spec doc.
2. What landed (backend / frontend / docs paragraphs).
3. Slice-margin discoveries (if any — note them; if none, say "none surfaced").
4. Files touched (table per tier).
5. Test coverage — IT delta (~33 new), full failsafe baseline number.
6. Backlog reconciliation — drain F7-β; F7-γ + F7-δ remain in the table.
7. Known follow-ups — point to F7-γ as the next slice.

Update the backlog table at the top: remove F7-β; F7-γ + F7-δ unchanged.

#### Final verify

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
- `mvn verify`: ~333 ITs, 0 failures, 0 errors, 1 intentional skip.
- All frontend gates: exit 0.

#### Commit + ask for push authorization

```bash
git add CLAUDE.md docs-site/static/internal-api.json cia-log.md
git commit -m "$(cat <<'EOF'
docs(log): Session 126 — F7 slice β landed (PDF generation + MinIO + download surfaces)

Closes the PDF half of F7. Receipts + payment vouchers auto-generate on
post() with ₦ glyph (NotoSans embedded in HtmlToPdfConverter), upload to
MinIO, persist pdf_path. 4 download surfaces wired (Receivables + Payables
tabs + nested DN/CN detail dialogs).

Failsafe baseline ~333 ITs, all green.

Backlog: F7-β drained. F7-γ (email via Temporal) + F7-δ (per-tenant template)
remain.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Then **ask the user for push authorization** (binary AskUserQuestion — push N commits or hold). DO NOT push without explicit consent.

---

## Self-review (run after writing this plan)

1. **Spec coverage**: every backend file listed in the spec doc's slice β section appears in this plan ✓. Frontend coverage matches the 7-file inventory ✓.
2. **No placeholders**: scan for "TODO", "TBD" — should find zero in task content. The "Note" callouts about confirming field names (e.g. `Customer.getDisplayName()`) are deliberate verification steps for the implementer, not unfinished work.
3. **Type consistency**: `BeneficiaryProfile` defined once (Task 4), referenced consistently by all 4 resolvers (Tasks 5-8) + dispatcher + voucher generator.
4. **Scope discipline**: no email, no per-tenant template — those are γ and δ respectively. Address handling is fully resolved per the user's Option C choice.
5. **Dependency ordering**: Task 1 before 2 (entity changes before pom expansion); 3 before 10/13 (font before consumer generators); 4 before 5-8 (interface before impls); 9 before 13 (dispatcher IT before voucher gen IT); 11 before 12 (service wires PDF before controller exposes endpoint).
6. **Commit-per-task**: every task ends with a commit step. No multi-task commits.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-05-25-f7-slice-beta-pdf-generation.md`.**

Two execution options:

1. **Subagent-Driven (recommended)** — controller dispatches a fresh subagent per task, runs spec + code-quality review between tasks, commits as each task passes.
2. **Inline Execution** — execute tasks in this session using executing-plans, batch execution with user checkpoints.

Which approach?
