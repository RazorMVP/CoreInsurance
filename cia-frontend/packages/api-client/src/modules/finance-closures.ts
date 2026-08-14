// ── Finance — Closures (Module 12, Slices 1.6 + 1.7 + Phase 2 + Phase 3) ─
//
// Wire shapes for the Period-End Closures backend. Field names mirror the
// canonical Java DTOs in cia-finance/src/main/java/com/nubeero/cia/finance/
// dto/ (Phase 1 + 2) and com/nubeero/cia/finance/{paa,ifrs9}/ (Phase 2/3
// engine result records).
//
// Schemas are the source of truth — derive types via z.infer<typeof T>.
// Fetch with validatedGet so backend rename drift fails loudly at runtime.
//
// ── File ordering convention ─────────────────────────────────────────────
// 1. All z.enum(...) schemas live in the single "Enums" section below
//    (dependency-free zone — never references another schema).
// 2. DTO sections come after. They may reference any enum from §1 and
//    any earlier DTO. Recursive shapes use z.lazy() with an explicit
//    z.ZodType<DtoType> annotation.
// 3. Adding a new section: declare its enums in §1, place its DTOs in
//    a fresh section header (`// ── New thing ─ … ─`) anywhere below.
//    Never declare a new z.enum outside §1; TypeScript will catch the
//    forward reference but the convention prevents the trip in the
//    first place. Bug encountered + caught in F5.14, 2026-05-21.

import { z } from 'zod';

// ── Enums ─────────────────────────────────────────────────────────────────
// Single home for every z.enum(...). Never declare an enum outside this
// section; later DTO sections rely on these being all-up-front.

// Fiscal calendar (Slice 1.6)
export const FiscalYearStatusSchema   = z.enum(['PLANNING', 'ACTIVE', 'CLOSED']);
export const FiscalPeriodTypeSchema   = z.enum(['DAY', 'MONTH', 'QUARTER', 'HALF_YEAR', 'YEAR']);
export const FiscalPeriodStatusSchema = z.enum(['OPEN', 'SOFT_CLOSED', 'HARD_CLOSED', 'REOPENED']);
export const LockTypeSchema           = z.enum(['SOFT', 'HARD']);

// Chart of accounts (Slice 1.3 + V32)
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

// Journal entry (Slice 1.4)
export const JournalEntryStatusSchema = z.enum(['DRAFT', 'POSTED', 'REVERSED']);

// IFRS 17 contract grouping (Slice 2.2)
export const OnerousnessSchema = z.enum(['NOT_ONEROUS', 'NO_SIGNIFICANT_POSSIBILITY', 'ONEROUS']);
export const GroupStatusSchema = z.enum(['OPEN', 'CLOSED']);

// Portfolio contract-nature dimension (V76, FAC / IFRS-17 PAA workstream Task 7)
// — distinguishes direct-policy portfolios from facultative reinsurance
// accepted (inward) / ceded (outward). Distinct from the ContractType
// entity discriminator (a direct policy is POLICY inside a DIRECT portfolio).
export const ContractNatureSchema = z.enum(['DIRECT', 'FAC_INWARD', 'FAC_OUTWARD']);

// IFRS 9 holdings (Slice 3.2)
export const AssetTypeSchema = z.enum(['DEBT', 'EQUITY', 'MONEY_MARKET', 'DERIVATIVE']);
export const InvestmentClassificationSchema = z.enum([
  'AMORTISED_COST', 'FVOCI_DEBT', 'FVOCI_EQUITY', 'FVPL',
]);
export const HoldingStatusSchema = z.enum(['ACTIVE', 'MATURED', 'SOLD', 'IMPAIRED']);

// Backfill workflow (Slice 1.8)
export const BackfillEventTypeSchema = z.enum([
  'POLICY_APPROVED',
  'CLAIM_APPROVED',
  'CLAIM_SETTLED',
  'CLAIM_EXPENSE_APPROVED',
  'ENDORSEMENT_APPROVED',
  'FAC_PREMIUM_CEDED',
]);
export const BackfillResultStatusSchema = z.enum(['SUCCESS', 'PARTIAL_FAILURE', 'REFUSED']);

