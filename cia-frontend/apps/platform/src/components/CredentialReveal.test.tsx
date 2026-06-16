import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import CredentialReveal from './CredentialReveal';

describe('CredentialReveal', () => {
  beforeEach(() => {
    Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
  });

  it('renders the secret and gates Done behind Copy', async () => {
    const onDone = vi.fn();
    render(
      <CredentialReveal
        title="Tenant onboarded"
        subtitle="Acme · tenant_acme"
        identityLabel="Admin"
        identityValue="admin · admin@acme.test"
        secret="Aa1!x9Kc2pQ7mZ"
        onDone={onDone}
      />,
    );
    expect(screen.getByText('Aa1!x9Kc2pQ7mZ')).toBeInTheDocument();

    const done = screen.getByRole('button', { name: /done/i });
    expect(done).toBeDisabled();

    await userEvent.click(screen.getByRole('button', { name: /copy/i }));
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('Aa1!x9Kc2pQ7mZ');
    expect(done).toBeEnabled();

    await userEvent.click(done);
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('never writes the secret to localStorage', () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    render(
      <CredentialReveal title="t" subtitle="s" identityLabel="l" identityValue="v"
        secret="Aa1!secret" onDone={() => {}} />,
    );
    expect(setItem).not.toHaveBeenCalledWith(expect.anything(), expect.stringContaining('Aa1!secret'));
  });
});
