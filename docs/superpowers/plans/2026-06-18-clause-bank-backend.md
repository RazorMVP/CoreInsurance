# Clause Bank Backend + Quote/Policy Snapshot (#2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Build the missing **Clause master backend** (entity + table + seed + CRUD API), make quotes and policies **snapshot real clause text** at selection time, render that snapshot on the official (backend) quote + policy PDFs, and rewire the frontend Clause Bank + quote sheets + quote/policy detail to the real API — deleting the frontend mock constants that currently are the only source of clause text. Closes the audit's "Clause Bank mock-only / leaks into the live quote+PDF flow."

**Architecture:** A new `Clause` Setup master-data entity (mirrors `Agent` exactly — entity/repo/service/controller/DTOs/enum/migration, `/api/v1/setup/clauses`, SETUP_* roles, reasoned soft-delete). Quotes and policies gain a **`selected_clauses` JSONB snapshot** (`List<ClauseSnapshot>` = `{id,title,text,type}`) resolved from the selected clause IDs against the clause master at create/edit time — the **point-in-time** model the user confirmed (Decision 1 = A), matching the codebase's denormalized-snapshot pattern. The backend PDFs render the snapshot text (replacing today's stub). The frontend renders clauses from the API/response, and the `INITIAL_CLAUSES` mock constants are deleted.

**Tech Stack:** Java 21 / Spring Boot 3.5.14, JPA `@JdbcTypeCode(SqlTypes.JSON)` for JSONB lists, JUnit 5 + Testcontainers; React + TanStack Query + zod, the existing `useDeleteWithReason`/`ConfirmDeleteDialog` + `RisksEditorDialog` patterns.

**Scope (user-confirmed B = quote + policy in one slice):** Setup Clause CRUD; quote snapshot + quote PDF; policy snapshot (via quote→policy bind AND direct-entry) + policy clause editor + policy document; full frontend rewiring. **Everything ships in this slice — no follow-ups.**

---

## Key shapes (used throughout)

**Backend `ClauseSnapshot`** — a JSON-serialized value object stored in the `selected_clauses` JSONB on both `quotes` and `policies`. Lives in `cia-common` (both cia-quotation and cia-policy depend on it):

```java
// cia-common/.../clause/ClauseSnapshot.java
package com.nubeero.cia.common.clause;

/** Point-in-time snapshot of a selected clause, frozen onto a quote/policy at selection time. */
public record ClauseSnapshot(String id, String title, String text, String type) {}
```

**Frontend `ClauseSnapshotDto`** (api-client) — matches it: `{ id: string; title: string; text: string; type: string }`.

Per-endpoint clause policy: clauses are selected by ID in the request; the backend resolves each ID against the active clause master and snapshots `{id,title,text,type}`. Unknown/deleted IDs are skipped (logged), so a stale ID never breaks a quote/policy.

---

## PHASE 1 — Clause master backend (cia-setup)

### Task 1.1: Clause enums + entity + repository

**Files (new, `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/policy/`):**
- `ClauseType.java`, `ClauseApplicability.java`, `Clause.java`, `ClauseRepository.java`

- [ ] **Step 1: Enums** (match the frontend `clause-types.ts` unions exactly)

```java
// ClauseType.java
package com.nubeero.cia.setup.policy;
public enum ClauseType { STANDARD, EXCLUSION, SPECIAL_CONDITION, WARRANTY }
```
```java
// ClauseApplicability.java
package com.nubeero.cia.setup.policy;
public enum ClauseApplicability { MANDATORY, OPTIONAL }
```

- [ ] **Step 2: Entity** (mirror `Agent.java`; `text` is TEXT; `productIds` is a JSONB list — precedent: `Quote.selectedClauseIds`)

