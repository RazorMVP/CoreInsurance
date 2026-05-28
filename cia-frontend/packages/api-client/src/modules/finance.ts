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
import { apiClient } from '../client';
import { validatedList, validatedPost } from '../validation';

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

// ── Flat list responses (F7 slice α — GET /api/v1/receipts + /api/v1/payments) ────────

export const ReceiptListItemResponseSchema = z.object({
  id:               z.string(),
  reference:        z.string(),
  debitNoteId:      z.string(),
  debitNoteNumber:  z.string(),
  policyNumber:     z.string().nullable(),
  customerName:     z.string().nullable(),
  amount:           z.number(),
  paymentMethod:    PaymentMethodSchema,
  paymentDate:      z.string().nullable(),
  status:           ReceiptStatusSchema,
  reversedAt:       z.string().nullable(),
  reversedBy:       z.string().nullable(),
  reversalReason:   z.string().nullable(),
  createdAt:        z.string(),
  pdfPath:          z.string().nullable(),
  // Slice γ — email transmission. recipientEmail pre-resolved at projection;
  // emailSentAt + emailSentTo populated by the Temporal email workflow.
  recipientEmail:   z.string().nullable(),
  emailSentAt:      z.string().nullable(),
  emailSentTo:      z.string().nullable(),
  // Task 6.2 — SMS transmission. recipientPhone pre-resolved at projection;
  // smsSentAt + smsSentTo populated by the Temporal SMS workflow.
  recipientPhone:   z.string().nullable(),
  smsSentAt:        z.string().nullable(),
  smsSentTo:        z.string().nullable(),
});
export type ReceiptListItemResponse = z.infer<typeof ReceiptListItemResponseSchema>;

export const PaymentListItemResponseSchema = z.object({
  id:                   z.string(),
  reference:            z.string(),
  creditNoteId:         z.string(),
  creditNoteNumber:     z.string(),
  beneficiaryType:      z.string().nullable(),
  beneficiaryReference: z.string().nullable(),
  amount:               z.number(),
  paymentMethod:        PaymentMethodSchema,
  paymentDate:          z.string().nullable(),
  status:               PaymentStatusSchema,
  reversedAt:           z.string().nullable(),
  reversedBy:           z.string().nullable(),
  reversalReason:       z.string().nullable(),
  createdAt:            z.string(),
  pdfPath:              z.string().nullable(),
  // Slice γ — email transmission. recipientEmail resolved per row via
  // BeneficiaryEmailResolverDispatcher; emailSentAt + emailSentTo populated
  // by the Temporal payment-voucher email workflow.
  recipientEmail:       z.string().nullable(),
  emailSentAt:          z.string().nullable(),
  emailSentTo:          z.string().nullable(),
  // Task 6.2 — SMS transmission. recipientPhone resolved per row via
  // BeneficiaryPhoneResolverDispatcher; smsSentAt + smsSentTo populated
  // by the Temporal SMS workflow.
  recipientPhone:       z.string().nullable(),
  smsSentAt:            z.string().nullable(),
  smsSentTo:            z.string().nullable(),
});
export type PaymentListItemResponse = z.infer<typeof PaymentListItemResponseSchema>;

export interface ReceiptListFilters {
  status?:         'POSTED' | 'REVERSED';
  createdFrom?:    string;
  createdTo?:      string;
  paymentMethod?:  string;
  debitNoteId?:    string;
  page?:           number;
  size?:           number;
}

export interface PaymentListFilters {
  status?:         'POSTED' | 'REVERSED';
  createdFrom?:    string;
  createdTo?:      string;
  paymentMethod?:  string;
  creditNoteId?:   string;
  page?:           number;
  size?:           number;
}

function buildParams(filters: ReceiptListFilters | PaymentListFilters): Record<string, string> {
  const out: Record<string, string> = {};
  Object.entries(filters as Record<string, unknown>).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') out[k] = String(v);
  });
  return out;
}

