// ── Finance — DTOs ────────────────────────────────────────────────────────

export type ReceiptStatus  = 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'REVERSED';
export type PaymentStatus  = 'PENDING' | 'APPROVED' | 'PAID' | 'REVERSED';

export interface DebitNoteDto {
  id:           string;
  number:       string;
  policyId:     string;
  policyNumber: string;
  customerId:   string;
  customerName: string;
  amount:       number;
  status:       'OUTSTANDING' | 'PARTIALLY_PAID' | 'SETTLED';
  dueDate:      string;
  createdAt:    string;
}

export interface ReceiptDto {
  id:           string;
  receiptNumber: string;
  debitNoteId:  string;
  debitNoteNumber: string;
  amount:       number;
  paymentMethod: string;
  status:       ReceiptStatus;
  createdAt:    string;
}

export interface CreditNoteDto {
  id:           string;
  number:       string;
  sourceType:   'CLAIM' | 'ENDORSEMENT' | 'COMMISSION' | 'REINSURANCE';
  sourceId:     string;
  amount:       number;
  status:       'OUTSTANDING' | 'PAID';
  createdAt:    string;
}

export interface PaymentDto {
  id:           string;
  paymentNumber: string;
  creditNoteId: string;
  amount:       number;
  paymentMethod: string;
  status:       PaymentStatus;
  createdAt:    string;
}
