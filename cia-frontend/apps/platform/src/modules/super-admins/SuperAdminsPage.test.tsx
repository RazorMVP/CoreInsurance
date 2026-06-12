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
    // The current user (matched by email) cannot revoke self → that row's button is disabled.
    const selfRow = screen.getByText('root@x.test').closest('tr')!;
    const selfRevoke = within(selfRow).getByRole('button', { name: /revoke/i });
    expect(selfRevoke).toBeDisabled();
  });
});
