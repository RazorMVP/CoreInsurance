# B2 — Relationship-Manager commission via 2520 (design spec)

**Status:** Approved by user 2026-05-29 (brainstorm Q1–Q4 + the Dr-account decision + whole-design approval).
**Drains backlog row:** `B2` (RM commission via 2520 + per-policy RM attribution).
**Open Question #11** (per-policy RM attribution) — resolved by this slice.

---

## 1. Goal

Pay Relationship Managers (RMs) a commission on the direct-channel policies they own, accrued to the general ledger as a staff payable and disbursed via external payroll. A direct policy (no broker, no agent) whose customer has an assigned RM and whose product carries an RM commission rate is stamped at creation with `commissionSourceType = RELATIONSHIP_MANAGER` + a snapshot of the customer's RM and the RM rate. On approval the GL accrues the incentive; a per-RM report tells payroll what each RM is owed.

## 2. Decisions (brainstorm)

| # | Decision | Choice | Rationale |
|---|---|---|---|
| Q1 | RM commission vs broker/agent | **Exclusive third arm** | RM is one of broker / agent / RM — mutually exclusive. Reuses the single `commissionSourceType` slot (the `RELATIONSHIP_MANAGER` enum value already exists). Implication: **RMs earn only on direct-channel policies** (if a broker/agent is on the policy, commission goes to them). |
| Q2 | Payout mechanism | **GL accrual only** | Dr 5130 / Cr 2520 journal entry on approval. **No CreditNote, no in-app payment.** RMs are internal staff paid via external payroll — not external vendors invoiced through Payables. |
| Q3 | Source trigger + attribution | **Auto-derived from customer** | Direct policy + customer has RM + `CommissionSetup(product, RELATIONSHIP_MANAGER)` effective → source=RM, snapshot the customer's RM (id + name) + the RM rate onto the policy. No new picker UI; mirrors the broker/agent snapshot. |
| Q4 | Reporting scope | **Accrual + per-RM report** | A SYSTEM report (Flyway data migration, Module 11) aggregating accrued RM commission by RM over a period, so payroll knows what to disburse. CSV export comes free; no bespoke payroll export. |
| Dr | Debit account for the accrual | **5130 Insurance acquisition expense** | The *nature of the cost* drives the expense account: a commission is an acquisition cost whether paid to an external producer or an internal RM. Only the credit differs by payee type (2520 Staff payables for the internal RM, vs 2320/2330 Commission payable for brokers/agents). |

## 3. Scope

### IN scope
1. V62 migration: `policies.relationship_manager_id` (UUID, FK → `relationship_managers`) + `relationship_manager_name` (VARCHAR(200), snapshot); replace the broker-xor-agent CHECK with a 3-way invariant.
2. RM-source auto-derivation in the policy-creation flow (mirror the broker/agent snapshot).
3. New `posting_rule` row `POLICY_COMMISSION_RM` (Dr 5130 / Cr 2520) + an RM branch in `SubledgerPostingService.replayPolicyApproved()`.
4. Per-RM commission SYSTEM report (Flyway data migration into `report_definition`, Module 11).
5. Minimal policy-detail display of RM as the commission source (confirm whether the Financial tab is already generic over `commissionSourceType`).
6. Integration tests (creation derivation, the 3-way CHECK, the GL posting, the no-CreditNote regression, the report aggregation).

### OUT of scope (explicit)
- Any CreditNote / payable / in-app payment / voucher / PDF / email / SMS for RM (Q2 = accrual only). `PolicyCommissionCreditNoteListener` stays untouched — it already skips RM.
- RM earning on broker/agent business (Q1 = exclusive → direct-channel only).
- A per-policy RM override / RM-picker UI (Q3 = auto from customer). The policy's RM is always the customer's RM at creation time.
- A payroll-system integration or bespoke export beyond the report's existing Module 11 CSV (Q4 = report, not a dedicated export).
- The disbursement itself and the matching **Dr 2520** on payment — that happens in payroll, outside this system. This slice only **accrues** the liability.
- Endorsement / renewal RM commission re-accrual — out of scope for v1 (the accrual fires once, on initial policy approval, like broker/agent commission).

## 4. Data model + migration (V62)

```sql
-- V62__add_relationship_manager_to_policies.sql
ALTER TABLE policies ADD COLUMN relationship_manager_id   UUID;
ALTER TABLE policies ADD COLUMN relationship_manager_name VARCHAR(200);

ALTER TABLE policies
  ADD CONSTRAINT fk_policies_relationship_manager
  FOREIGN KEY (relationship_manager_id) REFERENCES relationship_managers (id);

-- Replace the broker-xor-agent CHECK with a 3-way invariant:
--   (a) at most one of broker_id / agent_id / relationship_manager_id is non-null
--   (b) when commission_source_type = 'RELATIONSHIP_MANAGER', relationship_manager_id is non-null
ALTER TABLE policies DROP CONSTRAINT ck_policies_broker_xor_agent;
ALTER TABLE policies
  ADD CONSTRAINT ck_policies_commission_source_one
  CHECK (
    (CASE WHEN broker_id               IS NOT NULL THEN 1 ELSE 0 END)
  + (CASE WHEN agent_id                IS NOT NULL THEN 1 ELSE 0 END)
  + (CASE WHEN relationship_manager_id IS NOT NULL THEN 1 ELSE 0 END) <= 1
  );
ALTER TABLE policies
  ADD CONSTRAINT ck_policies_rm_source_requires_rm
  CHECK (commission_source_type <> 'RELATIONSHIP_MANAGER' OR relationship_manager_id IS NOT NULL);
```

