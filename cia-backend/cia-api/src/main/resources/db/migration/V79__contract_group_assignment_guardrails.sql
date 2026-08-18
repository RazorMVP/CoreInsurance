-- ─────────────────────────────────────────────────────────────────────────────
-- V79 — Restore contract_group_assignment NOT NULL / DEFAULT guardrails
--
-- FAC / IFRS-17 PAA workstream, final-review fix (Minor 1).
--
-- V77 generalised V37's policy_group_assignment into the polymorphic
-- contract_group_assignment, but in doing so RELAXED four DB-level
-- guardrails V37 enforced:
--   • id            lost  DEFAULT gen_random_uuid()
--   • assigned_at   lost  NOT NULL and DEFAULT now()
--   • created_at    lost  NOT NULL and DEFAULT now()
--   • updated_at    lost  NOT NULL and DEFAULT now()
--
-- The JPA layer populates all four (BaseEntity @CreatedDate/@GeneratedValue
-- auditing + ContractGroupingService.assign sets assigned_at; the entity even
-- declares assigned_at nullable=false), so there is no runtime break today —
-- ddl-auto=none and every insert goes through JPA. But the DB no longer backs
-- those invariants, so a future raw-SQL insert path could leave them NULL
-- where V37 would have defaulted or rejected. This migration re-asserts the
-- guardrails at the DB layer.
--
-- Safe to apply: every existing row is JPA-populated (assigned_at/created_at/
-- updated_at non-null) — the V77 backfill copied them from
-- policy_group_assignment, where V37 enforced NOT NULL DEFAULT now(), and all
-- subsequent inserts go through JPA — so SET NOT NULL succeeds without a
-- backfill. NEVER edits V77 — additive ALTERs only.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE contract_group_assignment
    ALTER COLUMN id          SET DEFAULT gen_random_uuid(),
    ALTER COLUMN assigned_at SET DEFAULT now(),
    ALTER COLUMN assigned_at SET NOT NULL,
    ALTER COLUMN created_at  SET DEFAULT now(),
    ALTER COLUMN created_at  SET NOT NULL,
    ALTER COLUMN updated_at  SET DEFAULT now(),
    ALTER COLUMN updated_at  SET NOT NULL;
