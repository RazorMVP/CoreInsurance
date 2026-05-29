# Reports Query Builder — Option A (dynamic per-field projection) design spec

**Status:** Approved by user 2026-05-29 (brainstorm Q1–Q4 + whole-design approval).
**Drains backlog row:** `reports-base-query-table-drift` (P2).
**Related backlog (NOT in scope):** `reports-aggregation-semantics-gap` (P3).

---

## 1. Goal

Make every pre-built SYSTEM report in Module 11 execute correctly against a real
tenant schema. Today **all 59** SYSTEM reports on the affected sources fail at
runtime because `ReportQueryBuilder` queries phantom tables and columns and maps
result columns to report fields by a fragile positional index. This slice rebuilds
the query path for the 6 business data sources via **dynamic per-field projection**
(build the SELECT from each report's declared field keys), fixes a one-word
table-name drift on 2 closures sources, and adds the first integration test that
runs every SYSTEM report against a real database.

## 2. Problem (as investigated 2026-05-29)

`ReportQueryBuilder.BASE_QUERIES` holds one fixed SELECT string per data source. The
6 business sources are broken three ways:

1. **Table drift** — `policy`, `customer`, `class_of_business`, `product`, `claim`,
   `debit_note`, `endorsement`, `ri_allocation`, `reinsurance_treaty` are all
   singular; the real schema is plural (`policies`, `customers`,
   `classes_of_business`, `products`, `claims`, `debit_notes`, `endorsements`,
   `ri_allocations`, `ri_treaties`). The `class_of_business cob` join in the
   closures sources GENERAL_LEDGER and PAA_GROUPS is singular too.
2. **Column drift** — the SELECTs reference columns that do not exist:
   `c.full_name` (customers has `first_name`/`last_name`/`company_name`),
   `p.sum_insured`/`p.premium`/`p.start_date`/`p.end_date`/`p.inception_date`
   (→ `total_sum_insured`/`total_premium`/`policy_start_date`/`policy_end_date`/none),
   `cl.total_paid`/`cl.registered_at` (claims has `approved_amount`/`reported_date`),
   `dn.policy_id` (debit_notes uses `entity_id`/`entity_type`/`entity_reference`),
   `t.name`/`t.type` (ri_treaties has neither — `description`/`treaty_type`),
   `c.channel` (no such column).
3. **Positional-mapping flaw** — `applyComputedFields()` maps `fields[i] ← SELECT
   col[i]` by index. A single fixed per-source SELECT cannot serve reports that
   declare different field subsets/orders (POLICIES alone has
   `Gross Written Premium`=[class,product,premium] vs
   `Policy Register`=[policy_number,customer_name,…] vs
   `Commission Statement`=[customer_name,premium,class]).

**Blast radius:** 55 V18 business reports + 4 V44 closures reports (GENERAL_LEDGER ×3,
PAA_GROUPS ×1, all on the singular `class_of_business` join) = **59 broken**. The
closures *pages* (JournalEntryBrowserPage etc.) are unaffected — they use dedicated
controllers, not the reports engine. `cia-reports` has **zero test directory**, so
nothing ever ran these queries against a real DB.

## 3. Decisions (brainstorm)

| # | Decision | Choice | Rationale |
|---|---|---|---|
| Q1 | Scope of sources | **Both** — Option-A rebuild for the 6 business sources + the `classes_of_business` table-name fix on GENERAL_LEDGER & PAA_GROUPS | Lands all 59 broken reports in one slice; the closures fix is one word per source (no design cost). |
| Q2 | The 3 unsatisfiable fields | `treaty_name` → `COALESCE(t.description, ria.treaty_type)`; `total_paid` → `cl.approved_amount` (proxy); `channel` → `NULL` (general unmapped-key fallback) | Use real columns where available; proxy/NULL where the schema genuinely lacks a source. |
| Q3 | Aggregation semantics | **Defer** | Reports return correctly-keyed, correct-column rows (better than crashing). Config-driven GROUP BY is a distinct, larger redesign already logged P3 (`reports-aggregation-semantics-gap`). One goal per slice. |
| Q4 | Test depth | **Smoke-all 59 + value-subset (~6)** | Smoke-all is the true regression guard against this bug class; per-source value assertions prove the projection maps correctly without fixturing all 59. |

## 4. Architecture — dual model

`ReportQueryBuilder.execute()` branches on data source:

- **6 business sources** (POLICIES, CLAIMS, FINANCE, REINSURANCE, CUSTOMERS,
  ENDORSEMENTS) → **dynamic per-field projection**. For each non-computed field in
  `config.getFields()`, in declared order, look up the field key in the source's
  expression map and emit `<expr> AS <key>`; an unmapped key emits `NULL AS <key>`.
  Append the source's FROM/JOIN/WHERE skeleton, then the existing filter loop, then
  the sanitized ORDER BY. No GROUP BY (aggregation deferred).
- **All other sources** (TRIAL_BALANCE, GENERAL_LEDGER, GL_PERIOD_LOCK, PAA_LRC,
  PAA_GROUPS, IFRS17_MOVEMENT, IFRS9_HOLDINGS, IFRS9_CARRYING, IFRS9_MOVEMENT,
  RM_COMMISSION) → **unchanged** fixed `BASE_QUERIES` + `BASE_QUERY_TAILS`, except
  the `class_of_business` → `classes_of_business` correction in the GENERAL_LEDGER
  and PAA_GROUPS join clauses.

**Why `applyComputedFields()` is unchanged:** because the dynamic SELECT is built in
the report's declared field order, the positional mapping (`fields[i] ← col[i]`)
becomes correct-by-construction for the business sources — the flaw it caused
evaporates without touching the method. Closures sources already align their V44
configs to their fixed SELECT, so positional mapping stays correct there too.

### New data structures (business sources only)

```
// FROM / JOIN / WHERE skeleton per business source.
Map<DataSource, String> SOURCE_FROM

// field key -> SQL expression per business source.
Map<DataSource, Map<String, String>> SOURCE_COLUMNS
```

### Per-source registries

The union of non-computed field keys declared across all V18 reports per source
(plus the keys the custom-report builder can emit) maps as follows. Each business
source is single-table except REINSURANCE (one LEFT JOIN for the treaty label).

**POLICIES** — `FROM policies p WHERE p.deleted_at IS NULL`

| key | expr | key | expr |
|---|---|---|---|
| policy_number | p.policy_number | sum_insured | p.total_sum_insured |
| customer_name | p.customer_name | premium | p.total_premium |
| class_of_business | p.class_of_business_name | status | p.status |
| product_name | p.product_name | start_date | p.policy_start_date |
| created_at | p.created_at | end_date | p.policy_end_date |

**CLAIMS** — `FROM claims cl WHERE cl.deleted_at IS NULL`

| key | expr | key | expr |
|---|---|---|---|
| claim_number | cl.claim_number | reserve_amount | cl.reserve_amount |
| policy_number | cl.policy_number | total_paid | cl.approved_amount (proxy) |
| customer_name | cl.customer_name | registered_at | cl.reported_date |
| class_of_business | cl.class_of_business_name | status | cl.status |
| created_at | cl.created_at | | |

**FINANCE** — `FROM debit_notes dn WHERE dn.deleted_at IS NULL`

| key | expr |
|---|---|
| debit_note_number | dn.debit_note_number |
| policy_number | dn.entity_reference |
| customer_name | dn.customer_name |
| amount | dn.amount |
| status | dn.status |
| due_date | dn.due_date |
| created_at | dn.created_at |

**REINSURANCE** — `FROM ri_allocations ria LEFT JOIN ri_treaties t ON t.id = ria.treaty_id WHERE ria.deleted_at IS NULL`

| key | expr |
|---|---|
| policy_number | ria.policy_number |
| treaty_name | COALESCE(t.description, ria.treaty_type) |
| treaty_type | ria.treaty_type |
| retained_amount | ria.retained_amount |
| ceded_amount | ria.ceded_amount |
| status | ria.status |
| created_at | ria.created_at |

**CUSTOMERS** — `FROM customers c WHERE c.deleted_at IS NULL`

| key | expr |
|---|---|
| full_name | COALESCE(c.company_name, NULLIF(TRIM(CONCAT_WS(' ', c.first_name, c.other_names, c.last_name)), '')) |
| customer_type | c.customer_type |
| channel | NULL (unmapped-key fallback) |
| kyc_status | c.kyc_status |
| created_at | c.created_at |

**ENDORSEMENTS** — `FROM endorsements e WHERE e.deleted_at IS NULL` (no V18 report uses
this source today; mapped for the custom-report builder + correctness)

| key | expr |
|---|---|
| endorsement_number | e.endorsement_number |
| policy_number | e.policy_number |
| customer_name | e.customer_name |
| endorsement_type | e.endorsement_type |
| endorsement_premium | e.premium_adjustment |
| effective_date | e.effective_date |
| status | e.status |
| created_at | e.created_at |

## 5. Filters

The existing filter loop in `execute()` is retained. The helpers
`createdAtCol(ds)`, `statusCol(ds)`, and `hasCobJoin(ds)` are updated for the new
single-table aliases on the business sources:

- `createdAtCol`: POLICIES→`p.created_at`, CLAIMS→`cl.created_at`,
  FINANCE→`dn.created_at`, REINSURANCE→`ria.created_at`, CUSTOMERS→`c.created_at`,
  ENDORSEMENTS→`e.created_at` (closures entries unchanged).
- `statusCol`: POLICIES→`p.status`, CLAIMS→`cl.status`, FINANCE→`dn.status`,
  REINSURANCE→`ria.status`, CUSTOMERS→`c.kyc_status`, ENDORSEMENTS→`e.status`.
- `class_of_business_id` filter resolves to the denormalised `*.class_of_business_id`
  column on POLICIES/CLAIMS/ENDORSEMENTS (no join needed); `product_id` to
  `p.product_id` on POLICIES. `hasCobJoin` is repurposed to "supports the
  class_of_business_id filter" for the business sources (now via the denormalised id
  column, not a join) — closures behaviour unchanged.

