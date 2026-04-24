// ── Policy — DTOs ─────────────────────────────────────────────────────────

export type PolicyStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'ACTIVE' | 'EXPIRED' | 'CANCELLED' | 'LAPSED';

export interface PolicyDto {
  id:               string;
  policyNumber:     string;
  quoteId?:         string;
  customerId:       string;
  customerName:     string;
  productId:        string;
  productName:      string;
  classOfBusinessId: string;
  classOfBusinessName: string;
  businessType:     string;
  status:           PolicyStatus;
  sumInsured:       number;
  premium:          number;
  netPremium:       number;
  startDate:        string;
  endDate:          string;
  naicomUid?:       string;
  niidUid?:         string;
  documentPath?:    string;
  debitNoteId?:     string;
  createdAt:        string;
  updatedAt:        string;
}

export interface PolicyRiskDto {
  id:          string;
  policyId:    string;
  description: string;
  sumInsured:  number;
  premium:     number;
  riskDetails: Record<string, unknown>;
}
