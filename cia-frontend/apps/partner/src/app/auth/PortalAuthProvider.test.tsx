import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { configurePortalClient } from '@cia/api-client';
import { PortalAuthProvider } from './PortalAuthProvider';

function renderWithProviders() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <PortalAuthProvider><div>secured content</div></PortalAuthProvider>
    </QueryClientProvider>,
  );
}

describe('PortalAuthProvider', () => {
  beforeEach(() => configurePortalClient({ baseURL: '', demoMode: true }));
  it('renders children with a mock session in demo mode', async () => {
    renderWithProviders();
    await waitFor(() => expect(screen.getByText('secured content')).toBeInTheDocument());
  });
});