`UUID.fromString` / `LocalDate.parse` parameter binding is unchanged. ORDER BY still
runs through `sanitizeColumnName`.

## 6. Closures table-name fix

In `BASE_QUERIES`, the GENERAL_LEDGER and PAA_GROUPS entries change their join from
`LEFT JOIN class_of_business cob ON ...` to `LEFT JOIN classes_of_business cob ON ...`.
No other change to those entries; their V44 configs already align positionally.

## 7. Testing

A new IT (`SystemReportSmokeIT`, cia-api, extends `FinanceWebItSupport` — full
`@SpringBootTest`, singleton Postgres at Flyway target 64 so all 59 definitions are
seeded and every table exists):

1. **Smoke-all** — `SELECT id FROM report_definition WHERE type = 'SYSTEM'`, then for
   each id call `ReportRunnerService.run(request)` with required filters populated
   (a wide `date_from`/`date_to` window covering all seeded + empty data). Assert the
   call throws no exception and returns a non-null row list. With no seeded business
   data the lists are empty, which is the point — the guard is that every base query
   *executes* against the real schema. This is the test that would have caught the
   drift.
2. **Value-subset (~6)** — one representative report per business source. Seed minimal
   rows via JDBC (mirroring `RmCommissionReportIT`'s pattern), run the report, and
   assert the projected columns carry the right values (proves the key→expr mapping,
   not just non-crashing). Representative picks: Policy Register (POLICIES), Claims
   Register (CLAIMS), Debit Note Analysis (FINANCE), RI Premium Bordereaux
   (REINSURANCE), Active Customers (CUSTOMERS) — and one ENDORSEMENTS check via a
   custom-shaped definition or skipped if no SYSTEM report targets it (ENDORSEMENTS
   has no V18 SYSTEM report; assert its base query executes via a direct
   `ReportQueryBuilder` call instead).

The smoke loop supplies `date_from`/`date_to` and lets other filters default to
absent. Across the V18/V44 SYSTEM configs the only `required:true` filters are
`date_from`/`date_to` (verify at plan time); if any report marks a non-date filter
required, the loop must supply a sentinel/seeded value for it so the run is not
rejected by `ReportRunnerService` before the query executes.

## 8. CLAUDE.md / docs updates

- Update the Reports API Design section note about `ReportQueryBuilder` to record the
  dual model (dynamic per-field projection for business sources; fixed BASE_QUERIES
  for closures/aggregate/view sources) and that adding a business-source report field
  now requires a `SOURCE_COLUMNS` entry if the key is new.
- cia-log.md session entry + backlog reconciliation (drain `reports-base-query-table-drift`).

## 9. Backlog reconciliation

- **Drains:** `reports-base-query-table-drift` (P2).
- **Leaves (logged, out of scope):** `reports-aggregation-semantics-gap` (P3),
  and the noted exact-paid-to-date / customer-channel derivations (folded into the
  proxy/NULL resolutions, not separately tracked unless a consumer needs them).
- **No new rows expected**; side-discoveries follow slice discipline.