// NAICOM submission state machine (Slice 4.9)
export const NaicomSubmissionStateSchema = z.enum(['DRAFT', 'SUBMITTED', 'ACKNOWLEDGED', 'ARCHIVED', 'RETRACTED']);
export const NaicomSubmissionTypeSchema = z.enum([
  'ANNUAL_REVENUE_ACCOUNT',
  'BALANCE_SHEET',
  'PRUDENTIAL_RETURN',
  'RI_QUARTERLY_RETURN',
  'PREMIUM_BORDEREAUX',
  'CLAIMS_BORDEREAUX',
  'NIID_STATUS_SNAPSHOT',
  'INVESTMENT_STATEMENT',
]);

// NAICOM artifact formats (Slice 4.10)
export const ArtifactFormatSchema = z.enum(['PDF', 'CSV', 'JSON', 'XML']);

export type FiscalYearStatus         = z.infer<typeof FiscalYearStatusSchema>;
export type FiscalPeriodType         = z.infer<typeof FiscalPeriodTypeSchema>;
export type FiscalPeriodStatus       = z.infer<typeof FiscalPeriodStatusSchema>;
export type LockType                 = z.infer<typeof LockTypeSchema>;
export type AccountType              = z.infer<typeof AccountTypeSchema>;
export type Ifrs17Role               = z.infer<typeof Ifrs17RoleSchema>;
export type Ifrs9Role                = z.infer<typeof Ifrs9RoleSchema>;
export type JournalEntryStatus       = z.infer<typeof JournalEntryStatusSchema>;
export type Onerousness              = z.infer<typeof OnerousnessSchema>;
export type GroupStatus              = z.infer<typeof GroupStatusSchema>;
export type ContractNature           = z.infer<typeof ContractNatureSchema>;
export type AssetType                = z.infer<typeof AssetTypeSchema>;
export type InvestmentClassification = z.infer<typeof InvestmentClassificationSchema>;
export type HoldingStatus            = z.infer<typeof HoldingStatusSchema>;
export type BackfillEventType        = z.infer<typeof BackfillEventTypeSchema>;
export type BackfillResultStatus     = z.infer<typeof BackfillResultStatusSchema>;
export type NaicomSubmissionState    = z.infer<typeof NaicomSubmissionStateSchema>;
export type NaicomSubmissionType     = z.infer<typeof NaicomSubmissionTypeSchema>;
export type ArtifactFormat           = z.infer<typeof ArtifactFormatSchema>;

// ── Journal Entries ───────────────────────────────────────────────────────

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

// ── IFRS 9 Measurement engines (Slices 3.3–3.6) ──────────────────────────

// Slice 3.3 — Amortised Cost (effective interest method)
export const AmortisedCostEntrySchema = z.object({
  holdingId:      z.string(),
  securityName:   z.string(),
  classification: z.string(),
  openingBalance: z.number(),
  interestIncome: z.number(),
  closingBalance: z.number(),
  journalEntryId: z.string().nullable().optional(),
});
export const AmortisedCostResultDtoSchema = z.object({
  periodId:                 z.string(),
  holdingsProcessed:        z.number(),
  holdingsWithJournalEntry: z.number(),
  totalInterestIncome:      z.number(),
  entries:                  z.array(AmortisedCostEntrySchema),
});
export type AmortisedCostResultDto = z.infer<typeof AmortisedCostResultDtoSchema>;

// Slice 3.4 — Fair Value
export const FairValueEntrySchema = z.object({
  holdingId:           z.string(),
  securityName:        z.string(),
  classification:      z.string(),
  routing:             z.string(),  // "PnL" | "OCI"
  preFairValueBalance: z.number(),
  newFairValue:        z.number(),
  fairValueChange:     z.number(),
  journalEntryId:      z.string().nullable().optional(),
});
export const FairValueResultDtoSchema = z.object({
  periodId:                  z.string(),
  holdingsProcessed:         z.number(),
  holdingsWithJournalEntry:  z.number(),
  totalFairValueChangePnl:   z.number(),
  totalFairValueChangeOci:   z.number(),
  entries:                   z.array(FairValueEntrySchema),
});
export type FairValueResultDto = z.infer<typeof FairValueResultDtoSchema>;

