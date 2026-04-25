export type TemplateType =
  | 'POLICY_DOCUMENT'
  | 'CERTIFICATE'
  | 'SCHEDULE'
  | 'DEBIT_NOTE'
  | 'ENDORSEMENT'
  | 'OTHER';

export interface TemplateRow {
  id:          string;
  productId:   string;
  productName: string;
  name:        string;
  filename:    string;
  type:        TemplateType;
  status:      'ACTIVE' | 'ARCHIVED';
  uploadedAt:  string;
}

export const TEMPLATE_TYPES: { value: TemplateType; label: string }[] = [
  { value: 'POLICY_DOCUMENT', label: 'Policy Document' },
  { value: 'CERTIFICATE',     label: 'Certificate' },
  { value: 'SCHEDULE',        label: 'Schedule' },
  { value: 'DEBIT_NOTE',      label: 'Debit Note' },
  { value: 'ENDORSEMENT',     label: 'Endorsement' },
  { value: 'OTHER',           label: 'Other' },
];
