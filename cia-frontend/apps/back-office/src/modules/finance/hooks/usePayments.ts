import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  apiClient,
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
        { reversalReason: reason },
      );
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['finance', 'payments'] });
      queryClient.invalidateQueries({ queryKey: ['finance', 'credit-notes'] });
    },
  });
}
