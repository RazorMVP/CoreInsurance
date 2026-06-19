export type ClauseType        = 'STANDARD' | 'EXCLUSION' | 'SPECIAL_CONDITION' | 'WARRANTY';
export type ClauseApplicability = 'MANDATORY' | 'OPTIONAL';

export interface ClauseRow {
  id:            string;
  title:         string;
  text:          string;
  type:          ClauseType;
  applicability: ClauseApplicability;
  productIds:    string[];
  productNames:  string[];
}

export const CLAUSE_TYPES = [
  { value: 'STANDARD' as const,          label: 'Standard' },
  { value: 'EXCLUSION' as const,         label: 'Exclusion' },
  { value: 'SPECIAL_CONDITION' as const, label: 'Special Condition' },
  { value: 'WARRANTY' as const,          label: 'Warranty' },
];
