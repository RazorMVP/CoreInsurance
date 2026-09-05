import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { configurePortalClient } from '@cia/api-client';
import ExplorerPage from './ExplorerPage';
import { AppContextProvider } from '../../app/AppContext';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}><AppContextProvider><ExplorerPage /></AppContextProvider></QueryClientProvider>);
}
beforeEach(() => { localStorage.clear(); localStorage.setItem('cia.portal.selectedAppId', 'app-1'); configurePortalClient({ baseURL: '', demoMode: true }); });

describe('ExplorerPage', () => {
  it('relays a 200 for GET products and a verbatim 403 for a scoped write', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByRole('button', { name: /send/i })).toBeEnabled());
    // default GET products → 200
    await userEvent.click(screen.getByRole('button', { name: /send/i }));
    await waitFor(() => expect(screen.getByText(/200/)).toBeInTheDocument());
    // switch to POST quotes → 403 verbatim
    await userEvent.selectOptions(screen.getByLabelText(/method/i), 'POST');
    await userEvent.clear(screen.getByLabelText(/path/i));
    await userEvent.type(screen.getByLabelText(/path/i), 'quotes');
    await userEvent.click(screen.getByRole('button', { name: /send/i }));
    await waitFor(() => expect(screen.getByText(/403/)).toBeInTheDocument());
    expect(screen.getByText(/INSUFFICIENT_SCOPE/)).toBeInTheDocument();
  });
});
