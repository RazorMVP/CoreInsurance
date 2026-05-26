import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  apiClient,
  downloadPaymentPdf,
  listPayments,
  type PaymentListFilters,
} from '@cia/api-client';

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
