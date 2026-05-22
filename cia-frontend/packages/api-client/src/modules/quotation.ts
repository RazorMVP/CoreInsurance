// ── Quotation — DTOs ──────────────────────────────────────────────────────
//
// Session 95 / Backlog A3: rewrote QuoteDto + QuoteRiskDto to mirror the
// backend QuoteResponse + QuoteRiskResponse 1:1. The previous frontend types
// carried single-line summary fields (`sumInsured`, `premium`, `discount`,
// `netPremium`, `startDate`, `endDate`, `version`) from an older shape;
// the actual backend response models per-risk + quote-level loadings and
// discounts, with `totalSumInsured` / `totalGrossPremium` / `totalNetPremium`
// at the quote level and `policyStartDate` / `policyEndDate` for the period.
//
// Added supporting types: AdjustmentEntryDto (for per-risk + quote-level
// loadings/discounts), QuoteCoinsuranceParticipantDto, AdjustmentFormat enum.

import type { BusinessType } from './policy';

export type QuoteStatus      = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'CONVERTED' | 'EXPIRED';
export type AdjustmentFormat = 'PERCENT' | 'FLAT';
export type { BusinessType };

// Mirrors com.nubeero.cia.quotation.dto.AdjustmentEntryResponse — used for
// both per-risk and quote-level loadings and discounts. computedAmount is
// the resolved monetary amount the backend computed from value+format
// (PERCENT applied to its base, FLAT taken at face value).
export interface AdjustmentEntryDto {
  typeId:         string;
  typeName:       string;
  format:         AdjustmentFormat;
  value:          number;
  computedAmount: number;
}

// Mirrors com.nubeero.cia.quotation.dto.QuoteCoinsuranceParticipantResponse.
export interface QuoteCoinsuranceParticipantDto {
  id:                   string;
  insuranceCompanyId:   string;
  insuranceCompanyName: string;
  sharePercentage:      number;
}

// Mirrors com.nubeero.cia.quotation.dto.QuoteRiskResponse 1:1.
// `quoteId` is no longer declared — backend nests risks under the quote and
// doesn't echo the parent id on each row.
export interface QuoteRiskDto {
  id:            string;
  description:   string;
  sumInsured:    number;
  rate:          number;
  grossPremium:  number;
  premium:       number;
  sectionId?:    string | null;
  sectionName?:  string | null;
  riskDetails?:  Record<string, unknown> | null;
  loadings:      AdjustmentEntryDto[];
  discounts:     AdjustmentEntryDto[];
  orderNo:       number;
}

// Mirrors com.nubeero.cia.quotation.dto.QuoteResponse 1:1.
// Naming differences from the prior shape:
//   sumInsured       → totalSumInsured
//   premium          → totalGrossPremium
//   netPremium       → totalNetPremium
//   startDate        → policyStartDate
//   endDate          → policyEndDate
// `discount` (single field) removed — quote-level discounts live in the
// `quoteDiscounts` array; per-risk discounts live on each risk row.
// `version` removed — backend doesn't ship a version field on QuoteResponse.
export interface QuoteDto {
  id:                       string;
  quoteNumber:              string;
  status:                   QuoteStatus;

  customerId:               string;
  customerName:             string;

  productId:                string;
  productName:              string;
  productCode:              string;
  productRate:              number;

  classOfBusinessId:        string;
  classOfBusinessName:      string;

  brokerId?:                string | null;
  brokerName?:              string | null;

  businessType:             BusinessType;

  policyStartDate:          string;
  policyEndDate:            string;

  totalSumInsured:          number;
  totalGrossPremium:        number;
  totalNetPremium:          number;

  quoteLoadings:            AdjustmentEntryDto[];
  quoteDiscounts:           AdjustmentEntryDto[];
  selectedClauseIds:        string[];

  inputterName?:            string | null;
  approverName?:            string | null;

  notes?:                   string | null;
  workflowId?:              string | null;

  approvedBy?:              string | null;
  approvedAt?:              string | null;
  rejectedBy?:              string | null;
  rejectedAt?:              string | null;
  rejectionReason?:         string | null;
  expiresAt?:               string | null;

  risks:                    QuoteRiskDto[];
  coinsuranceParticipants:  QuoteCoinsuranceParticipantDto[];

  createdAt:                string;
  updatedAt:                string;
}
