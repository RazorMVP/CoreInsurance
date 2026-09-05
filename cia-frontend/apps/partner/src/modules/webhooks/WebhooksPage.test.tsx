import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { configurePortalClient } from '@cia/api-client';
import WebhooksPage from './WebhooksPage';
import { AppContextProvider } from '../../app/AppContext';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}><AppContextProvider><WebhooksPage /></AppContextProvider></QueryClientProvider>);
}
beforeEach(() => { localStorage.clear(); localStorage.setItem('cia.portal.selectedAppId', 'app-1'); configurePortalClient({ baseURL: '', demoMode: true }); });
// This file's `globals: false` vitest config means Testing Library's automatic
// afterEach(cleanup) registration never fires (it detects a global `afterEach`) — without
// this, two `it` blocks in one file leave a stale DOM tree from the previous render mounted
// alongside the next one.
afterEach(cleanup);

describe('WebhooksPage', () => {
  it('lists existing webhooks by targetUrl and validates the secret length', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('https://insurtech.example/hooks/cia')).toBeInTheDocument());
    await userEvent.type(screen.getByLabelText(/target url/i), 'https://x.example/h');
    await userEvent.type(screen.getByLabelText(/signing secret/i), 'short');
    await userEvent.click(screen.getByLabelText(/policy.bound/i));
    await userEvent.click(screen.getByRole('button', { name: /register/i }));
    expect(screen.getByText(/at least 16 characters/i)).toBeInTheDocument();
  });

  it('requires a second confirm click before deleting a webhook (I4)', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('https://insurtech.example/hooks/cia')).toBeInTheDocument());

    const deleteButton = screen.getByRole('button', { name: /^delete$/i });
    await userEvent.click(deleteButton);

    // First click only reveals the confirm step — the webhook must still be listed.
    expect(screen.getByText(/confirm delete\?/i)).toBeInTheDocument();
    expect(screen.getByText('https://insurtech.example/hooks/cia')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /^confirm$/i }));

    await waitFor(() => expect(screen.queryByText('https://insurtech.example/hooks/cia')).not.toBeInTheDocument());
  });
});