```java
package com.nubeero.cia.setup.policy;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Policy clause master — the clause bank surfaced in Setup → Policy Specifications. */
@Entity
@Table(name = "clauses")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Clause extends BaseEntity {

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ClauseType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClauseApplicability applicability;

    /** Product UUIDs this clause applies to; empty = applies to all products. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "product_ids", columnDefinition = "jsonb")
    @Builder.Default
    private List<String> productIds = new ArrayList<>();
}
```

- [ ] **Step 3: Repository** (mirror `AgentRepository`)

```java
package com.nubeero.cia.setup.policy;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClauseRepository extends JpaRepository<Clause, UUID> {
    Page<Clause> findAllByDeletedAtIsNull(Pageable pageable);
    List<Clause> findAllByDeletedAtIsNull();   // used by the snapshot resolver
}
```

- [ ] **Step 4: Commit** `feat(clause): Clause entity + enums + repository (cia-setup)`

### Task 1.2: Migration + seed

**Files:** Create `cia-backend/cia-api/src/main/resources/db/migration/V72__create_clauses_table.sql` (verify the next free version with `ls db/migration | sort -V | tail` — use the next integer after the highest existing).

- [ ] **Step 1: Table + seed** (mirror `V48__create_agents_table.sql`; seed the 8 clauses currently in the frontend mock, with **fixed UUIDs**, empty `product_ids`)

```sql
CREATE TABLE IF NOT EXISTS clauses (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    title          VARCHAR(200) NOT NULL,
    text           TEXT         NOT NULL,
    type           VARCHAR(30)  NOT NULL DEFAULT 'STANDARD',
    applicability  VARCHAR(20)  NOT NULL DEFAULT 'OPTIONAL',
    product_ids    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by     VARCHAR(255),
    deleted_at     TIMESTAMPTZ,
    CONSTRAINT ck_clauses_type CHECK (type IN ('STANDARD','EXCLUSION','SPECIAL_CONDITION','WARRANTY')),
    CONSTRAINT ck_clauses_applicability CHECK (applicability IN ('MANDATORY','OPTIONAL'))
);
CREATE INDEX idx_clauses_active ON clauses (deleted_at) WHERE deleted_at IS NULL;

-- Seed the clause bank (was the frontend INITIAL_CLAUSES mock). product_ids empty = applies to all.
INSERT INTO clauses (id, title, text, type, applicability) VALUES
  ('00000000-0000-0000-0000-0000000000c1','Third Party Liability','Indemnity for third party bodily injury and property damage as per the Motor Vehicles (Third Party Insurance) Act.','STANDARD','MANDATORY'),
  ('00000000-0000-0000-0000-0000000000c2','Own Damage','Covers accidental damage to the insured vehicle including fire, theft and malicious damage.','STANDARD','MANDATORY'),
  ('00000000-0000-0000-0000-0000000000c3','Exclusion — Racing','This policy does not cover loss or damage arising from or whilst the vehicle is used in racing, rallying or similar events.','EXCLUSION','OPTIONAL')
  -- ... include all 8 from clauses-shared.ts / ClauseBankTab INITIAL_CLAUSES, verbatim text, deterministic UUIDs ...
;
```

The fixed UUIDs `…00c1`/`…00c2` etc. let any existing demo quote whose `selected_clause_ids` are `'c1'`/`'c2'` still resolve **only if** those legacy values are migrated; legacy short IDs (`'c1'`) will NOT match the UUIDs — acceptable in pre-launch (demo data), and new quotes carry the UUIDs. Note this in the cia-log.

- [ ] **Step 2: Migration IT** — add a constraints/seed IT under `cia-api/src/test/.../migration/` mirroring an existing `V48`-style test if one exists; otherwise assert the seed count via a Testcontainers check. **Bump the flyway target** in any IT support base that pins a target below this migration only if those ITs must see the table (the Clause CRUD IT pins its own target).

- [ ] **Step 3: Commit** `feat(clause): V72 clauses table + seed`

### Task 1.3: DTOs + service + controller (mirror Agent)

**Files (new):** `dto/ClauseRequest.java`, `dto/ClauseResponse.java`, `ClauseService.java`, `ClauseController.java` (all in `cia-setup/.../policy/`).

