import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import React from 'react';
import BulkEmailSheet from './BulkEmailSheet';

// ── mock @cia/ui ─────────────────────────────────────────────────────────
// Radix portals/animations don't work in jsdom; replace with plain HTML.
vi.mock('@cia/ui', () => {
  const passthrough =
    (tag: string) =>
    ({ children, className }: { children?: React.ReactNode; className?: string }) =>
      React.createElement(tag, { className }, children);

  return {
    Badge:            ({ children, variant }: { children?: React.ReactNode; variant?: string }) =>
      React.createElement('span', { 'data-variant': variant }, children),
    Button:           ({ children, onClick, variant }: { children?: React.ReactNode; onClick?: () => void; variant?: string }) =>
      React.createElement('button', { onClick, 'data-variant': variant }, children),
    Sheet:            ({ children, open }: { children?: React.ReactNode; open?: boolean }) =>
      open ? React.createElement('div', { 'data-testid': 'sheet' }, children) : null,
    SheetContent:     passthrough('div'),
    SheetHeader:      passthrough('div'),
    SheetTitle:       passthrough('h2'),
    SheetDescription: passthrough('p'),
    SheetFooter:      passthrough('footer'),
  };
});

// ── mock hooks ────────────────────────────────────────────────────────────
vi.mock('../hooks/useReceipts', () => ({
  useEmailReceipt: () => ({
    mutateAsync: vi.fn(({ reference }: { reference: string }) => {
      if (reference === 'REC-002') return Promise.reject(new Error('send failed'));
      return Promise.resolve({ workflowId: 'wf-' + reference });
    }),
    mutate:    vi.fn(),
    isPending: false,
  }),
  useCancelReceiptEmail: () => ({
    mutateAsync: vi.fn(),
    mutate:      vi.fn(),
    isPending:   false,
  }),
}));

vi.mock('../hooks/usePayments', () => ({
  useEmailPayment: () => ({
    mutateAsync: vi.fn(),
    mutate:      vi.fn(),
    isPending:   false,
  }),
  useCancelPaymentEmail: () => ({
    mutateAsync: vi.fn(),
    mutate:      vi.fn(),
    isPending:   false,
  }),
}));

// ── wrapper ───────────────────────────────────────────────────────────────
function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return React.createElement(QueryClientProvider, { client }, children);
}

describe('BulkEmailSheet — serial runner', () => {
  beforeEach(() => vi.clearAllMocks());

  it('flips badges to sent/failed over 3 rows (2 succeed + 1 fails)', async () => {
    const user = userEvent.setup();
    const rows = [
      { id: 'r1', parentId: 'dn1', reference: 'REC-001' },
      { id: 'r2', parentId: 'dn2', reference: 'REC-002' }, // mocked to fail
      { id: 'r3', parentId: 'dn3', reference: 'REC-003' },
    ];

    render(
      React.createElement(BulkEmailSheet, {
        type: 'RECEIPT',
        rows,
        open: true,
        onOpenChange: () => {},
      }),
      { wrapper },
    );

    await user.click(screen.getByText('Send all'));

    await waitFor(() => {
      expect(
        screen.getByText('sent: 2 · failed: 1 · cancelled: 0'),
      ).toBeInTheDocument();
    });
  });
});
