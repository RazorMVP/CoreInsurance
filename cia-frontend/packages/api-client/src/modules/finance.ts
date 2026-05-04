// ── Finance — DTOs ────────────────────────────────────────────────────────
//
// Field names below match the canonical backend response shape from
// cia-finance dto/* records. Do not rename or alias — there is no
// transforming interceptor in the api-client; field names flow through
// untouched. If the backend renames a field, update this file in lockstep
// with the controllers.

export type DebitNoteStatus    = 'OUTSTANDING' | 'PARTIAL' | 'SETTLED' | 'CANCELLED' | 'VOID';
export type CreditNoteStatus   = 'OUTSTANDING' | 'PARTIAL' | 'SETTLED' | 'CANCELLED';
export type ReceiptStatus      = 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'REVERSED';
export type PaymentStatus      = 'PENDING' | 'APPROVED' | 'PAID' | 'REVERSED';
export type FinanceEntityType  = 'POLICY' | 'ENDORSEMENT' | 'CLAIM' | 'CLAIM_EXPENSE' | 'COMMISSION' | 'REINSURANCE';

export interface DebitNoteDto {
  id:                string;
  debitNoteNumber:   string;
  status:            DebitNoteStatus;
  entityType:        FinanceEntityType;
  entityId:          string;
  entityReference:   string;     // e.g. policy number / endorsement number
  customerId:        string;
  customerName:      string;
  brokerId?:         string;
  brokerName?:       string;
  productName:       string;
  description:       string;
  amount:            number;     // pre-tax
  taxAmount:         number;
  totalAmount:       number;     // amount + taxAmount
  paidAmount:        number;
  outstandingAmount: number;     // totalAmount − paidAmount
  currencyCode:      string;
  dueDate:           string;
  createdAt:         string;
}

export interface ReceiptDto {
  id:               string;
  receiptNumber:    string;
  debitNoteId:      string;
  debitNoteNumber:  string;
  amount:           number;
  paymentMethod:    string;
  status:           ReceiptStatus;
  createdAt:        string;
}

export interface CreditNoteDto {
  id:                string;
  creditNoteNumber:  string;
  status:            CreditNoteStatus;
  entityType:        FinanceEntityType;
  entityId:          string;
  entityReference:   string;     // e.g. CLM-2026-00001 / END-... / POL-... / FAC-...
  beneficiaryId?:    string;
  beneficiaryName?:  string;
  description:       string;
  amount:            number;     // pre-tax
  taxAmount:         number;
  totalAmount:       number;
  paidAmount:        number;
  outstandingAmount: number;
  currencyCode:      string;
  dueDate:           string;
  createdAt:         string;
}

export interface PaymentDto {
  id:               string;
  paymentNumber:    string;
  creditNoteId:     string;
  amount:           number;
  paymentMethod:    string;
  status:           PaymentStatus;
  createdAt:        string;
}
