import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { configurePortalClient } from '@cia/api-client';
import React from 'react';
import { AppContextProvider, useSelectedApp } from './AppContext';

function wrap() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={qc}><AppContextProvider>{children}</AppContextProvider></QueryClientProvider>
  );
}

beforeEach(() => { localStorage.clear(); configurePortalClient({ baseURL: '', demoMode: true }); });

describe('useSelectedApp', () => {
  it('lists apps and persists a selection', async () => {
    const { result } = renderHook(() => useSelectedApp(), { wrapper: wrap() });
    await waitFor(() => expect(result.current.apps.length).toBe(2));
    act(() => result.current.setSelectedAppId('app-2'));
    expect(result.current.selectedAppId).toBe('app-2');
    expect(localStorage.getItem('cia.portal.selectedAppId')).toBe('app-2');
  });
});
