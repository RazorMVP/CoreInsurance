// ── Policy — schemas + derived types ─────────────────────────────────────
//
// Mirrors cia-policy/dto/* records. Serves at /api/v1/policies/...
//
// Schemas are the source of truth — types are derived via z.infer.
// Use validated* helpers from '@cia/api-client' to fetch + validate:
//
//   import { validatedGet, PolicyDtoSchema } from '@cia/api-client';
//   const p = await validatedGet(`/api/v1/policies/${id}`, PolicyDtoSchema);

import { z } from 'zod';

// ── Enums ─────────────────────────────────────────────────────────────────

export const PolicyStatusSchema = z.enum([
  'DRAFT',
  'PENDING_APPROVAL',
  'ACTIVE',
  'REJECTED',
  'CANCELLED',
  'LAPSED',
  'EXPIRED',
  'REINSTATED',
]);
export type PolicyStatus = z.infer<typeof PolicyStatusSchema>;

export const BusinessTypeSchema = z.enum([
  'DIRECT',
  'DIRECT_WITH_COINSURANCE',
  'INWARD_COINSURANCE',
  'INWARD_FACULTATIVE',
]);
export type BusinessType = z.infer<typeof BusinessTypeSchema>;

export const SurveyStatusSchema = z.enum([
  'ASSIGNED',
  'REPORT_SUBMITTED',
  'APPROVED',
  'OVERRIDDEN',
]);
export type SurveyStatus = z.infer<typeof SurveyStatusSchema>;

// ── Risk ──────────────────────────────────────────────────────────────────

export const PolicyRiskDtoSchema = z.object({
  id:               z.string(),
  description:      z.string(),
  sumInsured:       z.number(),
  premium:          z.number(),
  sectionId:        z.string().nullable().optional(),
  sectionName:      z.string().nullable().optional(),
  riskDetails:      z.record(z.string(), z.unknown()).nullable().optional(),
  vehicleRegNumber: z.string().nullable().optional(),
  orderNo:          z.number(),
});
export type PolicyRiskDto = z.infer<typeof PolicyRiskDtoSchema>;

// ── Coinsurance participant ──────────────────────────────────────────────

export const PolicyCoinsuranceParticipantDtoSchema = z.object({
  id:                   z.string(),
  insuranceCompanyId:   z.string(),
  insuranceCompanyName: z.string(),
  sharePercentage:      z.number(),
});
export type PolicyCoinsuranceParticipantDto = z.infer<typeof PolicyCoinsuranceParticipantDtoSchema>;

// ── Survey (added in B4.3) ────────────────────────────────────────────────

export const PolicySurveyDtoSchema = z.object({
  id:                z.string(),
  policyId:          z.string(),
  status:            SurveyStatusSchema,

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

  overrideReason:    z.string().nullable().optional(),
  overriddenBy:      z.string().nullable().optional(),
  overriddenAt:      z.string().nullable().optional(),

  createdAt:         z.string(),
});
export type PolicySurveyDto = z.infer<typeof PolicySurveyDtoSchema>;

// Frozen clause snapshot on the policy (mirrors com.nubeero.cia.common.clause.ClauseSnapshot).
export const ClauseSnapshotDtoSchema = z.object({
  id:    z.string(),
  title: z.string(),
  text:  z.string(),
  type:  z.string(),
});
export type ClauseSnapshotDto = z.infer<typeof ClauseSnapshotDtoSchema>;

// ── Policy ────────────────────────────────────────────────────────────────

