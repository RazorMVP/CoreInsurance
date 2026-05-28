import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import React from 'react';
import { useSmsReceipt } from './useReceipts';

vi.mock('@cia/api-client', () => ({
  smsReceipt: vi.fn(),
}));

vi.mock('@cia/ui', () => ({
  toast: vi.fn(),
}));

// eslint-disable-next-line import/first
import { smsReceipt } from '@cia/api-client';
// eslint-disable-next-line import/first
import { toast } from '@cia/ui';

function wrapper({ children }: { children: React.ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return React.createElement(
    QueryClientProvider,
    { client: queryClient },
    children,
  );
}

describe('useSmsReceipt', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('calls smsReceipt(dnId, receiptId) with the right args on mutate', async () => {
    (smsReceipt as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      workflowId: 'wf-1',
    });

    const { result } = renderHook(() => useSmsReceipt(), { wrapper });

    result.current.mutate({
      dnId: 'dn-1',
      receiptId: 'rec-1',
      reference: 'REC-2026-00001',
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(smsReceipt).toHaveBeenCalledWith('dn-1', 'rec-1');
  });

  it('surfaces the phone-unresolved toast on a 422 RECEIPT_RECIPIENT_PHONE_UNRESOLVED error', async () => {
    // allow-mock: axios-shaped error fixture for the 422 path
    const axiosError = {
      response: {
        data: {
          errors: [
            {
              code: 'RECEIPT_RECIPIENT_PHONE_UNRESOLVED',
              message: 'no phone',
            },
          ],
        },
      },
    };
    (smsReceipt as ReturnType<typeof vi.fn>).mockRejectedValueOnce(axiosError);

    const { result } = renderHook(() => useSmsReceipt(), { wrapper });

    result.current.mutate({
      dnId: 'dn-1',
      receiptId: 'rec-1',
      reference: 'REC-2026-00001',
    });

    await waitFor(() => expect(result.current.isError).toBe(true));

    expect(toast).toHaveBeenCalledWith({
      variant: 'destructive',
      title: 'SMS failed',
      description:
        'No phone on file for this customer. Update the customer record before texting.',
    });
  });
});
