import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  apiClient,
  listReceipts,
  type ReceiptListFilters,
} from '@cia/api-client';

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
        { reversalReason: reason },
      );
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['finance', 'receipts'] });
      queryClient.invalidateQueries({ queryKey: ['finance', 'debit-notes'] });
    },
  });
}
