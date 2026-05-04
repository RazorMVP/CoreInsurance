// ── Reinsurance — schemas + derived types ─────────────────────────────────
//
// Mirrors the canonical backend response shapes from
// cia-reinsurance/dto/* records. Backend serves at /api/v1/ri/...
// (not /api/v1/reinsurance/... — common confusion; the directory is
// "reinsurance", the URL prefix is "ri").
//
// Use the validated* helpers from '@cia/api-client' to fetch + validate
// in one call:
//
//   import { validatedGet, TreatyDtoSchema } from '@cia/api-client';
//   const treaties = await validatedGet('/api/v1/ri/treaties', z.array(TreatyDtoSchema));
//
// Backend gaps NOT modelled here (require future backend work):
//   - Inward FAC entirely (list, create, renew, extend) — backend has
//     a single RiFacCover entity with no direction field
//   - Treaty PUT (edit) — backend has create + activate/expire/cancel
//     transitions only
//   - Batch reallocation — backend has single /confirm only
//   - FAC offer-slip / credit-note PDFs

import { z } from 'zod';

// ── Enums ─────────────────────────────────────────────────────────────────

export const TreatyTypeSchema       = z.enum(['SURPLUS', 'QUOTA_SHARE', 'XOL']);
export const TreatyStatusSchema     = z.enum(['DRAFT', 'ACTIVE', 'EXPIRED', 'CANCELLED']);
export const AllocationStatusSchema = z.enum(['DRAFT', 'CONFIRMED', 'CANCELLED']);
export const FacCoverStatusSchema   = z.enum(['PENDING', 'CONFIRMED', 'CANCELLED']);

export type TreatyType       = z.infer<typeof TreatyTypeSchema>;
export type TreatyStatus     = z.infer<typeof TreatyStatusSchema>;
export type AllocationStatus = z.infer<typeof AllocationStatusSchema>;
export type FacCoverStatus   = z.infer<typeof FacCoverStatusSchema>;

// ── Treaty ────────────────────────────────────────────────────────────────

export const TreatyParticipantDtoSchema = z.object({
  id:                       z.string(),
  reinsuranceCompanyId:     z.string(),
  reinsuranceCompanyName:   z.string(),
  sharePercentage:          z.number(),
  surplusLine:              z.number().nullable().optional(),
  isLead:                   z.boolean(),
  commissionRate:           z.number().nullable().optional(),
  createdAt:                z.string(),
});

export const TreatyDtoSchema = z.object({
  id:                  z.string(),
  treatyType:          TreatyTypeSchema,
  status:              TreatyStatusSchema,
  treatyYear:          z.number(),
  productId:           z.string().nullable().optional(),
  classOfBusinessId:   z.string().nullable().optional(),
  retentionLimit:      z.number().nullable().optional(),
  surplusCapacity:     z.number().nullable().optional(),
  xolPerRiskRetention: z.number().nullable().optional(),
  xolPerRiskLimit:     z.number().nullable().optional(),
  currencyCode:        z.string(),
  effectiveDate:       z.string(),
  expiryDate:          z.string(),
  description:         z.string().nullable().optional(),
  participants:        z.array(TreatyParticipantDtoSchema),
  createdAt:           z.string(),
});

export type TreatyParticipantDto = z.infer<typeof TreatyParticipantDtoSchema>;
export type TreatyDto            = z.infer<typeof TreatyDtoSchema>;

// ── Allocation ────────────────────────────────────────────────────────────

export const AllocationLineDtoSchema = z.object({
  id:                     z.string(),
  reinsuranceCompanyId:   z.string(),
  reinsuranceCompanyName: z.string(),
  sharePercentage:        z.number(),
  cededAmount:            z.number(),
  cededPremium:           z.number(),
  commissionRate:         z.number().nullable().optional(),
  commissionAmount:       z.number().nullable().optional(),
});

export const AllocationDtoSchema = z.object({
  id:                  z.string(),
  allocationNumber:    z.string(),
  policyId:            z.string(),
  policyNumber:        z.string(),
  endorsementId:       z.string().nullable().optional(),
  treatyId:            z.string().nullable().optional(),
  treatyType:          TreatyTypeSchema.nullable().optional(),
  status:              AllocationStatusSchema,
  ourShareSumInsured:  z.number(),
  retainedAmount:      z.number(),
  cededAmount:         z.number(),
  excessAmount:        z.number(),
  ourSharePremium:     z.number(),
  retainedPremium:     z.number(),
  cededPremium:        z.number(),
  currencyCode:        z.string(),
  lines:               z.array(AllocationLineDtoSchema),
  createdAt:           z.string(),
});

export type AllocationLineDto = z.infer<typeof AllocationLineDtoSchema>;
export type AllocationDto     = z.infer<typeof AllocationDtoSchema>;

// ── FAC Cover ─────────────────────────────────────────────────────────────
//
// Backend has a single RiFacCover entity (no direction field). The frontend
// "Outward" tab maps directly. The frontend "Inward" tab has no backend
// equivalent yet — see the deferred-gaps comment at the top of this file.

export const FacCoverDtoSchema = z.object({
  id:                       z.string(),
  facReference:             z.string(),
  policyId:                 z.string(),
  policyNumber:             z.string(),
  reinsuranceCompanyId:     z.string(),
  reinsuranceCompanyName:   z.string(),
  status:                   FacCoverStatusSchema,
  sumInsuredCeded:          z.number(),
  premiumRate:              z.number(),
  premiumCeded:             z.number(),
  commissionRate:           z.number(),
  commissionAmount:         z.number(),
  netPremium:               z.number(),
  currencyCode:             z.string(),
  coverFrom:                z.string(),
  coverTo:                  z.string(),
  offerSlipReference:       z.string().nullable().optional(),
  terms:                    z.string().nullable().optional(),
  approvedBy:               z.string().nullable().optional(),
  approvedAt:               z.string().nullable().optional(),
  cancelledBy:              z.string().nullable().optional(),
  cancelledAt:              z.string().nullable().optional(),
  cancellationReason:       z.string().nullable().optional(),
  createdAt:                z.string(),
});

export type FacCoverDto = z.infer<typeof FacCoverDtoSchema>;
