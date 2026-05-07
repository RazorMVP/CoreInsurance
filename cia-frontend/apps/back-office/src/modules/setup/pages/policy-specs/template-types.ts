export type TemplateType =
  | 'POLICY'
  | 'ENDORSEMENT'
  | 'CLAIM_DV'
  | 'NAICOM_CERTIFICATE';

export interface TemplateRow {
  id:                 string;
  productId?:         string | null;
  classOfBusinessId?: string | null;
  productName?:       string;
  storagePath:        string;
  description?:       string | null;
  type:               TemplateType;
  status:             'ACTIVE' | 'ARCHIVED';
  uploadedAt:         string;
}

export const TEMPLATE_TYPES: { value: TemplateType; label: string }[] = [
  { value: 'POLICY',             label: 'Policy' },
  { value: 'ENDORSEMENT',        label: 'Endorsement' },
  { value: 'CLAIM_DV',           label: 'Claim DV' },
  { value: 'NAICOM_CERTIFICATE', label: 'NAICOM Certificate' },
];
