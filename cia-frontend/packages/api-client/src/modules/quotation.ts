// ── Quotation — DTOs ──────────────────────────────────────────────────────

import type { BusinessType } from './policy';

export type QuoteStatus   = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'CONVERTED' | 'EXPIRED';
export type { BusinessType };

export interface QuoteDto {
  id:               string;
  quoteNumber:      string;
  customerId:       string;
  customerName:     string;
  productId:        string;
  productName:      string;
  classOfBusinessId: string;
  classOfBusinessName: string;
  businessType:     BusinessType;
  status:           QuoteStatus;
  sumInsured:       number;
  premium:          number;
  discount:         number;
  netPremium:       number;
  startDate:        string;
  endDate:          string;
  version:          number;
  createdAt:        string;
  updatedAt:        string;
}

export interface QuoteRiskDto {
  id:          string;
  quoteId:     string;
  description: string;
  sumInsured:  number;
  rate:        number;
  premium:     number;
  riskDetails: Record<string, unknown>;
}
