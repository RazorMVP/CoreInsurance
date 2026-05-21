// ── Finance — Closures (Module 12, Slice 1.6 + 1.7) ──────────────────────
//
// Wire shapes for the Period-End Closures backend. Field names mirror the
// canonical Java DTOs (FiscalYearResponse, FiscalPeriodResponse,
// PeriodLockResponse, LockReportEntry) in cia-finance/src/main/java/com/
// nubeero/cia/finance/dto/.
//
// Schemas are the source of truth — derive types via z.infer<typeof T>.
// Fetch with validatedGet so backend rename drift fails loudly at runtime.

import { z } from 'zod';

// ── Enums ─────────────────────────────────────────────────────────────────

export const FiscalYearStatusSchema   = z.enum(['PLANNING', 'ACTIVE', 'CLOSED']);
export const FiscalPeriodTypeSchema   = z.enum(['DAY', 'MONTH', 'QUARTER', 'HALF_YEAR', 'YEAR']);
export const FiscalPeriodStatusSchema = z.enum(['OPEN', 'SOFT_CLOSED', 'HARD_CLOSED', 'REOPENED']);
export const LockTypeSchema           = z.enum(['SOFT', 'HARD']);

export const AccountTypeSchema = z.enum(['ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE']);

export const Ifrs17RoleSchema = z.enum([
  'LRC_BEL', 'LRC_RA', 'LRC_LC',
  'LIC_OCR', 'LIC_IBNR', 'LIC_RA', 'LIC_CHE',
  'LRC_REINSURANCE', 'LIC_REINSURANCE',
  'REVENUE_LRC_RELEASE', 'REVENUE_ACQ_RECOVERY', 'REVENUE_RA_RELEASE', 'REVENUE_EXP_ADJ',
  'REINSURANCE_RECOVERY',
  'INCURRED_CLAIMS', 'LIC_CHANGE', 'ACQ_EXPENSE', 'OTHER_DIRECT_EXPENSE', 'LC_CHANGE',
  'REINSURANCE_PREMIUM', 'REINSURANCE_LRC_CHANGE',
  'INSURANCE_FINANCE_EXPENSE', 'INSURANCE_FINANCE_OCI',
]);

export const Ifrs9RoleSchema = z.enum([
  'FVPL', 'FVOCI_DEBT', 'FVOCI_EQUITY', 'AMORTISED_COST',
  'ECL_ALLOWANCE', 'ECL_EXPENSE',
  'OCI_DEBT_RESERVE', 'OCI_EQUITY_RESERVE',
  'INTEREST_AC', 'INTEREST_FVOCI',
  'FVPL_GAINS', 'FVPL_LOSSES',
]);

export type FiscalYearStatus   = z.infer<typeof FiscalYearStatusSchema>;
export type FiscalPeriodType   = z.infer<typeof FiscalPeriodTypeSchema>;
export type FiscalPeriodStatus = z.infer<typeof FiscalPeriodStatusSchema>;
export type LockType           = z.infer<typeof LockTypeSchema>;
export type AccountType        = z.infer<typeof AccountTypeSchema>;
export type Ifrs17Role         = z.infer<typeof Ifrs17RoleSchema>;
export type Ifrs9Role          = z.infer<typeof Ifrs9RoleSchema>;

// ── Journal Entries ───────────────────────────────────────────────────────

export const JournalEntryStatusSchema = z.enum(['DRAFT', 'POSTED', 'REVERSED']);
export type  JournalEntryStatus = z.infer<typeof JournalEntryStatusSchema>;

export const JournalEntrySummaryDtoSchema = z.object({
  id:                     z.string(),
  postingDate:            z.string(),
  businessDate:           z.string(),
  periodId:               z.string(),
  sourceModule:           z.string(),
  sourceEventType:        z.string(),
  sourceReference:        z.string(),
  narrative:              z.string().nullable().optional(),
  postedBy:               z.string(),
  status:                 JournalEntryStatusSchema,
  reversalOf:             z.string().nullable().optional(),
  priorPeriodAdjustment:  z.boolean(),
  createdAt:              z.string(),
  lineCount:              z.number(),
  totalDebit:             z.number(),
});
export type JournalEntrySummaryDto = z.infer<typeof JournalEntrySummaryDtoSchema>;