export async function listReceipts(filters: ReceiptListFilters = {}) {
  return validatedList(
    '/api/v1/receipts',
    ReceiptListItemResponseSchema,
    { params: buildParams(filters) },
  );
}

export async function listPayments(filters: PaymentListFilters = {}) {
  return validatedList(
    '/api/v1/payments',
    PaymentListItemResponseSchema,
    { params: buildParams(filters) },
  );
}

/**
 * Streams the receipt PDF as a Blob for browser download. Uses
 * responseType:'blob' so axios doesn't try to JSON-parse the bytes.
 * Caller is responsible for filename synthesis + anchor-click side effect
 * (see useDownloadReceiptPdf hook).
 */
export async function downloadReceiptPdf(
  debitNoteId: string,
  receiptId:   string,
): Promise<Blob> {
  const res = await apiClient.get<Blob>(
    `/api/v1/debit-notes/${debitNoteId}/receipts/${receiptId}/pdf`,
    { responseType: 'blob' },
  );
  return res.data;
}

/**
 * Streams the payment voucher PDF as a Blob. Mirror of downloadReceiptPdf.
 */
export async function downloadPaymentPdf(
  creditNoteId: string,
  paymentId:    string,
): Promise<Blob> {
  const res = await apiClient.get<Blob>(
    `/api/v1/credit-notes/${creditNoteId}/payments/${paymentId}/pdf`,
    { responseType: 'blob' },
  );
  return res.data;
}

// ── Email transmission (F7 slice γ — POST /email returns 202 + { workflowId }) ────

const EmailWorkflowResponseSchema = z.object({ workflowId: z.string() });
export type EmailWorkflowResponse = z.infer<typeof EmailWorkflowResponseSchema>;

/**
 * Starts the Temporal SendReceiptEmailWorkflow. Returns the workflow id on
 * 202 enqueue. On 422 the backend returns errorCode (RECEIPT_PDF_UNAVAILABLE
 * / RECEIPT_RECIPIENT_UNRESOLVED) in the standard ApiResponse error envelope
 * — the calling hook surfaces the code in a toast.
 */
export async function emailReceipt(
  debitNoteId: string,
  receiptId:   string,
): Promise<EmailWorkflowResponse> {
  return validatedPost(
    `/api/v1/debit-notes/${debitNoteId}/receipts/${receiptId}/email`,
    {},
    EmailWorkflowResponseSchema,
  );
}

/**
 * Starts the Temporal SendPaymentVoucherEmailWorkflow. Mirror of
 * emailReceipt. 422 errorCodes are PAYMENT_PDF_UNAVAILABLE /
 * PAYMENT_RECIPIENT_UNRESOLVED.
 */
export async function emailPayment(
  creditNoteId: string,
  paymentId:    string,
): Promise<EmailWorkflowResponse> {
  return validatedPost(
    `/api/v1/credit-notes/${creditNoteId}/payments/${paymentId}/email`,
    {},
    EmailWorkflowResponseSchema,
  );
}

// ── F11: server-side download history + bulk download + email cancel ────

export const PdfDocumentTypeSchema = z.enum(['RECEIPT', 'PAYMENT']);
export type PdfDocumentType = z.infer<typeof PdfDocumentTypeSchema>;

export const PdfDownloadLogEntrySchema = z.object({
  id:             z.string(),
  entityType:     PdfDocumentTypeSchema,
  entityId:       z.string(),
  reference:      z.string(),
  parentId:       z.string().nullable(),
  parentRef:      z.string().nullable(),
  recipientName:  z.string().nullable(),
  downloadedAt:   z.string(),
});
export type PdfDownloadLogEntry = z.infer<typeof PdfDownloadLogEntrySchema>;

export interface BulkDownloadItem {
  type: PdfDocumentType;
  id:   string;
}

const EmailCancelResponseSchema = z.object({ cancelled: z.boolean() });
export type EmailCancelResponse = z.infer<typeof EmailCancelResponseSchema>;

/**
 * Lists the calling user's PDF downloads from the last N days, newest first.
 * Backend caps at 50 rows + at 30 days regardless of the days param.
 * Returns the standard { data, meta } envelope so callers can access
 * pagination metadata if needed.
 */
