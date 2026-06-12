import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ServerPaginationFooter from './ServerPaginationFooter';

describe('ServerPaginationFooter', () => {
  it('disables Previous on the first page and Next on the last', () => {
    const onPage = vi.fn();
    render(<ServerPaginationFooter page={0} size={50} total={120} onPageChange={onPage} noun="tenants" />);
    expect(screen.getByText(/showing 1–50 of 120/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /previous/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /next/i })).toBeEnabled();
  });

  it('disables Next on the last page', () => {
    render(<ServerPaginationFooter page={2} size={50} total={120} onPageChange={() => {}} noun="rows" />);
    expect(screen.getByRole('button', { name: /next/i })).toBeDisabled();
  });

  it('advances the page', async () => {
    const onPage = vi.fn();
    render(<ServerPaginationFooter page={0} size={50} total={120} onPageChange={onPage} noun="rows" />);
    await userEvent.click(screen.getByRole('button', { name: /next/i }));
    expect(onPage).toHaveBeenCalledWith(1);
  });
});
