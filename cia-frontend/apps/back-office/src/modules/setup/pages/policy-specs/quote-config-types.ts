export interface DiscountType {
  id:   string;
  name: string;
}

export interface LoadingType {
  id:   string;
  name: string;
}

export type CalcSequence = 'LOADING_FIRST' | 'DISCOUNT_FIRST';

export interface QuoteConfig {
  validityDays:    number;
  calcSequence:    CalcSequence;
}

// ── Shared mock data (used by quote sheets + PDF preview) ─────────────────────
export const MOCK_DISCOUNT_TYPES: DiscountType[] = [
  { id: 'd1', name: 'No Claims Discount (NCD)' },
  { id: 'd2', name: 'Fleet Discount' },
  { id: 'd3', name: 'Loyalty Discount' },
  { id: 'd4', name: 'Group Scheme Discount' },
  { id: 'd5', name: 'Long-term Policy Discount' },
];

export const MOCK_LOADING_TYPES: LoadingType[] = [
  { id: 'l1', name: 'Adverse Risk Loading' },
  { id: 'l2', name: 'High Claims History' },
  { id: 'l3', name: 'Occupation Loading' },
  { id: 'l4', name: 'Age Loading' },
  { id: 'l5', name: 'Location Hazard Loading' },
];

export const MOCK_QUOTE_CONFIG: QuoteConfig = {
  validityDays: 30,
  calcSequence: 'LOADING_FIRST',
};

// ── Shared format options ─────────────────────────────────────────────────────
export type AdjustmentFormat = 'PERCENT' | 'FLAT';

export interface AdjustmentEntry {
  id:       string;
  typeId:   string;
  format:   AdjustmentFormat;
  value:    number;
}
