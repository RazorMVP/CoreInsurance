// ── Claims — schemas + derived types ─────────────────────────────────────
//
// Mirrors cia-claims/dto/* records. Serves at /api/v1/claims/...
//
// Schemas are the source of truth — types are derived via z.infer.
// Use validated* helpers from '@cia/api-client' to fetch + validate:
//
//   import { validatedGet, ClaimDtoSchema } from '@cia/api-client';
//   const claims = await validatedGet('/api/v1/claims', z.array(ClaimDtoSchema));
//
// Backend gaps NOT modelled here (require future backend work):
//   - Inspection sub-workflow (frontend has approve/override/decline
//     for inspection reports as a separate step; backend has a single
//     /approve workflow for the whole claim)
//   - Inspection document bundle download
//   - "Paid amount" — backend exposes `approvedAmount` (the amount
//     approved for payment). Actual paid status is tracked via the
//     credit-note + payment chain in cia-finance.

import { z } from 'zod';

// ── Enums ─────────────────────────────────────────────────────────────────

export const ClaimStatusSchema = z.enum([
  'REGISTERED',
  'UNDER_INVESTIGATION',
  'RESERVED',
  'PENDING_APPROVAL',
  'APPROVED',
  'SETTLED',
  'REJECTED',
  'WITHDRAWN',
]);
export type ClaimStatus = z.infer<typeof ClaimStatusSchema>;

export const ClaimExpenseStatusSchema = z.enum(['PENDING', 'APPROVED', 'CANCELLED']);
export type ClaimExpenseStatus = z.infer<typeof ClaimExpenseStatusSchema>;

export const ClaimExpenseTypeSchema = z.enum([
  'SURVEYOR_FEE', 'ASSESSOR_FEE', 'LEGAL_FEE',
  'MEDICAL_REPORT', 'INVESTIGATION', 'OTHER',
]);
export type ClaimExpenseType = z.infer<typeof ClaimExpenseTypeSchema>;

export const ClaimDocumentTypeSchema = z.enum([
  'CLAIM_FORM', 'POLICE_REPORT', 'SURVEY_REPORT', 'MEDICAL_REPORT',
  'PHOTOS', 'REPAIR_ESTIMATE', 'DISCHARGE_VOUCHER', 'OTHER',
]);
export type ClaimDocumentType = z.infer<typeof ClaimDocumentTypeSchema>;

// ── Claim ─────────────────────────────────────────────────────────────────

export const ClaimDtoSchema = z.object({
  id:                  z.string(),
  claimNumber:         z.string(),
  status:              ClaimStatusSchema,
  policyId:            z.string(),
  policyNumber:        z.string(),
  policyStartDate:     z.string().nullable().optional(),
  policyEndDate:       z.string().nullable().optional(),
  customerId:          z.string(),
  customerName:        z.string(),
  productName:         z.string().nullable().optional(),
  classOfBusinessName: z.string().nullable().optional(),
  brokerId:            z.string().nullable().optional(),
  brokerName:          z.string().nullable().optional(),
  incidentDate:        z.string(),
  reportedDate:        z.string(),
  lossLocation:        z.string().nullable().optional(),
  description:         z.string(),
  estimatedLoss:       z.number(),
  reserveAmount:       z.number(),
  approvedAmount:      z.number().nullable().optional(),
  currencyCode:        z.string(),
  surveyorId:          z.string().nullable().optional(),
  surveyorName:        z.string().nullable().optional(),
  surveyorAssignedAt:  z.string().nullable().optional(),
  approvedBy:          z.string().nullable().optional(),
  approvedAt:          z.string().nullable().optional(),
  rejectedBy:          z.string().nullable().optional(),
  rejectedAt:          z.string().nullable().optional(),
  rejectionReason:     z.string().nullable().optional(),
  withdrawnBy:         z.string().nullable().optional(),
  withdrawnAt:         z.string().nullable().optional(),
  withdrawalReason:    z.string().nullable().optional(),
  settledAt:           z.string().nullable().optional(),
  notes:               z.string().nullable().optional(),
  createdAt:           z.string(),
});
export type ClaimDto = z.infer<typeof ClaimDtoSchema>;

// ── Reserve ───────────────────────────────────────────────────────────────

export const ClaimReserveDtoSchema = z.object({
  id:             z.string(),
  amount:         z.number(),
  previousAmount: z.number().nullable().optional(),
  reason:         z.string().nullable().optional(),
  createdBy:      z.string().nullable().optional(),
  createdAt:      z.string(),
});
export type ClaimReserveDto = z.infer<typeof ClaimReserveDtoSchema>;

// ── Expense ───────────────────────────────────────────────────────────────

export const ClaimExpenseDtoSchema = z.object({
  id:                  z.string(),
  claimId:             z.string(),
  expenseType:         ClaimExpenseTypeSchema,
  status:              ClaimExpenseStatusSchema,
  vendorId:            z.string().nullable().optional(),
  vendorName:          z.string().nullable().optional(),
  amount:              z.number(),
  description:         z.string().nullable().optional(),
  approvedBy:          z.string().nullable().optional(),
  approvedAt:          z.string().nullable().optional(),
  cancelledBy:         z.string().nullable().optional(),
  cancelledAt:         z.string().nullable().optional(),
  cancellationReason:  z.string().nullable().optional(),
  createdAt:           z.string(),
});
export type ClaimExpenseDto = z.infer<typeof ClaimExpenseDtoSchema>;

// ── Document ──────────────────────────────────────────────────────────────

export const ClaimDocumentDtoSchema = z.object({
  id:           z.string(),
  claimId:      z.string(),
  documentType: ClaimDocumentTypeSchema,
  fileName:     z.string(),
  filePath:     z.string(),
  fileSize:     z.number().nullable().optional(),
  uploadedBy:   z.string().nullable().optional(),
  createdAt:    z.string(),
});
export type ClaimDocumentDto = z.infer<typeof ClaimDocumentDtoSchema>;