export const JournalEntryLineDtoSchema = z.object({
  id:                 z.string(),
  lineNo:             z.number(),
  accountId:          z.string(),
  accountCode:        z.string(),
  accountName:        z.string(),
  debitAmount:        z.number(),
  creditAmount:       z.number(),
  currencyCode:       z.string(),
  cohortYear:         z.number().nullable().optional(),
  portfolioId:        z.string().nullable().optional(),
  contractGroupId:    z.string().nullable().optional(),
  holdingId:          z.string().nullable().optional(),
  classOfBusinessId:  z.string().nullable().optional(),
  dimensionTags:      z.record(z.string(), z.unknown()).nullable().optional(),
});
export type JournalEntryLineDto = z.infer<typeof JournalEntryLineDtoSchema>;

export const JournalEntryDtoSchema = z.object({
  id:               z.string(),
  postingDate:      z.string(),
  businessDate:     z.string(),
  periodId:         z.string(),
  sourceModule:     z.string(),
  sourceEventType:  z.string(),
  sourceReference:  z.string(),
  narrative:        z.string().nullable().optional(),
  postedBy:         z.string(),
  status:           JournalEntryStatusSchema,
  reversalOf:       z.string().nullable().optional(),
  createdAt:        z.string(),
  lines:            z.array(JournalEntryLineDtoSchema),
});
export type JournalEntryDto = z.infer<typeof JournalEntryDtoSchema>;

// Spring Page<T> envelope on the data field — totalElements + content[] are
// the fields the browser table reads.
export const SpringPageSchema = <T extends z.ZodTypeAny>(item: T) => z.object({
  content:          z.array(item),
  totalElements:    z.number(),
  totalPages:       z.number(),
  number:           z.number(),
  size:             z.number(),
  first:            z.boolean(),
  last:             z.boolean(),
  empty:            z.boolean(),
  numberOfElements: z.number(),
});

// ── IFRS 17 PAA — Period Close (Slice 2.5) ───────────────────────────────

export const LrcGroupEntrySchema = z.object({
  groupId:         z.string(),
  openingBalance:  z.number(),
  premiumReceived: z.number(),
  premiumEarned:   z.number(),
  closingBalance:  z.number(),
  journalEntryId:  z.string().nullable().optional(),
});

export const LrcResultDtoSchema = z.object({
  periodId:                z.string(),
  groupsProcessed:         z.number(),
  groupsWithJournalEntry:  z.number(),
  totalPremiumEarned:      z.number(),
  entries:                 z.array(LrcGroupEntrySchema),
});
export type LrcResultDto = z.infer<typeof LrcResultDtoSchema>;

export const LicGroupEntrySchema = z.object({
  groupId:        z.string(),
  openingBalance: z.number(),
  claimsIncurred: z.number(),
  claimsPaid:     z.number(),
  closingBalance: z.number(),
});

export const LicResultDtoSchema = z.object({
  periodId:            z.string(),
  groupsProcessed:     z.number(),
  totalClaimsIncurred: z.number(),
  totalClaimsPaid:     z.number(),
  entries:             z.array(LicGroupEntrySchema),
});
export type LicResultDto = z.infer<typeof LicResultDtoSchema>;

export const DiscountUnwindGroupEntrySchema = z.object({
  groupId:        z.string(),
  openingBalance: z.number(),
  unwindAmount:   z.number(),
  closingBalance: z.number(),
  journalEntryId: z.string().nullable().optional(),
});

export const DiscountUnwindResultDtoSchema = z.object({
  periodId:               z.string(),
  discountingDisabled:    z.boolean(),
  routing:                z.string().nullable().optional(),
  groupsProcessed:        z.number(),
  groupsWithJournalEntry: z.number(),
  totalUnwind:            z.number(),
  entries:                z.array(DiscountUnwindGroupEntrySchema),
});
export type DiscountUnwindResultDto = z.infer<typeof DiscountUnwindResultDtoSchema>;

export const OnerousGroupEntrySchema = z.object({
  groupId:             z.string(),
  cumulativeEarned:    z.number(),
  cumulativeIncurred:  z.number(),
  priorLossComponent:  z.number(),
  newLossComponent:    z.number(),
  lossComponentChange: z.number(),
  journalEntryId:      z.string().nullable().optional(),
});

export const OnerousTestResultDtoSchema = z.object({
  periodId:                       z.string(),
  groupsTested:                   z.number(),
  groupsWithLossComponentChange:  z.number(),
  totalLossComponentIncrease:     z.number(),
  totalLossComponentReversal:     z.number(),
  entries:                        z.array(OnerousGroupEntrySchema),
});
export type OnerousTestResultDto = z.infer<typeof OnerousTestResultDtoSchema>;

