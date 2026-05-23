// ── Finance — schemas + derived types ─────────────────────────────────────
//
// Field names below match the canonical backend response shape from
// cia-finance dto/* records. Schemas are the source of truth — types are
// derived via z.infer<typeof Schema>.
//
// Use the validated* helpers from '@cia/api-client' to fetch and validate
// in one call:
//
//   import { validatedGet, DebitNoteDtoSchema } from '@cia/api-client';
//   const list = await validatedGet('/api/v1/finance/debit-notes', z.array(DebitNoteDtoSchema));
//
// If the backend renames a field, the response will fail zod validation
// loudly at runtime instead of silently passing undefined to the UI.

import { z } from 'zod';

// ── Enums ─────────────────────────────────────────────────────────────────

export const DebitNoteStatusSchema    = z.enum(['OUTSTANDING', 'PARTIAL', 'SETTLED', 'CANCELLED', 'VOID']);
export const CreditNoteStatusSchema   = z.enum(['OUTSTANDING', 'PARTIAL', 'SETTLED', 'CANCELLED']);
// Backend TransactionStatus enum: POSTED on creation; REVERSED on reversal.
// No approval state — receipt-post is a CREATE operation, not a workflow.
export const ReceiptStatusSchema      = z.enum(['POSTED', 'REVERSED']);
export const PaymentStatusSchema      = z.enum(['POSTED', 'REVERSED']);
export const FinanceEntityTypeSchema  = z.enum(['POLICY', 'ENDORSEMENT', 'CLAIM', 'CLAIM_EXPENSE', 'COMMISSION', 'REINSURANCE']);

export type DebitNoteStatus   = z.infer<typeof DebitNoteStatusSchema>;
export type CreditNoteStatus  = z.infer<typeof CreditNoteStatusSchema>;
export type ReceiptStatus     = z.infer<typeof ReceiptStatusSchema>;
export type PaymentStatus     = z.infer<typeof PaymentStatusSchema>;
export type FinanceEntityType = z.infer<typeof FinanceEntityTypeSchema>;

// ── DebitNote ─────────────────────────────────────────────────────────────

export const DebitNoteDtoSchema = z.object({
  id:                z.string(),
  debitNoteNumber:   z.string(),
  status:            DebitNoteStatusSchema,
  entityType:        FinanceEntityTypeSchema,
  entityId:          z.string(),
  entityReference:   z.string(),
  customerId:        z.string(),
  customerName:      z.string(),
  brokerId:          z.string().nullable().optional(),
  brokerName:        z.string().nullable().optional(),
  productName:       z.string(),
  description:       z.string().nullable().optional(),
  amount:            z.number(),
  taxAmount:         z.number(),
  totalAmount:       z.number(),
  paidAmount:        z.number(),
  outstandingAmount: z.number(),
  currencyCode:      z.string(),
  dueDate:           z.string(),
  createdAt:         z.string(),
});

export type DebitNoteDto = z.infer<typeof DebitNoteDtoSchema>;

// ── Receipt ───────────────────────────────────────────────────────────────

// Backend ReceiptResponse ships paymentDate / bankId / bankName / narration /
// postedBy / reversal* metadata as well. They were added optionally in Session
// 96 (Backlog C1) so PolicyDetailPage's Finance tab can render the receipts
// list with real values without breaking the ReceivablesTab consumer that
// only uses the original narrow set.
export const ReceiptDtoSchema = z.object({
  id:               z.string(),
  receiptNumber:    z.string(),
  debitNoteId:      z.string(),
  debitNoteNumber:  z.string(),
  amount:           z.number(),
  paymentMethod:    z.string(),
  status:           ReceiptStatusSchema,
  createdAt:        z.string(),
  // ── Optional details (Session 96 / Backlog C1) ───────────────────────────
  paymentDate:      z.string().nullable().optional(),
  bankId:           z.string().nullable().optional(),
  bankName:         z.string().nullable().optional(),
  chequeNumber:     z.string().nullable().optional(),
  narration:        z.string().nullable().optional(),
  postedBy:         z.string().nullable().optional(),
  // Reversal metadata — set when status === 'REVERSED'. Surfaced by Session
  // 116 (F3) extending the drift parser to zod-derived types; the fields have
  // shipped from ReceiptResponse since the reversal flow landed but weren't
  // declared on this Dto.
  reversedAt:       z.string().nullable().optional(),
  reversedBy:       z.string().nullable().optional(),
  reversalReason:   z.string().nullable().optional(),
});

