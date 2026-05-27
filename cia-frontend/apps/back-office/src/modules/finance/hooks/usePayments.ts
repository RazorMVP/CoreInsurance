import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  apiClient,
  downloadPaymentPdf,
  emailPayment,
  listPayments,
  type ApiError,
  type ApiResponse,
  type PaymentListFilters,
} from '@cia/api-client';
import { toast } from '@cia/ui';

interface ApiHttpError { response?: { data?: ApiResponse<unknown> }; message?: string }

export function usePaymentList(filters: PaymentListFilters) {
  return useQuery({
    queryKey: ['finance', 'payments', filters],
    queryFn: () => listPayments(filters),
    staleTime: 60_000,
  });
}

export interface ReversePaymentArgs {
  cnId:      string;
  paymentId: string;
  reason:    string;
}

export function useReversePayment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ cnId, paymentId, reason }: ReversePaymentArgs) => {
      await apiClient.post(
        `/api/v1/credit-notes/${cnId}/payments/${paymentId}/reverse`,
        { reason },
      );
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['finance', 'payments'] });
      queryClient.invalidateQueries({ queryKey: ['finance', 'credit-notes'] });
    },
  });
}

export interface DownloadPaymentPdfArgs {
  cnId:      string;
  paymentId: string;
  reference: string;        // for filename synthesis (e.g. "PAY-2026-00001")
}

export function useDownloadPaymentPdf() {
  return useMutation({
    mutationFn: async ({ cnId, paymentId, reference }: DownloadPaymentPdfArgs) => {
      const blob = await downloadPaymentPdf(cnId, paymentId);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `PAY-${reference}.pdf`;
      a.click();
      URL.revokeObjectURL(url);
    },
  });
}

export interface EmailPaymentArgs {
  cnId:      string;
  paymentId: string;
  reference: string;        // for toast — e.g. "PAY-2026-00001"
}

/**
 * Triggers the SendPaymentVoucherEmailWorkflow via POST /email. Surfaces
 * server errorCode in the failure toast (PAYMENT_PDF_UNAVAILABLE /
 * PAYMENT_RECIPIENT_UNRESOLVED). Invalidates payment queries on success.
 */
export function useEmailPayment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ cnId, paymentId }: EmailPaymentArgs) => {
      return await emailPayment(cnId, paymentId);
    },
    onSuccess: (_data, vars) => {
      queryClient.invalidateQueries({ queryKey: ['finance', 'payments'] });
      toast({
        title: 'Email queued',
        description: `Payment voucher ${vars.reference} will be delivered shortly. The "Last emailed" badge updates after delivery.`,
      });
    },
    onError: (error) => {
      const ax = error as ApiHttpError;
      const errors: ApiError[] = ax?.response?.data?.errors ?? [];
      const code = errors[0]?.code ?? '';
      let description: string;
      if (code === 'PAYMENT_PDF_UNAVAILABLE') {
        description = 'PDF not yet available for this payment. Try again in a moment or re-post the payment.';
      } else if (code === 'PAYMENT_RECIPIENT_UNRESOLVED') {
        description = 'No email on file for this beneficiary. Update the source record (broker / reinsurer / customer) before emailing.';
      } else {
        description = errors.length > 0
          ? errors.map(e => e.message).filter(Boolean).join('. ')
          : ax?.message ?? 'An unexpected error occurred. Please try again.';
      }
      toast({ variant: 'destructive', title: 'Email failed', description });
    },
  });
}