export const InsuranceServiceGroupResultSchema = z.object({
  groupId:                 z.string(),
  portfolioCode:           z.string().nullable().optional(),
  cohortYear:              z.number().nullable().optional(),
  onerousness:             z.string().nullable().optional(),
  insuranceRevenue:        z.number(),
  insuranceServiceExpense: z.number(),
  insuranceServiceResult:  z.number(),
});

export const InsuranceServiceResultDtoSchema = z.object({
  periodId:                     z.string(),
  periodStart:                  z.string(),
  periodEnd:                    z.string(),
  totalInsuranceRevenue:        z.number(),
  totalInsuranceServiceExpense: z.number(),
  totalInsuranceServiceResult:  z.number(),
  byGroup:                      z.array(InsuranceServiceGroupResultSchema),
});
export type InsuranceServiceResultDto = z.infer<typeof InsuranceServiceResultDtoSchema>;

export const PaaPeriodCloseResultDtoSchema = z.object({
  periodId:               z.string(),
  periodStart:            z.string(),
  periodEnd:              z.string(),
  lrc:                    LrcResultDtoSchema.nullable().optional(),
  lic:                    LicResultDtoSchema.nullable().optional(),
  discountUnwind:         DiscountUnwindResultDtoSchema,
  onerousTest:            OnerousTestResultDtoSchema,
  insuranceServiceResult: InsuranceServiceResultDtoSchema,
});
export type PaaPeriodCloseResultDto = z.infer<typeof PaaPeriodCloseResultDtoSchema>;

// ── IFRS 17 Contract Groups + Portfolios (Slice 2.2) ─────────────────────

export const OnerousnessSchema = z.enum(['NOT_ONEROUS', 'NO_SIGNIFICANT_POSSIBILITY', 'ONEROUS']);
export type Onerousness = z.infer<typeof OnerousnessSchema>;

export const GroupStatusSchema = z.enum(['OPEN', 'CLOSED']);
export type GroupStatus = z.infer<typeof GroupStatusSchema>;

export const ContractGroupSummaryDtoSchema = z.object({
  id:             z.string(),
  portfolioId:    z.string(),
  portfolioCode:  z.string(),
  portfolioName:  z.string(),
  cohortYear:     z.number(),
  onerousness:    OnerousnessSchema,
  status:         GroupStatusSchema,
  createdAt:      z.string(),
});
export type ContractGroupSummaryDto = z.infer<typeof ContractGroupSummaryDtoSchema>;

export const PortfolioSummaryDtoSchema = z.object({
  id:                z.string(),
  code:              z.string(),
  name:              z.string(),
  classOfBusinessId: z.string().nullable().optional(),
  description:       z.string().nullable().optional(),
  active:            z.boolean(),
});
export type PortfolioSummaryDto = z.infer<typeof PortfolioSummaryDtoSchema>;

// ── IFRS 17 §103 Movement Analysis (Slice 2.8) ───────────────────────────

export const LrcMovementTotalsSchema = z.object({
  opening:                   z.number(),
  premiumsReceived:          z.number(),
  premiumEarned:             z.number(),
  acquisitionCostsDeferred:  z.number(),
  acquisitionCostsAmortised: z.number(),
  lossComponent:             z.number(),
  lossComponentChange:       z.number(),
  closing:                   z.number(),
});
export type LrcMovementTotalsDto = z.infer<typeof LrcMovementTotalsSchema>;

export const LicMovementTotalsSchema = z.object({
  opening:              z.number(),
  claimsIncurred:       z.number(),
  claimsPaid:           z.number(),
  caseReserveChange:    z.number(),
  ibnrEstimate:         z.number(),
  ibnrChange:           z.number(),
  riskAdjustment:       z.number(),
  riskAdjustmentChange: z.number(),
  discountUnwind:       z.number(),
  closing:              z.number(),
});
export type LicMovementTotalsDto = z.infer<typeof LicMovementTotalsSchema>;

