import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { configurePortalClient } from '@cia/api-client';
import UsagePage from './UsagePage';
import { AppContextProvider } from '../../app/AppContext';

// Recharts needs a sized container in jsdom; stub ResponsiveContainer.
vi.mock('recharts', async (orig) => {
  const actual = await orig<typeof import('recharts')>();
  return { ...actual, ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div style={{ width: 800, height: 300 }}>{children}</div> };
});

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}><AppContextProvider><UsagePage /></AppContextProvider></QueryClientProvider>);
}
beforeEach(() => { localStorage.clear(); localStorage.setItem('cia.portal.selectedAppId', 'app-1'); configurePortalClient({ baseURL: '', demoMode: true }); });

describe('UsagePage', () => {
  it('shows today total and an error-rate percent', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText(/requests today/i)).toBeInTheDocument());
    expect(screen.getByText('412')).toBeInTheDocument();
    expect(screen.getByText(/%$/)).toBeInTheDocument();
  });
});