export const PolicyDtoSchema = z.object({
  id:                       z.string(),
  policyNumber:             z.string().nullable().optional(),
  status:                   PolicyStatusSchema,

  quoteId:                  z.string().nullable().optional(),
  quoteNumber:              z.string().nullable().optional(),

  customerId:               z.string(),
  customerName:             z.string(),

  productId:                z.string(),
  productName:              z.string(),
  productCode:              z.string(),
  productRate:              z.number(),

  classOfBusinessId:        z.string(),
  classOfBusinessName:      z.string(),
  classOfBusinessCode:      z.string(),

  brokerId:                 z.string().nullable().optional(),
  brokerName:               z.string().nullable().optional(),

  // Per-policy agent attribution (Slice 84d / V53). Mutually exclusive with
  // brokerId at the DB level (ck_policies_broker_xor_agent).
  agentId:                  z.string().nullable().optional(),
  agentName:                z.string().nullable().optional(),

  // Relationship Manager attribution (B2 Task 5.1). RM is an accrual-only
  // commission source (no payment/CreditNote) — surfaced on the Financial tab.
  relationshipManagerId:    z.string().nullable().optional(),
  relationshipManagerName:  z.string().nullable().optional(),

  businessType:             BusinessTypeSchema,
  niidRequired:             z.boolean(),

  policyStartDate:          z.string(),
  policyEndDate:            z.string(),

  totalSumInsured:          z.number(),
  totalPremium:             z.number(),
  discount:                 z.number(),
  netPremium:               z.number(),

  // Commission snapshot (Slice 84b V51 — surfaced by 84e). All three are null
  // when no commission is configured at issuance; paired-CHECK semantics on
  // the DB keep source + rate in lockstep. commissionAmount is computed by the
  // backend (netPremium × rate / 100, HALF_UP 2dp) so the frontend doesn't
  // multiply.
  commissionSourceType:     z.enum(['AGENT', 'BROKER', 'RELATIONSHIP_MANAGER']).nullable().optional(),
  commissionRate:           z.number().nullable().optional(),
  commissionAmount:         z.number().nullable().optional(),

  notes:                    z.string().nullable().optional(),
  workflowId:               z.string().nullable().optional(),

  approvedBy:               z.string().nullable().optional(),
  approvedAt:               z.string().nullable().optional(),
  rejectedBy:               z.string().nullable().optional(),
  rejectedAt:               z.string().nullable().optional(),
  rejectionReason:          z.string().nullable().optional(),

  cancelledBy:              z.string().nullable().optional(),
  cancelledAt:              z.string().nullable().optional(),
  cancellationReason:       z.string().nullable().optional(),

  naicomUid:                z.string().nullable().optional(),
  naicomUploadedAt:         z.string().nullable().optional(),
  naicomCertificatePath:    z.string().nullable().optional(),

  niidRef:                  z.string().nullable().optional(),
  niidUploadedAt:           z.string().nullable().optional(),

  policyDocumentPath:       z.string().nullable().optional(),
  documentSentAt:           z.string().nullable().optional(),
  documentSentBy:           z.string().nullable().optional(),
  documentAcknowledgedAt:   z.string().nullable().optional(),
  documentAcknowledgedBy:   z.string().nullable().optional(),

  risks:                    z.array(PolicyRiskDtoSchema),
  coinsuranceParticipants:  z.array(PolicyCoinsuranceParticipantDtoSchema),
  selectedClauses:          z.array(ClauseSnapshotDtoSchema).optional(),
  survey:                   PolicySurveyDtoSchema.nullable().optional(),

  createdAt:                z.string(),
  updatedAt:                z.string().nullable().optional(),
});
export type PolicyDto = z.infer<typeof PolicyDtoSchema>;

// ── Policy summary (list endpoint returns this lighter shape) ─────────────

export const PolicySummaryDtoSchema = z.object({
  id:                  z.string(),
  policyNumber:        z.string().nullable().optional(),
  status:              PolicyStatusSchema,
  customerId:          z.string(),
  customerName:        z.string(),
  productName:         z.string(),
  classOfBusinessName: z.string(),
  brokerName:          z.string().nullable().optional(),
  /** Per-policy agent attribution (Slice 84d). Mutually exclusive with brokerName via V53 CHECK. */
  agentName:           z.string().nullable().optional(),
  businessType:        BusinessTypeSchema,
  policyStartDate:     z.string(),
  policyEndDate:       z.string(),
  totalSumInsured:     z.number(),
  netPremium:          z.number(),
  naicomUid:           z.string().nullable().optional(),
  createdAt:           z.string(),
});
export type PolicySummaryDto = z.infer<typeof PolicySummaryDtoSchema>;