export const GroupMovementEntrySchema = z.object({
  groupId:                   z.string(),
  portfolioCode:             z.string().nullable().optional(),
  portfolioName:             z.string().nullable().optional(),
  cohortYear:                z.number().nullable().optional(),
  onerousness:               z.string().nullable().optional(),
  groupStatus:               z.string().nullable().optional(),
  // LRC side
  lrcOpening:                z.number(),
  premiumReceived:           z.number(),
  premiumEarned:             z.number(),
  acquisitionCostsDeferred:  z.number(),
  acquisitionCostsAmortised: z.number(),
  lossComponent:             z.number(),
  lossComponentChange:       z.number(),
  lrcClosing:                z.number(),
  // LIC side
  licOpening:                z.number(),
  claimsIncurred:            z.number(),
  claimsPaid:                z.number(),
  caseReserveChange:         z.number(),
  ibnrEstimate:              z.number(),
  ibnrChange:                z.number(),
  riskAdjustment:            z.number(),
  riskAdjustmentChange:      z.number(),
  discountUnwind:            z.number(),
  licClosing:                z.number(),
  // Combined
  totalOpening:              z.number(),
  totalClosing:              z.number(),
  currencyCode:              z.string().nullable().optional(),
});
export type GroupMovementEntryDto = z.infer<typeof GroupMovementEntrySchema>;

export const MovementAnalysisDtoSchema = z.object({
  periodId:              z.string(),
  periodStart:           z.string(),
  periodEnd:             z.string(),
  lrcTotals:             LrcMovementTotalsSchema,
  licTotals:             LicMovementTotalsSchema,
  totalOpeningLiability: z.number(),
  totalClosingLiability: z.number(),
  byGroup:               z.array(GroupMovementEntrySchema),
});
export type MovementAnalysisDto = z.infer<typeof MovementAnalysisDtoSchema>;

// ── Retroactive JE Backfill (Slice 1.8) ──────────────────────────────────

export const BackfillEventTypeSchema = z.enum([
  'POLICY_APPROVED',
  'CLAIM_APPROVED',
  'CLAIM_SETTLED',
  'CLAIM_EXPENSE_APPROVED',
  'ENDORSEMENT_APPROVED',
  'FAC_PREMIUM_CEDED',
]);
export type BackfillEventType = z.infer<typeof BackfillEventTypeSchema>;

export const BackfillResultStatusSchema = z.enum(['SUCCESS', 'PARTIAL_FAILURE', 'REFUSED']);
export type BackfillResultStatus = z.infer<typeof BackfillResultStatusSchema>;

export const BackfillEventTypeCountDtoSchema = z.object({
  eventType:     BackfillEventTypeSchema,
  attempted:     z.number(),
  posted:        z.number(),
  alreadyExists: z.number(),
  failed:        z.number(),
});
export type BackfillEventTypeCountDto = z.infer<typeof BackfillEventTypeCountDtoSchema>;

export const BackfillResultDtoSchema = z.object({
  tenantId:           z.string().nullable().optional(),
  requestId:          z.string(),
  status:             BackfillResultStatusSchema,
  dryRun:             z.boolean(),
  startedAt:          z.string(),
  completedAt:        z.string().nullable().optional(),
  totalAttempted:     z.number(),
  totalPosted:        z.number(),
  totalAlreadyExists: z.number(),
  totalFailed:        z.number(),
  byEventType:        z.array(BackfillEventTypeCountDtoSchema),
  refusalReason:      z.string().nullable().optional(),
});
export type BackfillResultDto = z.infer<typeof BackfillResultDtoSchema>;

export const StartBackfillResponseDtoSchema = z.object({
  workflowId:  z.string(),
  tenantId:    z.string().nullable().optional(),
  dryRun:      z.boolean(),
  startedAt:   z.string(),
});
export type StartBackfillResponseDto = z.infer<typeof StartBackfillResponseDtoSchema>;

export const BackfillStatusResponseDtoSchema = z.object({
  workflowId:      z.string(),
  executionStatus: z.string(),  // RUNNING / COMPLETED / FAILED / CANCELED / TERMINATED / TIMED_OUT / NOT_FOUND
  result:          BackfillResultDtoSchema.nullable().optional(),
});
export type BackfillStatusResponseDto = z.infer<typeof BackfillStatusResponseDtoSchema>;

// ── Trial Balance ─────────────────────────────────────────────────────────

export const TrialBalanceLineDtoSchema = z.object({
  accountId:      z.string(),
  accountCode:    z.string(),
  accountName:    z.string(),
  accountType:    AccountTypeSchema,
  debitBalance:   z.number(),
  creditBalance:  z.number(),
});
export type TrialBalanceLineDto = z.infer<typeof TrialBalanceLineDtoSchema>;

export const TrialBalanceFooterDtoSchema = z.object({
  totalDebits:   z.number(),
  totalCredits:  z.number(),
  balanced:      z.boolean(),
  lineCount:     z.number(),
});
export type TrialBalanceFooterDto = z.infer<typeof TrialBalanceFooterDtoSchema>;

