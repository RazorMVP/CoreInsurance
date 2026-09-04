import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import React from 'react';
import { configurePortalClient, useApps, useUsage, useWebhooks, useCreateWebhook } from '@cia/api-client';

function wrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) => <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

beforeEach(() => configurePortalClient({ baseURL: '', demoMode: true }));

describe('portal hooks in demo mode', () => {
  it('useApps returns mock apps', async () => {
    const { result } = renderHook(() => useApps(), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.[0].clientId).toBe('insurtech-aggregator');
  });
  it('useUsage returns a real-shaped usage object', async () => {
    const { result } = renderHook(() => useUsage('app-1'), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.today.total).toBeGreaterThan(0);
    expect(result.current.data?.history.length).toBeGreaterThan(0);
  });
  it('useCreateWebhook adds a webhook', async () => {
    const w = wrapper();
    const { result: list } = renderHook(() => useWebhooks('app-1'), { wrapper: w });
    await waitFor(() => expect(list.current.isSuccess).toBe(true));
    const before = list.current.data?.length ?? 0;
    const { result: create } = renderHook(() => useCreateWebhook('app-1'), { wrapper: w });
    await create.current.mutateAsync({ targetUrl: 'https://x.example/h', secret: 'sixteen-char-secret!', eventTypes: ['policy.bound'] });
    expect(before).toBeGreaterThanOrEqual(1);
  });
});
