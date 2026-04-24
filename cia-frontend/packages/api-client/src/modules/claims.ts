// ── Claims — DTOs ─────────────────────────────────────────────────────────

export type ClaimStatus = 'REGISTERED' | 'PROCESSING' | 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED' | 'SETTLED' | 'CLOSED' | 'WITHDRAWN';

export interface ClaimDto {
  id:               string;
  claimNumber:      string;
  policyId:         string;
  policyNumber:     string;
  customerId:       string;
  customerName:     string;
  status:           ClaimStatus;
  incidentDate:     string;
  registeredDate:   string;
  description:      string;
  estimatedLoss:    number;
  reserveAmount:    number;
  paidAmount:       number;
  surveyorId?:      string;
  surveyorName?:    string;
  createdAt:        string;
  updatedAt:        string;
}

export interface ClaimReserveDto {
  id:         string;
  claimId:    string;
  category:   string;
  amount:     number;
  notes?:     string;
  createdAt:  string;
}

export interface ClaimExpenseDto {
  id:         string;
  claimId:    string;
  type:       string;
  amount:     number;
  status:     'PENDING' | 'APPROVED' | 'PAID';
  createdAt:  string;
}
