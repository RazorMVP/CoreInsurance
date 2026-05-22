// ── Endorsements — DTOs ───────────────────────────────────────────────────

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
export interface EndorsementRiskDto {
  id:                string;
  description:       string;
  sumInsured:        number;
  premium:           number;
  sectionId:         string | null;
  sectionName:       string | null;
  riskDetails:       Record<string, unknown> | null;
  vehicleRegNumber:  string | null;
  orderNo:           number;
}

// Mirror of EndorsementResponse (cia-endorsement.dto) 1:1.
// The old-vs-new diff lives in (oldSumInsured / newSumInsured / oldNetPremium /
// newNetPremium / premiumAdjustment). premiumAdjustment is the signed pro-rata
// delta — negative means a credit note will be raised on approval.
export interface EndorsementDto {
  id:                  string;
  endorsementNumber:   string;
  status:              EndorsementStatus;
  endorsementType:     EndorsementType;
  policyId:            string;
  policyNumber:        string;
  customerId:          string;
  customerName:        string;
  productName:         string;
  classOfBusinessName: string;
  brokerId:            string | null;
  brokerName:          string | null;
  effectiveDate:       string;
  policyEndDate:       string;
  remainingDays:       number;
  oldSumInsured:       number;
  newSumInsured:       number;
  oldNetPremium:       number;
  newNetPremium:       number;
  premiumAdjustment:   number;
  currencyCode:        string;
  description:         string | null;
  notes:               string | null;
  approvedBy:          string | null;
  approvedAt:          string | null;
  rejectedBy:          string | null;
  rejectedAt:          string | null;
  rejectionReason:     string | null;
  createdAt:           string;
  risks:               EndorsementRiskDto[];
}
