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

export type FiscalYearStatus   = z.infer<typeof FiscalYearStatusSchema>;
export type FiscalPeriodType   = z.infer<typeof FiscalPeriodTypeSchema>;
export type FiscalPeriodStatus = z.infer<typeof FiscalPeriodStatusSchema>;
export type LockType           = z.infer<typeof LockTypeSchema>;

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
