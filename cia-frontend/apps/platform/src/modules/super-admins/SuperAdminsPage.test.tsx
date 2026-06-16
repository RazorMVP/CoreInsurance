import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import SuperAdminsPage from './SuperAdminsPage';

vi.mock('@cia/auth', () => ({ useAuth: () => ({ user: { name: 'Root', email: 'root@x.test' } }) }));
vi.mock('@cia/api-client', () => ({
  useSuperAdmins: () => ({
    data: [
      { username: 'root@x.test', email: 'root@x.test', enabled: true },
      { username: 'sa2', email: 'sa2@x.test', enabled: true },
    ],
    isLoading: false, isError: false,
  }),
  useRevokeSuperAdmin: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useInviteSuperAdmin: () => ({ mutateAsync: vi.fn(), isPending: false }),
  platformErrorCode: () => undefined,
}));

function wrap(ui: React.ReactNode) { return render(<MemoryRouter>{ui}</MemoryRouter>); }

describe('SuperAdminsPage', () => {
  it('lists super-admins and disables Revoke on the current user row', () => {
    wrap(<SuperAdminsPage />);
    expect(screen.getByText('sa2')).toBeInTheDocument();
    // Self row's Revoke is disabled (UI hint), found by its title — markup-agnostic, so it doesn't
    // depend on the identical username/email cells in this fixture.
    expect(screen.getByTitle(/cannot revoke your own access/i)).toBeDisabled();
    // A non-self row (sa2) keeps Revoke enabled — the gate is selective.
    const sa2Row = screen.getByText('sa2').closest('tr')!;
    expect(within(sa2Row).getByRole('button', { name: /revoke/i })).toBeEnabled();
  });
});
