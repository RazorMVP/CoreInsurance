import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  apiClient,
  downloadReceiptPdf,
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
