import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import OnboardTenantSheet from './OnboardTenantSheet';

const onboardMock = vi.fn();
vi.mock('@cia/api-client', () => ({
  useOnboardTenant: () => ({ mutateAsync: onboardMock, isPending: false }),
}));

function wrap(ui: React.ReactNode) {
  const qc = new QueryClient();
  return render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>);
}

describe('OnboardTenantSheet', () => {
  beforeEach(() => {
    onboardMock.mockReset();
    Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
  });

  it('submits the form then reveals the one-time temp password', async () => {
    onboardMock.mockResolvedValue({
      tenant: { schema: 'tenant_acme', displayName: 'Acme', subdomain: 'acme', active: true, createdAt: '2026-06-10T00:00:00Z' },
      firstAdmin: { username: 'admin', email: 'a@acme.test', temporaryPassword: 'Aa1!revealed' },
    });

    wrap(<OnboardTenantSheet open onOpenChange={() => {}} />);

    await userEvent.type(screen.getByLabelText(/^schema/i), 'tenant_acme');
    await userEvent.type(screen.getByLabelText(/display name/i), 'Acme');
    await userEvent.type(screen.getByLabelText(/subdomain/i), 'acme');
    await userEvent.type(screen.getByLabelText(/admin username/i), 'admin');
    await userEvent.type(screen.getByLabelText(/admin email/i), 'a@acme.test');
    await userEvent.click(screen.getByRole('button', { name: /onboard/i }));

    await waitFor(() => expect(screen.getByText('Aa1!revealed')).toBeInTheDocument());
    expect(onboardMock).toHaveBeenCalledWith(expect.objectContaining({ schema: 'tenant_acme', adminEmail: 'a@acme.test' }));
  });
});
