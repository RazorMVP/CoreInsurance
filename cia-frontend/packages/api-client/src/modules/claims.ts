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
//   - Comments aggregate — would need a ClaimComment entity (1:many)
//     and /api/v1/claims/{id}/comments CRUD endpoints
//   - Required-document checklist — needs ClaimRequiredDocument entity
//     tied to the per-product checklist defined in setup module
//   - "Paid amount" — backend exposes `approvedAmount` (the amount
//     approved for payment). Actual paid status is tracked via the
//     credit-note + payment chain in cia-finance.
//
// Inspection sub-workflow IS modelled (B6: ClaimInspectionDtoSchema +
// /api/v1/claims/{id}/inspection/* endpoints). Document bundle download
// IS available at /api/v1/claims/{id}/inspection/documents/bundle (B6).

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

export const DvTypeSchema = z.enum(['OWN_DAMAGE', 'THIRD_PARTY', 'EX_GRATIA']);
export type DvType = z.infer<typeof DvTypeSchema>;

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

export const InspectionStatusSchema = z.enum([
  'ASSIGNED', 'REPORT_SUBMITTED', 'APPROVED', 'DECLINED', 'OVERRIDDEN',
]);
export type InspectionStatus = z.infer<typeof InspectionStatusSchema>;

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
  natureOfLoss:        z.string().nullable().optional(),
  causeOfLoss:         z.string().nullable().optional(),
  contactName:         z.string().nullable().optional(),
  contactPhone:        z.string().nullable().optional(),
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
  dvType:              DvTypeSchema.nullable().optional(),
  dvAmount:            z.number().nullable().optional(),
  dvGeneratedAt:       z.string().nullable().optional(),
  dvExecutedAt:        z.string().nullable().optional(),
  dvDocumentPath:      z.string().nullable().optional(),
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

// ── Inspection (B6) ───────────────────────────────────────────────────────

export const ClaimInspectionDtoSchema = z.object({
  id:                z.string(),
  claimId:           z.string(),
  status:            InspectionStatusSchema,

  surveyorType:      z.string().nullable().optional(),
  surveyorId:        z.string().nullable().optional(),
  surveyorName:      z.string().nullable().optional(),
  assignedBy:        z.string().nullable().optional(),
  assignedAt:        z.string().nullable().optional(),

  reportPath:        z.string().nullable().optional(),
  reportNotes:       z.string().nullable().optional(),
  reportUploadedBy:  z.string().nullable().optional(),
  reportUploadedAt:  z.string().nullable().optional(),

  approvedBy:        z.string().nullable().optional(),
  approvedAt:        z.string().nullable().optional(),
  approvalNotes:     z.string().nullable().optional(),

  declinedBy:        z.string().nullable().optional(),
  declinedAt:        z.string().nullable().optional(),
  declineReason:     z.string().nullable().optional(),

  overrideReason:    z.string().nullable().optional(),
  overriddenBy:      z.string().nullable().optional(),
  overriddenAt:      z.string().nullable().optional(),

  createdAt:         z.string(),
});
export type ClaimInspectionDto = z.infer<typeof ClaimInspectionDtoSchema>;