export async function listRecentDownloads(days = 1) {
  return validatedList(
    '/api/v1/finance/pdf-downloads',
    PdfDownloadLogEntrySchema,
    { params: { days: String(days) } },
  );
}

/**
 * Bulk-download N PDFs as a ZIP. Backend caps at 50 items; UI should
 * gate the trigger button before reaching this point.
 */
export async function bulkDownloadZip(items: BulkDownloadItem[]): Promise<Blob> {
  const res = await apiClient.post<Blob>(
    '/api/v1/finance/pdfs/bulk-download',
    { items },
    { responseType: 'blob' },
  );
  return res.data;
}

export async function cancelReceiptEmail(
  debitNoteId: string,
  receiptId:   string,
): Promise<EmailCancelResponse> {
  return validatedPost(
    `/api/v1/debit-notes/${debitNoteId}/receipts/${receiptId}/email/cancel`,
    {},
    EmailCancelResponseSchema,
  );
}

export async function cancelPaymentEmail(
  creditNoteId: string,
  paymentId:    string,
): Promise<EmailCancelResponse> {
  return validatedPost(
    `/api/v1/credit-notes/${creditNoteId}/payments/${paymentId}/email/cancel`,
    {},
    EmailCancelResponseSchema,
  );
}

// ── SMS transmission (F7-δ / R7 — POST /sms returns 202 + { workflowId }) ────
//
// Shape mirrors the email fetchers exactly — same envelope schemas
// (EmailWorkflowResponseSchema + EmailCancelResponseSchema), same
// validatedPost helper, same 422 / WORKFLOW_NOT_FOUND semantics.

/**
 * Starts the Temporal SendReceiptSmsWorkflow. Returns the workflow id on
 * 202 enqueue. 422 errorCodes: RECEIPT_PDF_UNAVAILABLE /
 * RECEIPT_RECIPIENT_PHONE_UNRESOLVED.
 */
export async function smsReceipt(
  debitNoteId: string,
  receiptId:   string,
): Promise<EmailWorkflowResponse> {
  return validatedPost(
    `/api/v1/debit-notes/${debitNoteId}/receipts/${receiptId}/sms`,
    {},
    EmailWorkflowResponseSchema,
  );
}

/**
 * Signals the in-flight SendReceiptSmsWorkflow to cancel before dispatch.
 * Best-effort — an activity already in flight completes normally.
 * 422 errorCode: WORKFLOW_NOT_FOUND.
 */
export async function cancelReceiptSms(
  debitNoteId: string,
  receiptId:   string,
): Promise<EmailCancelResponse> {
  return validatedPost(
    `/api/v1/debit-notes/${debitNoteId}/receipts/${receiptId}/sms/cancel`,
    {},
    EmailCancelResponseSchema,
  );
}

/**
 * Starts the Temporal SendPaymentVoucherSmsWorkflow. Mirror of smsReceipt.
 * 422 errorCodes: PAYMENT_PDF_UNAVAILABLE /
 * PAYMENT_RECIPIENT_PHONE_UNRESOLVED.
 */
export async function smsPayment(
  creditNoteId: string,
  paymentId:    string,
): Promise<EmailWorkflowResponse> {
  return validatedPost(
    `/api/v1/credit-notes/${creditNoteId}/payments/${paymentId}/sms`,
    {},
    EmailWorkflowResponseSchema,
  );
}

/**
 * Signals the in-flight SendPaymentVoucherSmsWorkflow to cancel before dispatch.
 * Mirror of cancelReceiptSms. 422 errorCode: WORKFLOW_NOT_FOUND.
 */
export async function cancelPaymentSms(
  creditNoteId: string,
  paymentId:    string,
): Promise<EmailCancelResponse> {
  return validatedPost(
    `/api/v1/credit-notes/${creditNoteId}/payments/${paymentId}/sms/cancel`,
    {},
    EmailCancelResponseSchema,
  );
}
