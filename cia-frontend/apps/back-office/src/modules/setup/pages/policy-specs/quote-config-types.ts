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

// ── Shared format options ─────────────────────────────────────────────────────
export type AdjustmentFormat = 'PERCENT' | 'FLAT';

export interface AdjustmentEntry {
  id:       string;
  typeId:   string;
  format:   AdjustmentFormat;
  value:    number;
}
