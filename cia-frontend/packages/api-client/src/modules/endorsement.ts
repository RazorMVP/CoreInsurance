// ── Endorsements — DTOs ───────────────────────────────────────────────────

import { z } from 'zod';

export type EndorsementStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED';
export type EndorsementType =
  | 'RENEWAL'
  | 'EXTENSION'
  | 'CANCELLATION'
  | 'REVERSAL'
  | 'REDUCTION'
  | 'CHANGE_PERIOD'
  | 'INCREASE_SI'
  | 'DECREASE_SI'
  | 'ADD_ITEMS'
  | 'DELETE_ITEMS';

export const ENDORSEMENT_TYPE_LABELS: Record<EndorsementType, string> = {
  RENEWAL:       'Renewal',
  EXTENSION:     'Extension of Period',
  CANCELLATION:  'Cancellation',
  REVERSAL:      'Reversal',
  REDUCTION:     'Reduction in Period',
  CHANGE_PERIOD: 'Change in Period',
  INCREASE_SI:   'Increase Sum Insured',
  DECREASE_SI:   'Decrease Sum Insured',
  ADD_ITEMS:     'Add Insured Items',
  DELETE_ITEMS:  'Delete Insured Items',
};

// Mirror of EndorsementRiskResponse (cia-endorsement.dto).
export const EndorsementRiskDtoSchema = z.object({
  id:               z.string(),
  description:      z.string(),
  sumInsured:       z.number(),
  premium:          z.number(),
  sectionId:        z.string().nullable(),
  sectionName:      z.string().nullable(),
  riskDetails:      z.record(z.string(), z.unknown()).nullable(),
  vehicleRegNumber: z.string().nullable(),
  orderNo:          z.number(),
});
export type EndorsementRiskDto = z.infer<typeof EndorsementRiskDtoSchema>;

// Mirror of EndorsementResponse (cia-endorsement.dto) 1:1.
// The old-vs-new diff lives in (oldSumInsured / newSumInsured / oldNetPremium /
// newNetPremium / premiumAdjustment). premiumAdjustment is the signed pro-rata
// delta — negative means a credit note will be raised on approval.
export const EndorsementDtoSchema = z.object({
  id:                  z.string(),
  endorsementNumber:   z.string(),
  status:              z.enum(['DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED']),
  endorsementType:     z.enum(['RENEWAL', 'EXTENSION', 'CANCELLATION', 'REVERSAL', 'REDUCTION', 'CHANGE_PERIOD', 'INCREASE_SI', 'DECREASE_SI', 'ADD_ITEMS', 'DELETE_ITEMS']),
  policyId:            z.string(),
  policyNumber:        z.string(),
  customerId:          z.string(),
  customerName:        z.string(),
  productName:         z.string(),
  classOfBusinessName: z.string(),
  brokerId:            z.string().nullable(),
  brokerName:          z.string().nullable(),
  effectiveDate:       z.string(),
  policyEndDate:       z.string(),
  remainingDays:       z.number(),
  oldSumInsured:       z.number(),
  newSumInsured:       z.number(),
  oldNetPremium:       z.number(),
  newNetPremium:       z.number(),
  premiumAdjustment:   z.number(),
  currencyCode:        z.string(),
  description:         z.string().nullable(),
  notes:               z.string().nullable(),
  approvedBy:          z.string().nullable(),
  approvedAt:          z.string().nullable(),
  rejectedBy:          z.string().nullable(),
  rejectedAt:          z.string().nullable(),
  rejectionReason:     z.string().nullable(),
  createdAt:           z.string(),
  risks:               z.array(EndorsementRiskDtoSchema),
});
export type EndorsementDto = z.infer<typeof EndorsementDtoSchema>;
