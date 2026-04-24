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

export interface EndorsementDto {
  id:               string;
  endorsementNumber:string;
  policyId:         string;
  policyNumber:     string;
  status:           EndorsementStatus;
  endorsementType:  EndorsementType;
  sumInsured:       number;
  premium:          number;
  startDate:        string;
  endDate:          string;
  createdAt:        string;
  updatedAt:        string;
}
