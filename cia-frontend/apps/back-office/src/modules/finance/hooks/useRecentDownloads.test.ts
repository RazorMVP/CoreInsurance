import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import React from 'react';
import { useRecentDownloads } from './useRecentDownloads';

vi.mock('@cia/api-client', () => ({
  listRecentDownloads: vi.fn(),
}));

// eslint-disable-next-line import/first
import { listRecentDownloads } from '@cia/api-client';

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

describe('useRecentDownloads', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('returns server data via useQuery', async () => {
    // listRecentDownloads → validatedList → returns { data: T[], meta: {...} }
    // allow-mock: Vitest fixture for hook test
    const mockEntry = {
      id: 'abc',
      entityType: 'RECEIPT' as const,
      entityId: 'rec-1',
      reference: 'REC-001',
      parentId: 'dn-1',
      parentRef: 'DN-001',
      recipientName: 'Test',
      downloadedAt: '2026-05-27T10:00:00Z',
    };
    // allow-mock: Vitest fixture for hook test
    const mockReturn = {
      data: [mockEntry],
      meta: { total: 1, page: 0, size: 20 },
    };
    (listRecentDownloads as ReturnType<typeof vi.fn>).mockResolvedValueOnce(mockReturn);

    const { result } = renderHook(() => useRecentDownloads(1), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    // hook returns the full envelope; consumer accesses entries via query.data?.data
    expect(result.current.data?.data).toHaveLength(1);
    expect(result.current.data?.data?.[0].reference).toBe('REC-001');
    expect(listRecentDownloads).toHaveBeenCalledWith(1);
  });
});
