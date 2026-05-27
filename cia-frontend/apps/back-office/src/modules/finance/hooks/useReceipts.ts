import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  apiClient,
  cancelReceiptEmail,
  downloadReceiptPdf,
  emailReceipt,
  listReceipts,
  type ApiError,
  type ApiResponse,
  type ReceiptListFilters,
} from '@cia/api-client';
import { toast } from '@cia/ui';

interface ApiHttpError { response?: { data?: ApiResponse<unknown> }; message?: string }

export function useReceiptList(filters: ReceiptListFilters) {
  return useQuery({
    queryKey: ['finance', 'receipts', filters],
    queryFn: () => listReceipts(filters),
    staleTime: 60_000,
  });
}

export interface ReverseReceiptArgs {
  dnId:      string;
  receiptId: string;
  reason:    string;
}

export function useReverseReceipt() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ dnId, receiptId, reason }: ReverseReceiptArgs) => {
      await apiClient.post(
        `/api/v1/debit-notes/${dnId}/receipts/${receiptId}/reverse`,
        { reason },
      );
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['finance', 'receipts'] });
      queryClient.invalidateQueries({ queryKey: ['finance', 'debit-notes'] });
    },
  });
}

export interface DownloadReceiptPdfArgs {
  dnId:      string;
  receiptId: string;
  reference: string;        // for filename synthesis (e.g. "REC-2026-00001")
}

export function useDownloadReceiptPdf() {
  return useMutation({
    mutationFn: async ({ dnId, receiptId, reference }: DownloadReceiptPdfArgs) => {
      const blob = await downloadReceiptPdf(dnId, receiptId);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `REC-${reference}.pdf`;
      a.click();
      URL.revokeObjectURL(url);
    },
  });
}

export interface EmailReceiptArgs {
  dnId:      string;
  receiptId: string;
  reference: string;        // for toast — e.g. "REC-2026-00001"
}

/**
 * Triggers the SendReceiptEmailWorkflow via POST /email. Surfaces server
 * errorCode in the failure toast (RECEIPT_PDF_UNAVAILABLE /
 * RECEIPT_RECIPIENT_UNRESOLVED). Invalidates receipt queries on success so
 * the "Last emailed at" badge reflects the latest send timestamp once the
 * workflow completes (next list refresh).
 */
export function useEmailReceipt() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ dnId, receiptId }: EmailReceiptArgs) => {
      return await emailReceipt(dnId, receiptId);
    },
    onSuccess: (_data, vars) => {
      queryClient.invalidateQueries({ queryKey: ['finance', 'receipts'] });
      toast({
        title: 'Email queued',
        description: `Receipt ${vars.reference} will be delivered shortly. The "Last emailed" badge updates after delivery.`,
      });
    },
    onError: (error) => {
      const ax = error as ApiHttpError;
      const errors: ApiError[] = ax?.response?.data?.errors ?? [];
      const code = errors[0]?.code ?? '';
      let description: string;
      if (code === 'RECEIPT_PDF_UNAVAILABLE') {
        description = 'PDF not yet available for this receipt. Try again in a moment or re-post the receipt.';
      } else if (code === 'RECEIPT_RECIPIENT_UNRESOLVED') {
        description = 'No email on file for this customer. Update the customer record before emailing.';
      } else {
        description = errors.length > 0
          ? errors.map(e => e.message).filter(Boolean).join('. ')
          : ax?.message ?? 'An unexpected error occurred. Please try again.';
      }
      toast({ variant: 'destructive', title: 'Email failed', description });
    },
  });
}

export interface CancelReceiptEmailArgs {
  dnId:      string;
  receiptId: string;
  reference: string;       // for toast
}

/**
 * Signals the Temporal SendReceiptEmailWorkflow to cancel. Best-effort
 * — see workflow Javadoc. UI surfaces success/error toast and
 * invalidates the receipts list so any "Last emailed" badge state
 * reflects the post-cancel result.
 */
export function useCancelReceiptEmail() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ dnId, receiptId }: CancelReceiptEmailArgs) => {
      return await cancelReceiptEmail(dnId, receiptId);
    },
    onSuccess: (_data, vars) => {
      queryClient.invalidateQueries({ queryKey: ['finance', 'receipts'] });
      toast({
        title: 'Email cancelled',
        description: `Cancel signal sent for receipt ${vars.reference}. In-flight delivery may still complete (best-effort).`,
      });
    },
    onError: (error) => {
      const ax = error as ApiHttpError;
      const errors: ApiError[] = ax?.response?.data?.errors ?? [];
      const code = errors[0]?.code ?? '';
      const description = code === 'WORKFLOW_NOT_FOUND'
        ? 'The email workflow has already completed or never started — nothing to cancel.'
        : (errors.length > 0
            ? errors.map(e => e.message).filter(Boolean).join('. ')
            : ax?.message ?? 'Cancel failed.');
      toast({ variant: 'destructive', title: 'Cancel failed', description });
    },
  });
}