export type ReceiptDto = z.infer<typeof ReceiptDtoSchema>;

// Request body for POST /api/v1/debit-notes/{dnId}/receipts. Mirrors
// com.nubeero.cia.finance.dto.PostReceiptRequest.
export const PaymentMethodSchema = z.enum(['CASH', 'CHEQUE', 'BANK_TRANSFER', 'DIRECT_DEBIT', 'MOBILE_MONEY', 'POS']);
export type PaymentMethod = z.infer<typeof PaymentMethodSchema>;

export const PostReceiptRequestSchema = z.object({
  amount:        z.number().min(0.01),
  paymentDate:   z.string().min(1),
  paymentMethod: PaymentMethodSchema,
  bankId:        z.string().nullable().optional(),
  bankName:      z.string().nullable().optional(),
  chequeNumber:  z.string().nullable().optional(),
  narration:     z.string().nullable().optional(),
});

export type PostReceiptRequest = z.infer<typeof PostReceiptRequestSchema>;

// ── CreditNote ────────────────────────────────────────────────────────────

export const CreditNoteDtoSchema = z.object({
  id:                z.string(),
  creditNoteNumber:  z.string(),
  status:            CreditNoteStatusSchema,
  entityType:        FinanceEntityTypeSchema,
  entityId:          z.string(),
  entityReference:   z.string(),
  beneficiaryId:     z.string().nullable().optional(),
  beneficiaryName:   z.string().nullable().optional(),
  description:       z.string().nullable().optional(),
  amount:            z.number(),
  taxAmount:         z.number(),
  totalAmount:       z.number(),
  paidAmount:        z.number(),
  outstandingAmount: z.number(),
  currencyCode:      z.string(),
  dueDate:           z.string(),
  createdAt:         z.string(),
});

export type CreditNoteDto = z.infer<typeof CreditNoteDtoSchema>;

// ── Payment ───────────────────────────────────────────────────────────────

// Backend PaymentResponse mirrors ReceiptResponse — full bank-detail block +
// reversal metadata. Declared optional here so consumers that need the detail
// (e.g. the per-CN payments tab inside CreditNoteDetailDialog) can render
// real values; consumers that only need the summary row don't have to touch
// anything. `creditNoteNumber` is shipped by the backend and used directly
// instead of cross-list lookups against the CN list.
export const PaymentDtoSchema = z.object({
  id:                 z.string(),
  paymentNumber:      z.string(),
  creditNoteId:       z.string(),
  creditNoteNumber:   z.string(),
  amount:             z.number(),
  paymentMethod:      z.string(),
  status:             PaymentStatusSchema,
  createdAt:          z.string(),
  // ── Optional details ───────────────────────────────────────────────────
  paymentDate:        z.string().nullable().optional(),
  bankId:             z.string().nullable().optional(),
  bankName:           z.string().nullable().optional(),
  bankAccountName:    z.string().nullable().optional(),
  bankAccountNumber:  z.string().nullable().optional(),
  narration:          z.string().nullable().optional(),
  postedBy:           z.string().nullable().optional(),
  reversalReason:     z.string().nullable().optional(),
  reversedAt:         z.string().nullable().optional(),
  reversedBy:         z.string().nullable().optional(),
});

export type PaymentDto = z.infer<typeof PaymentDtoSchema>;

// Request body for POST /api/v1/credit-notes/{cnId}/payments. Mirrors
// com.nubeero.cia.finance.dto.PostPaymentRequest.
export const PostPaymentRequestSchema = z.object({
  amount:             z.number().min(0.01),
  paymentDate:        z.string().min(1),
  paymentMethod:      PaymentMethodSchema,
  bankId:             z.string().nullable().optional(),
  bankName:           z.string().nullable().optional(),
  bankAccountName:    z.string().nullable().optional(),
  bankAccountNumber:  z.string().nullable().optional(),
  narration:          z.string().nullable().optional(),
});

export type PostPaymentRequest = z.infer<typeof PostPaymentRequestSchema>;
