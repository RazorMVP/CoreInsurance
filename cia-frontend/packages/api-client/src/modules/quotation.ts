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

import { z } from 'zod';
import type { BusinessType, ClauseSnapshotDto } from './policy';
import { BusinessTypeSchema } from './policy';

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

  // Per-quote agent attribution (Slice B1a / V55). Mutually exclusive with
  // brokerId via ck_quotes_broker_xor_agent — frontend can render either
  // (Broker · name) or (Agent · name), never both.
  agentId?:                 string | null;
  agentName?:               string | null;

  businessType:             BusinessType;

  policyStartDate:          string;
  policyEndDate:            string;

  totalSumInsured:          number;
  totalGrossPremium:        number;
  totalNetPremium:          number;

  quoteLoadings:            AdjustmentEntryDto[];
  quoteDiscounts:           AdjustmentEntryDto[];
  selectedClauseIds:        string[];
  selectedClauses?:         ClauseSnapshotDto[];

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

/**
 * The lean list/search projection returned by `GET /api/v1/quotes` (and
 * `/search`) — mirrors backend `QuoteSummaryResponse`, NOT the full `QuoteDto`.
 * Notably the summary exposes `netPremium` (not the full DTO's `totalNetPremium`)
 * and omits risks/clauses/adjustments. List pages MUST bind to this type — using
 * `QuoteDto` lets TS believe fields exist that the summary payload never sends.
 */
export const QuoteSummaryDtoSchema = z.object({
  id:                  z.string(),
  quoteNumber:         z.string(),
  status:              z.enum(['DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'CONVERTED', 'EXPIRED']),
  customerId:          z.string(),
  customerName:        z.string(),
  productName:         z.string(),
  classOfBusinessName: z.string(),
  brokerName:          z.string().nullable().optional(),
  agentName:           z.string().nullable().optional(),
  businessType:        BusinessTypeSchema,
  policyStartDate:     z.string(),
  policyEndDate:       z.string(),
  totalSumInsured:     z.number(),
  netPremium:          z.number(),
  expiresAt:           z.string().nullable().optional(),
  createdAt:           z.string(),
});
export type QuoteSummaryDto = z.infer<typeof QuoteSummaryDtoSchema>;