- [ ] **Step 1: DTOs** — `ClauseRequest` (`@NotBlank title`, `@NotBlank text`, `@NotNull ClauseType type`, `@NotNull ClauseApplicability applicability`, `List<String> productIds`); `ClauseResponse` (`id,title,text,type,applicability,productIds,createdAt,updatedAt`) — mirror `AgentRequest`/`AgentResponse`.

- [ ] **Step 2: `ClauseService`** — copy `AgentService` verbatim, swap fields; CRUD + `delete(id, reason)` via `auditService.logWithReason("Clause", …)`. Add a method the quote/policy modules will use:

```java
@Transactional(readOnly = true)
public List<ClauseSnapshot> snapshot(List<String> clauseIds) {
    if (clauseIds == null || clauseIds.isEmpty()) return List.of();
    Map<String, Clause> byId = repository.findAllByDeletedAtIsNull().stream()
            .collect(Collectors.toMap(c -> c.getId().toString(), c -> c));
    return clauseIds.stream()
            .map(byId::get)
            .filter(Objects::nonNull)   // skip unknown/deleted ids
            .map(c -> new ClauseSnapshot(c.getId().toString(), c.getTitle(), c.getText(), c.getType().name()))
            .toList();
}
```

- [ ] **Step 3: `ClauseController`** — copy `AgentController`, path `/api/v1/setup/clauses`, SETUP_VIEW/CREATE/UPDATE/DELETE, `?reason=` on delete. `@PageableDefault(size = 2000)` (clauses are a small master list the FE fetches whole).

- [ ] **Step 4: Controller IT** — mirror an existing setup controller IT: list returns the seeded clauses; create → 201; delete?reason → 200 + soft-deleted; RBAC 403 without role.

- [ ] **Step 5: Commit** `feat(clause): Clause CRUD service + /api/v1/setup/clauses controller + IT`

---

## PHASE 2 — Quote clause snapshot (cia-quotation)

### Task 2.1: `selected_clauses` on quotes + snapshot at create

**Files:** new migration `V73__quote_selected_clauses.sql`; modify `Quote.java`, `QuoteService.java`, `QuoteRequest`/`QuoteResponse`, `QuotePdfService.java`. (`ClauseSnapshot` lives in cia-common — Task created it under Phase 1 prep; if not yet, create it now.)

- [ ] **Step 1: Migration** — `ALTER TABLE quotes ADD COLUMN selected_clauses JSONB NOT NULL DEFAULT '[]'::jsonb;`

- [ ] **Step 2: Quote entity** — add the snapshot column:

```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "selected_clauses", columnDefinition = "jsonb")
@Builder.Default
private List<ClauseSnapshot> selectedClauses = new ArrayList<>();
```

- [ ] **Step 3: QuoteService** — inject `ClauseService` (cia-quotation already depends on cia-setup for products/classes; confirm and add the dep if missing). At create (and any clause-affecting update), set `.selectedClauses(clauseService.snapshot(request.getSelectedClauseIds()))` alongside the existing `selectedClauseIds`.

- [ ] **Step 4: QuoteResponse** — add `private List<ClauseSnapshot> selectedClauses;` and map it in `toResponse`.

- [ ] **Step 5: QuotePdfService** — replace the stub (lines ~127-131) to render the snapshot text:

```java
if (!q.getSelectedClauses().isEmpty()) {
    sb.append("<h2>Applicable Clauses</h2>");
    int i = 1;
    for (ClauseSnapshot c : q.getSelectedClauses()) {
        sb.append("<p style=\"font-weight:bold;\">").append(i++).append(". ")
          .append(escape(c.title())).append("</p>")
          .append("<p style=\"font-size:9pt;color:#444;\">").append(escape(c.text())).append("</p>");
    }
}
```
(Use the existing HTML-escape helper in QuotePdfService if present; if none, add a minimal one — do not introduce an XSS hole by interpolating raw clause text.)

