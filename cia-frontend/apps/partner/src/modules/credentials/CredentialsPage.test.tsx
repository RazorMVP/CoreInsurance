import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { configurePortalClient } from '@cia/api-client';
import CredentialsPage from './CredentialsPage';
import { AppContextProvider } from '../../app/AppContext';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}><AppContextProvider><CredentialsPage /></AppContextProvider></QueryClientProvider>,
  );
}
beforeEach(() => { localStorage.clear(); localStorage.setItem('cia.portal.selectedAppId', 'app-1'); configurePortalClient({ baseURL: '', demoMode: true }); });

describe('CredentialsPage', () => {
  it('shows client id + scopes and reveals a rotated secret once', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('insurtech-aggregator')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: /rotate secret/i }));
    await waitFor(() => expect(screen.getByText(/demo-secret-/)).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: /dismiss/i }));
    expect(screen.queryByText(/demo-secret-/)).not.toBeInTheDocument();
  });
});