// Slice 3.5 — Investment ECL
export const EclEntrySchema = z.object({
  holdingId:      z.string(),
  securityName:   z.string(),
  classification: z.string(),
  priorStage:     z.number().nullable().optional(),
  newStage:       z.number().nullable().optional(),
  priorEcl:       z.number(),
  newEcl:         z.number(),
  eclMovement:    z.number(),
  journalEntryId: z.string().nullable().optional(),
});
export const EclRecognitionResultDtoSchema = z.object({
  periodId:                 z.string(),
  holdingsProcessed:        z.number(),
  holdingsWithJournalEntry: z.number(),
  totalEclIncrease:         z.number(),
  totalEclReversal:         z.number(),
  totalEclMovement:         z.number(),
  entries:                  z.array(EclEntrySchema),
});
export type EclRecognitionResultDto = z.infer<typeof EclRecognitionResultDtoSchema>;

// Slice 3.6 — Premium Receivable ECL (provision matrix)
export const ProvisionBucketSchema = z.object({
  label:             z.string(),
  outstandingAmount: z.number(),
  defaultRate:       z.number(),
  bucketEcl:         z.number(),
});
export const PremiumReceivableEclResultDtoSchema = z.object({
  periodId:             z.string(),
  totalOutstanding:     z.number(),
  targetLifetimeEcl:    z.number(),
  priorCumulativeEcl:   z.number(),
  eclMovement:          z.number(),
  direction:            z.string(),  // INCREASE / REVERSAL / NO_CHANGE
  journalEntryId:       z.string().nullable().optional(),
  buckets:              z.array(ProvisionBucketSchema),
});
export type PremiumReceivableEclResultDto = z.infer<typeof PremiumReceivableEclResultDtoSchema>;

// ── IFRS 9 Investment Holdings + Classification History (Slice 3.2) ─────

export const InvestmentHoldingDtoSchema = z.object({
  id:               z.string(),
  isin:             z.string().nullable().optional(),
  securityName:     z.string(),
  issuer:           z.string().nullable().optional(),
  assetType:        AssetTypeSchema,
  classification:   InvestmentClassificationSchema,
  acquisitionDate:  z.string(),
  acquisitionCost:  z.number(),
  faceValue:        z.number().nullable().optional(),
  couponRate:       z.number().nullable().optional(),
  maturityDate:     z.string().nullable().optional(),
  currencyCode:     z.string(),
  status:           HoldingStatusSchema,
  sppiTestPassed:   z.boolean().nullable().optional(),
  eclStage:         z.number().nullable().optional(),
});
export type InvestmentHoldingDto = z.infer<typeof InvestmentHoldingDtoSchema>;

export const InvestmentClassificationHistoryDtoSchema = z.object({
  id:                     z.string(),
  holdingId:              z.string(),
  previousClassification: InvestmentClassificationSchema,
  newClassification:      InvestmentClassificationSchema,
  reclassificationDate:   z.string(),
  reason:                 z.string(),
  approvedBy:             z.string(),
  createdAt:              z.string(),
});
export type InvestmentClassificationHistoryDto = z.infer<typeof InvestmentClassificationHistoryDtoSchema>;

// ── IFRS 9 §B5.5.39 Movement Analysis (Slice 3.7) ────────────────────────

export const Ifrs9InvestmentTotalsSchema = z.object({
  openingBalance:           z.number(),
  effectiveInterestIncome:  z.number(),
  couponReceived:           z.number(),
  fairValueChangePnl:       z.number(),
  fairValueChangeOci:       z.number(),
  eclMovement:              z.number(),
  impairmentLoss:           z.number(),
  disposals:                z.number(),
  closingBalance:           z.number(),
  totalPnlIncome:           z.number(),
  totalOciMovement:         z.number(),
});

export const Ifrs9HoldingMovementSchema = z.object({
  holdingId:                z.string(),
  isin:                     z.string().nullable().optional(),
  securityName:             z.string(),
  issuer:                   z.string().nullable().optional(),
  assetType:                AssetTypeSchema,
  classification:           InvestmentClassificationSchema,
  holdingStatus:            HoldingStatusSchema,
  currencyCode:             z.string(),
  maturityDate:             z.string().nullable().optional(),
  openingBalance:           z.number(),
  effectiveInterestIncome:  z.number(),
  couponReceived:           z.number(),
  fairValueChangePnl:       z.number(),
  fairValueChangeOci:       z.number(),
  eclMovement:              z.number(),
  impairmentLoss:           z.number(),
  disposals:                z.number(),
  closingBalance:           z.number(),
  closingFairValue:         z.number().nullable().optional(),
  eclStage:                 z.number().nullable().optional(),
  totalPnlIncome:           z.number(),
  totalOciMovement:         z.number(),
});

