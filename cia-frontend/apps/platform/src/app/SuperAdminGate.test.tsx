import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import SuperAdminGate from './SuperAdminGate';

vi.mock('@cia/auth', () => ({
  useAuth: vi.fn(),
}));
import { useAuth } from '@cia/auth';

describe('SuperAdminGate', () => {
  it('renders children when the user has SUPER_ADMIN', () => {
    (useAuth as unknown as ReturnType<typeof vi.fn>).mockReturnValue({ hasRole: (r: string) => r === 'SUPER_ADMIN' });
    render(<SuperAdminGate><div>secret console</div></SuperAdminGate>);
    expect(screen.getByText('secret console')).toBeInTheDocument();
  });

  it('renders Not authorized when the user lacks SUPER_ADMIN', () => {
    (useAuth as unknown as ReturnType<typeof vi.fn>).mockReturnValue({ hasRole: () => false });
    render(<SuperAdminGate><div>secret console</div></SuperAdminGate>);
    expect(screen.queryByText('secret console')).not.toBeInTheDocument();
    expect(screen.getByText(/not authorized/i)).toBeInTheDocument();
  });
});