- [ ] **Step 6: Test** — a cia-api IT: create a quote with `selectedClauseIds` = seeded clause UUIDs → assert the QuoteResponse `selectedClauses` carries the resolved title+text, and the generated quote PDF bytes contain the clause title. (Mirror an existing QuotePdf IT.)

- [ ] **Step 7: Commit** `feat(clause): snapshot selected clauses onto quotes + render on quote PDF`

---

## PHASE 3 — Policy clause snapshot (cia-policy)

### Task 3.1: `selected_clauses` on policies + bind + direct-entry + edit endpoint

**Files:** migration `V74__policy_selected_clauses.sql`; modify `Policy.java`, `PolicyService.java`, `PolicyRequest`/`PolicyResponse`, add a `PUT /api/v1/policies/{id}/clauses` to `PolicyController`.

- [ ] **Step 1: Migration** — `ALTER TABLE policies ADD COLUMN selected_clauses JSONB NOT NULL DEFAULT '[]'::jsonb;`
- [ ] **Step 2: Policy entity** — add the same `selectedClauses` JSONB field as the quote.
- [ ] **Step 3: `bindFromQuote`** — after coinsurance copy (PolicyService ~line 194), `policy.setSelectedClauses(new ArrayList<>(quote.getSelectedClauses()));` (carry the frozen snapshot forward verbatim — do NOT re-resolve, so the policy matches the issued quote).
- [ ] **Step 4: Direct `create`** — `PolicyRequest` gains `private List<String> selectedClauseIds;`. Inject `ClauseService` into `PolicyService`; in `create`, `.selectedClauses(clauseService.snapshot(request.getSelectedClauseIds()))`.
- [ ] **Step 5: Edit endpoint** — `PolicyService.updateClauses(UUID policyId, List<String> clauseIds)` re-snapshots + saves + audits UPDATE; `PUT /api/v1/policies/{id}/clauses` (`hasRole('POLICY_UPDATE')`, body `{ selectedClauseIds: [...] }`). (Check the actual policy update role used by RisksEditorDialog's PUT endpoints and match it.)
- [ ] **Step 6: PolicyResponse** — add `private List<ClauseSnapshot> selectedClauses;` + map in `toResponse`.
- [ ] **Step 7: Test** — cia-api ITs: (a) bind a quote-with-clauses → policy carries the same snapshot; (b) `PUT /clauses` re-snapshots; (c) direct create with clause IDs snapshots.
- [ ] **Step 8: Commit** `feat(clause): snapshot clauses onto policies (bind + direct + edit endpoint)`

### Task 3.2: Policy document renders clauses

**Files:** `PolicyDocumentContext.java`, `DocumentGenerationServiceImpl.java`, the policy Thymeleaf template, `PolicyService.approve` call site.

- [ ] **Step 1:** Add `List<ClauseSnapshot> clauses` to `PolicyDocumentContext`.
- [ ] **Step 2:** In `generatePolicyDocument`, pass clauses into the template model (a rendered `clausesHtml` string built from the snapshot, or an iterable the template loops). Update the POLICY Thymeleaf template (find it under `cia-documents/src/main/resources/templates/`) to render an "Applicable Clauses" section from the model. Escape clause text.
- [ ] **Step 3:** `PolicyService.approve` — pass `saved.getSelectedClauses()` into the `PolicyDocumentContext` constructor.
- [ ] **Step 4: Test** — extend a policy-document IT (or add one): approve a policy with clauses → the generated PDF/HTML contains the clause titles.
- [ ] **Step 5: Commit** `feat(clause): render clauses on the policy document`

---

## PHASE 4 — Frontend rewiring (delete the mocks)

### Task 4.1: api-client — ClauseDto + hooks + snapshot on Quote/Policy

**Files:** `cia-frontend/packages/api-client/src/modules/setup.ts` (+ wherever QuoteDto / PolicyDto live).

- [ ] **Step 1:** Add to `setup.ts`: `ClauseType`, `ClauseApplicability`, `ClauseDto` (`id,title,text,type,applicability,productIds,createdAt,updatedAt` — **no** `productNames`; that's a frontend display derivation, so no drift). Export a `ClauseSnapshotDto = { id; title; text; type }`.
- [ ] **Step 2:** Add `selectedClauses?: ClauseSnapshotDto[]` to `QuoteDto` and `PolicyDto`. Since backend `QuoteResponse`/`PolicyResponse` gained `selectedClauses` in Phases 2/3, this keeps `check-dto-drift.mjs` green (both sides move together). Run `node cia-frontend/scripts/check-dto-drift.mjs` — expect no drift.
- [ ] **Step 3: Commit** `feat(clause): api-client ClauseDto + selectedClauses on Quote/Policy DTOs`

### Task 4.2: Setup Clause Bank tab → real API

**Files:** `ClauseBankTab.tsx`, `ClauseSheet.tsx` (its add/edit sheet), `clause-types.ts`.

- [ ] **Step 1:** Rewrite `ClauseBankTab` to mirror `AgentsTab` (OrganisationsPage): `useQuery(['setup','clauses'])` → `GET /api/v1/setup/clauses`; map `ClauseDto[]` → `ClauseRow[]` (derive `productNames` by joining `productIds` against a products `useQuery`); `useDeleteWithReason({ endpoint: id => /api/v1/setup/clauses/${id}, invalidateKey: ['setup','clauses'], entityLabel: 'Clause', entityName: c => c.title })`; the add/edit Sheet uses `useMutation` POST/PUT (mirror `AgentSheet`). **Delete the `INITIAL_CLAUSES` mock constant.**
- [ ] **Step 2:** Run `bash cia-frontend/scripts/check-api-wiring.sh` — the un-flagged `INITIAL_CLAUSES` in ClauseBankTab is now gone.
- [ ] **Step 3: Commit** `feat(clause): wire Setup Clause Bank tab to /api/v1/setup/clauses`

### Task 4.3: Quote sheets + detail + PDF → backend clauses

**Files:** `SingleRiskQuoteSheet.tsx`, `MultiRiskQuoteSheet.tsx`, `clauses-shared.ts`, `QuoteDetailPage.tsx`, `QuotePdfPreview.tsx`.

- [ ] **Step 1:** In both quote sheets, replace `import { INITIAL_CLAUSES } from '../clauses-shared'` + the `.filter(...)` over the mock with a `useQuery(['setup','clauses'])` fetch of `ClauseDto[]`; keep submitting `selectedClauseIds` (now real clause UUIDs).
- [ ] **Step 2:** `QuoteDetailPage.tsx:240` — render `q.selectedClauses` (from the response) instead of `INITIAL_CLAUSES.filter(...)`.
- [ ] **Step 3:** `QuotePdfPreview.tsx` — render `data.selectedClauses` instead of the mock filter (both the React preview and `buildPrintHtml`).
- [ ] **Step 4:** **Delete `clauses-shared.ts`** (the mock) once no importers remain. Run `check-api-wiring.sh` + `tsc`/build.
- [ ] **Step 5: Commit** `feat(clause): quote sheets/detail/PDF render backend clauses; delete clauses-shared mock`

### Task 4.4: Policy detail clause editor

**Files:** new `ClausesEditorDialog.tsx`; modify `PolicyDetailPage.tsx`; optionally `CreatePolicySheet.tsx`.

- [ ] **Step 1:** `PolicyDetailPage` — render `p.selectedClauses` (from the policy response) in the Document tab clause block instead of the inline `mockPolicy.clauses`; remove the `clauses` field from the `MockPolicy` mock fallback (or point it at `[]`). The dead "+ Add Clause" / "Edit" buttons become a single "Edit Clauses" that opens the new dialog.
- [ ] **Step 2:** `ClausesEditorDialog.tsx` — mirror `RisksEditorDialog` but simpler: fetch the clause bank (`useQuery(['setup','clauses'])`), show checkboxes pre-checked from the policy's current `selectedClauses` ids, and on save `PUT /api/v1/policies/${policyId}/clauses` with `{ selectedClauseIds }`; invalidate `['policies', policyId]`.
- [ ] **Step 3 (parity):** `CreatePolicySheet` direct-entry — add an optional clause multi-select (mirror the quote sheet's clause block) submitting `selectedClauseIds`. The "from approved quote" tab needs nothing (clauses carry from the quote on bind).
- [ ] **Step 4:** Run `check-api-wiring.sh`, `check-dto-drift.mjs`, build. **Vitest** (the back-office app has it): add a small component test for `ClausesEditorDialog` (renders the bank, PUTs the selected ids) mirroring an existing finance vitest.
- [ ] **Step 5: Commit** `feat(clause): policy clause editor + render backend clauses on policy detail`

---

## PHASE 5 — Docs + backlog

### Task 5.1: CLAUDE.md + cia-log + backlog

- [ ] **Step 1: CLAUDE.md** — Module 1 Setup feature line (+ Clause Bank as real master data); a §Quotation/Policy note that clauses are snapshotted point-in-time onto quotes/policies and rendered on the official PDFs; the `clauses` table in the data-architecture migration list.
- [ ] **Step 2: cia-log** — a `## 2026-06-18 — Clause Bank backend + quote/policy snapshot — COMPLETE` entry: the new Clause master, the JSONB snapshot model (point-in-time, Decision 1=A), the PDF rendering on both quote + policy, the frontend rewiring + **deletion of both `INITIAL_CLAUSES` mocks** (closing the un-flagged `check-api-wiring.sh` evasion by construction), and the legacy-short-id non-resolution note. Backlog reconciliation: remove the audit's clause-bank item; **note the `check-api-wiring.sh` regex only matches `mockX`/`MOCK_X` (the clause mock evaded it by naming) — broadening it to all-caps data constants would false-positive on legit constants (`CLAUSE_TYPES`, `PRODUCTS`), so the correct closure is the mock deletion done here, not a guard change.**
- [ ] **Step 3: Commit** `docs(clause): CLAUDE.md + cia-log + backlog`

---

## Self-Review

**Spec coverage:** Clause master backend (1.1-1.3) ✓; quote snapshot + quote PDF (2.1) ✓; policy snapshot via bind + direct + edit (3.1) + policy document (3.2) ✓; frontend Setup tab (4.2) + quote sheets/detail/PDF (4.3) + policy detail editor (4.4) ✓; both mocks deleted (4.2, 4.3) ✓; DTO-drift kept green by moving both sides together (4.1) ✓; docs/backlog (5.1) ✓.

**Type consistency:** `ClauseSnapshot(id,title,text,type)` is identical in cia-common (backend) and `ClauseSnapshotDto` (frontend); `selectedClauses` added to Quote+Policy entities, both Responses, and both Dtos together; `ClauseType`/`ClauseApplicability` enums match the frontend `clause-types.ts` unions verbatim; `/api/v1/setup/clauses` + SETUP_* roles consistent with the Agent template.

**No deferrals:** policy direct-entry clause selection (4.4 Step 3) is built, not deferred; the CI-guard-regex question is resolved-by-decision (mock deletion closes it; broadening would regress) and documented as such in 5.1, not punted.

**Open verification points to resolve during execution (not deferrals — confirm-while-building):** (1) confirm cia-quotation + cia-policy already depend on cia-setup (for `ClauseService`); add the Maven dep if not. (2) Confirm the exact policy-update role on the existing risk PUT endpoints and reuse it for `PUT /clauses`. (3) Find the POLICY Thymeleaf template path before editing it (3.2). (4) Pick the next free Flyway version (V72+) by listing the migration dir. (5) Confirm QuotePdfService has an HTML-escape helper; if not, add one (no raw clause-text interpolation).