export const TrialBalanceDtoSchema = z.object({
  asOf:         z.string(),
  generatedAt:  z.string(),
  lines:        z.array(TrialBalanceLineDtoSchema),
  footer:       TrialBalanceFooterDtoSchema,
});
export type TrialBalanceDto = z.infer<typeof TrialBalanceDtoSchema>;

// ── Chart of Accounts (recursive) ─────────────────────────────────────────

export type ChartOfAccountNodeDto = {
  code:        string;
  name:        string;
  accountType: AccountType;
  ifrs17Role:  Ifrs17Role | null;
  ifrs9Role:   Ifrs9Role  | null;
  active:      boolean;
  children:    ChartOfAccountNodeDto[];
};

export const ChartOfAccountNodeSchema: z.ZodType<ChartOfAccountNodeDto> = z.lazy(() =>
  z.object({
    code:        z.string(),
    name:        z.string(),
    accountType: AccountTypeSchema,
    ifrs17Role:  Ifrs17RoleSchema.nullable(),
    ifrs9Role:   Ifrs9RoleSchema.nullable(),
    active:      z.boolean(),
    children:    z.array(ChartOfAccountNodeSchema),
  }),
);

// ── FiscalPeriod ──────────────────────────────────────────────────────────

export const FiscalPeriodDtoSchema = z.object({
  id:            z.string(),
  fiscalYearId:  z.string(),
  periodType:    FiscalPeriodTypeSchema,
  startDate:     z.string(),
  endDate:       z.string(),
  status:        FiscalPeriodStatusSchema,
  softClosedAt:  z.string().nullable().optional(),
  hardClosedAt:  z.string().nullable().optional(),
});

export type FiscalPeriodDto = z.infer<typeof FiscalPeriodDtoSchema>;

// ── FiscalYear ────────────────────────────────────────────────────────────

export const FiscalYearDtoSchema = z.object({
  id:         z.string(),
  name:       z.string(),
  startDate:  z.string(),
  endDate:    z.string(),
  status:     FiscalYearStatusSchema,
  createdAt:  z.string(),
  periods:    z.array(FiscalPeriodDtoSchema).nullable().optional(),
});

export type FiscalYearDto = z.infer<typeof FiscalYearDtoSchema>;

// ── PeriodLock ────────────────────────────────────────────────────────────

export const PeriodLockDtoSchema = z.object({
  id:                z.string(),
  fiscalPeriodId:    z.string(),
  lockType:          LockTypeSchema,
  lockedAt:          z.string(),
  lockedBy:          z.string().nullable().optional(),
  graceWindowUntil:  z.string().nullable().optional(),
  releasedAt:        z.string().nullable().optional(),
  releasedBy:        z.string().nullable().optional(),
  releaseReason:     z.string().nullable().optional(),
});

export type PeriodLockDto = z.infer<typeof PeriodLockDtoSchema>;

// ── LockReportEntry (range preview) ───────────────────────────────────────

export const LockReportEntrySchema = z.object({
  date:              z.string(),
  periodId:          z.string().nullable().optional(),
  periodLabel:       z.string(),
  status:            FiscalPeriodStatusSchema.nullable().optional(),
  graceWindowUntil:  z.string().nullable().optional(),
  requiresOverride:  z.boolean(),
  rejected:          z.boolean(),
});

export type LockReportEntry = z.infer<typeof LockReportEntrySchema>;

// ── Request bodies ────────────────────────────────────────────────────────

export const ClosePeriodRequestSchema  = z.object({ reason: z.string().min(1).max(500) });
export const ReopenPeriodRequestSchema = z.object({ reason: z.string().min(1).max(500) });

export type ClosePeriodRequest  = z.infer<typeof ClosePeriodRequestSchema>;
export type ReopenPeriodRequest = z.infer<typeof ReopenPeriodRequestSchema>;

// Note: backend treats all three fields as optional (defaults to current
// calendar year with "FY{YYYY}" name). The frontend form makes name + dates
// optional on the wire but presents them as required-feeling fields with
// live defaults visible to the user.
export const CreateFiscalYearRequestSchema = z.object({
  name:       z.string().max(50).optional(),
  startDate:  z.string().optional(),
  endDate:    z.string().optional(),
});

export type CreateFiscalYearRequest = z.infer<typeof CreateFiscalYearRequestSchema>;