export const Ifrs9InvestmentSectionSchema = z.object({
  totals:    Ifrs9InvestmentTotalsSchema,
  byHolding: z.array(Ifrs9HoldingMovementSchema),
});

export const Ifrs9PremiumReceivableSectionSchema = z.object({
  openingAllowance: z.number(),
  periodMovement:   z.number(),
  closingAllowance: z.number(),
  direction:        z.string(),
});

export const Ifrs9MovementAnalysisDtoSchema = z.object({
  periodId:              z.string(),
  periodStart:           z.string(),
  periodEnd:             z.string(),
  investments:           Ifrs9InvestmentSectionSchema,
  premiumReceivableEcl:  Ifrs9PremiumReceivableSectionSchema,
});
export type Ifrs9MovementAnalysisDto = z.infer<typeof Ifrs9MovementAnalysisDtoSchema>;
export type Ifrs9InvestmentTotalsDto = z.infer<typeof Ifrs9InvestmentTotalsSchema>;

// ── IFRS 17 Contract Groups + Portfolios (Slice 2.2) ─────────────────────

export const ContractGroupSummaryDtoSchema = z.object({
  id:             z.string(),
  portfolioId:    z.string(),
  portfolioCode:  z.string(),
  portfolioName:  z.string(),
  contractNature: ContractNatureSchema,
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
  contractNature:            z.string().nullable().optional(),
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

// ── Posting Rules (Slice 1.5 service / F5.7 frontend) ─────────────────────

export const PostingRuleDtoSchema = z.object({
  id:                  z.string(),
  sourceEventType:     z.string(),
  debitAccountCode:    z.string(),
  debitAccountName:    z.string(),
  creditAccountCode:   z.string(),
  creditAccountName:   z.string(),
  narrativeTemplate:   z.string().nullable().optional(),
  active:              z.boolean(),
  createdAt:           z.string(),
});
export type PostingRuleDto = z.infer<typeof PostingRuleDtoSchema>;

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

// ── NAICOM Submissions (Slice 4.9 + 4.10) ────────────────────────────────

export const NaicomSubmissionDtoSchema = z.object({
  id:               z.string(),
  submissionType:   NaicomSubmissionTypeSchema,
  periodId:         z.string(),
  periodStart:      z.string(),
  periodEnd:        z.string(),
  state:            NaicomSubmissionStateSchema,
  submittedAt:      z.string().nullable().optional(),
  submittedBy:      z.string().nullable().optional(),
  acknowledgedAt:   z.string().nullable().optional(),
  acknowledgedBy:   z.string().nullable().optional(),
  naicomUid:        z.string().nullable().optional(),
  archivedAt:       z.string().nullable().optional(),
  retractedAt:      z.string().nullable().optional(),
  retractedBy:      z.string().nullable().optional(),
  retractionReason: z.string().nullable().optional(),
  notes:            z.string().nullable().optional(),
  payload:          z.record(z.string(), z.unknown()).nullable().optional(),
});
export type NaicomSubmissionDto = z.infer<typeof NaicomSubmissionDtoSchema>;

export const NaicomSubmissionEventDtoSchema = z.object({
  id:           z.string(),
  submissionId: z.string(),
  fromState:    NaicomSubmissionStateSchema.nullable().optional(),
  toState:      NaicomSubmissionStateSchema,
  reason:       z.string().nullable().optional(),
  actor:        z.string(),
  occurredAt:   z.string(),
});
export type NaicomSubmissionEventDto = z.infer<typeof NaicomSubmissionEventDtoSchema>;

export const SubmissionArtifactDtoSchema = z.object({
  id:           z.string(),
  submissionId: z.string(),
  format:       ArtifactFormatSchema,
  storagePath:  z.string(),
  sizeBytes:    z.number(),
  sha256Hex:    z.string(),
  renderedAt:   z.string(),
  renderedBy:   z.string().nullable().optional(),
});
export type SubmissionArtifactDto = z.infer<typeof SubmissionArtifactDtoSchema>;
