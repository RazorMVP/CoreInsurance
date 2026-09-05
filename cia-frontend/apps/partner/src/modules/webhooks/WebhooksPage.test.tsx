import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
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
});