> The exact CHECK names/shape match against the actual existing `ck_policies_broker_xor_agent` definition at plan time (read V53/V54 + the Policy entity to confirm the current constraint text + column names before writing V62). The intent is fixed: at-most-one source + RM-source-requires-RM.

- `Policy` entity gains `relationshipManagerId` (UUID) + `relationshipManagerName` (String) fields, mirroring the broker/agent snapshot fields (V53).
- **No change** to `CommissionSourceType` (RELATIONSHIP_MANAGER exists) or `CommissionSetup` (already supports the RM source + rate).

## 5. Source derivation (policy creation)

Mirror the existing broker/agent snapshot in the policy-creation service. Derivation order:

1. Broker present → `commissionSourceType = BROKER`, snapshot broker.
2. Else agent present → `AGENT`, snapshot agent.
3. Else if the customer has a `relationship_manager_id` **and** a `CommissionSetup(product, RELATIONSHIP_MANAGER)` is effective for the policy date → `RELATIONSHIP_MANAGER`; snapshot `customer.relationshipManagerId` + the RM's name + the RM rate (`commissionRate`) onto the policy.
4. Else no commission (source null, as today for direct policies with no RM / no setup).

The RM rate is **frozen** on the policy at creation (like broker/agent), so later `CommissionSetup` rate changes don't retro-alter booked accruals.

## 6. GL posting

- New seed row in `posting_rule` (Flyway data migration): event type `POLICY_COMMISSION_RM`, **Dr 5130 (Insurance acquisition expense) / Cr 2520 (Staff payables)**.
- `SubledgerPostingService.replayPolicyApproved()` gains an RM branch: when `commissionSourceType == RELATIONSHIP_MANAGER`, post `premium × commissionRate` through the `POLICY_COMMISSION_RM` rule, reading the RM snapshot off the policy. Mirrors the existing BROKER/AGENT branches (which post Dr 5130 / Cr 2320|2330).
- Accrual basis + timing: **gross written premium at approval × frozen RM rate**, posted once on initial policy approval — identical basis/timing to broker/agent commission.
- `PolicyCommissionCreditNoteListener` is **unchanged**: it already skips RM (the comment "RM commission is a staff payroll incentive, not a commission CN" is now realised by this accrual-only path). No payable, no payment, no beneficiary resolvers.

## 7. Per-RM commission report (Module 11)

A SYSTEM report added via a Flyway data migration (INSERT into `report_definition`) — code change only if a new `DataSource` enum value / base query is required (confirm at plan time whether an existing data source covers a policy-grouped query, or a new one is needed).

- **Base query:** policies `WHERE commission_source_type = 'RELATIONSHIP_MANAGER'` and approval date within the filter period, `GROUP BY relationship_manager_id`.
- **Columns:** RM name, policy count, total premium, **total accrued** (Σ premium × frozen rate).
- **Category:** Finance (a staff-payable accrual view).
- **Period filter:** policy approval date within range.
- Surfaces automatically in the existing Report Library / Viewer (Module 11 renders any `report_definition`) with CSV export — **no new frontend page**.

## 8. Frontend

Minimal. The policy-detail Financial tab already renders commission; ensure it shows "Commission source: Relationship Manager — {name}" when source = RM (likely already generic over `commissionSourceType` — confirm at plan time; if generic, zero frontend change beyond a label mapping). No new page; the report is auto-surfaced by Module 11.

## 9. Testing (integration)

1. **Derivation — RM picked:** direct policy (no broker/agent) + customer has RM + effective `CommissionSetup(product, RM)` → policy created with `commissionSourceType = RELATIONSHIP_MANAGER` + RM id/name/rate snapshot.
2. **Derivation — RM not picked:** (a) broker present → BROKER (RM ignored even if customer has one); (b) agent present → AGENT; (c) customer has no RM → no commission; (d) no RM `CommissionSetup` → no commission.
3. **3-way CHECK:** inserting a policy with two of {broker_id, agent_id, relationship_manager_id} non-null is rejected; `commissionSourceType = RELATIONSHIP_MANAGER` with null `relationship_manager_id` is rejected.
4. **GL posting:** approving an RM-sourced policy posts a journal entry **Dr 5130 / Cr 2520 = premium × rate** (reconciliation assertion on the amount).
5. **No-CreditNote regression:** approving an RM-sourced policy creates **no** CreditNote (the listener skips RM).
6. **Report:** the per-RM report aggregates accrued commission correctly by RM over a period (RM name, policy count, total accrued = Σ premium × rate).

## 10. CLAUDE.md updates

- Module 1 / Module 3 commission note: RM is now an operationalised third commission source (direct-channel only), accrued to the GL (Dr 5130 / Cr 2520), no CreditNote.
- A Development Standards note (or extend the existing commission note) recording the three-source posting split (same Dr 5130 expense; Cr 2320 brokers / 2330 agents / 2520 staff-payable RM) and the accrual-only (no-payable) RM treatment.
- Module 11: the new per-RM commission SYSTEM report in the report inventory + count.

## 11. Backlog reconciliation

- **Drains:** `B2` (RM commission via 2520 + per-policy RM attribution) — and resolves Open Question #11 (per-policy RM attribution → snapshot on the policy).
- **No new rows expected**; any side-discoveries during execution follow slice discipline (logged to the backlog, not absorbed).
