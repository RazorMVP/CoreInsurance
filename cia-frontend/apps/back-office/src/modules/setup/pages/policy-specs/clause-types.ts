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

export const PRODUCTS = [
  { id: '1', name: 'Private Motor Comprehensive' },
  { id: '2', name: 'Commercial Vehicle' },
  { id: '3', name: 'Fire & Burglary Standard' },
  { id: '4', name: 'Marine Cargo Open Cover' },
] as const;

export const CLAUSE_TYPES = [
  { value: 'STANDARD' as const,          label: 'Standard' },
  { value: 'EXCLUSION' as const,         label: 'Exclusion' },
  { value: 'SPECIAL_CONDITION' as const, label: 'Special Condition' },
  { value: 'WARRANTY' as const,          label: 'Warranty' },
];

/** Shape passed from ClauseSheet.onSave to ClauseBankTab.handleSave */
export type ClauseSavePayload = Omit<ClauseRow, 'productNames' | 'id'> & { id?: string };
