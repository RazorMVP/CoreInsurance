# Trial Balance Reconciliation Evidence

`reconciliation-evidence.json` is the deterministic output of the
**100-JE reconciliation test** in
[`TrialBalanceServiceIT.hundredJournalEntriesReconcile`](../../../java/com/nubeero/cia/api/finance/gl/TrialBalanceServiceIT.java).

## What it proves

After randomly posting 100 balanced journal entries with seed `Random(42L)`
through `JournalEntryService.post` and aggregating via
`TrialBalanceService.trialBalanceAsOf`, the GL nets to **exactly zero**:

- `totalDebits == totalCredits == 505263.29`
- `lineCount == 200` (2 lines per JE × 100 JEs)
- `balanced == true`
- 13 distinct accounts touched (per `ACCOUNT_PAIRS`)

## How it stays in sync

The IT writes this file every time it runs (locally via Testcontainers or
on CI). Because the seed and account pairings are fixed, the output is
byte-stable across machines. **If a future change drifts these values, the
IT failure surfaces the new file as a git-diff** rather than as an opaque
assertion failure — reviewers can see the per-account delta directly.

The seed value `42L`, the eight `ACCOUNT_PAIRS`, the amount formula
(`BigDecimal.valueOf(100L + r.nextInt(990_001), 2)`), and the `asOf`
business date (2026-05-14) collectively determine the file. Touching any
of these is a deliberate design change and the regenerated file should be
committed in the same change.

## Don't hand-edit

Treat this file as a generated artefact. Any manual edits will be
overwritten by the next CI run.
