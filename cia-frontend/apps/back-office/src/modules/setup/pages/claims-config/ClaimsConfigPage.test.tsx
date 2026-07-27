import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import React from 'react';
import ClaimsConfigPage from './ClaimsConfigPage';
import type { ClaimReserveCategoryDto } from '@cia/api-client';

// ── mock @cia/ui (DataTable + DataTableRowActions only) — mirrors S3a ────────
vi.mock('@cia/ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@cia/ui')>();
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  function DataTable({ columns, data }: { columns: any[]; data: any[] }) {
    return React.createElement('table', null, React.createElement('tbody', null,
      data.map((rowData, i) => {
        const row = { original: rowData };
        return React.createElement('tr', { key: rowData.id ?? i },
          columns.map((col, j) => {
            const ctx = { getValue: () => (col.accessorKey ? rowData[col.accessorKey] : undefined), row };
            const content = col.cell ? col.cell(ctx) : ctx.getValue();
            return React.createElement('td', { key: col.id ?? col.accessorKey ?? j }, content);
          }));
      })));
  }
  function DataTableRowActions({
    row, actions,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  }: { row: any; actions: { label: string; onClick: (row: any) => void }[] }) {
    return React.createElement('div', null,
      actions.map((a, i) => React.createElement('button', { key: i, onClick: () => a.onClick(row) }, a.label)));
  }
  return { ...actual, DataTable, DataTableRowActions };
});

// ── mock @cia/api-client ─────────────────────────────────────────────────
const get = vi.fn();
const post = vi.fn();
vi.mock('@cia/api-client', () => ({
  apiClient: { get: (...a: unknown[]) => get(...a), post: (...a: unknown[]) => post(...a), put: vi.fn(), delete: vi.fn() },
}));

const categories: ClaimReserveCategoryDto[] = [
  { id: 'c1', name: 'Bodily Injury', code: 'BI', createdAt: '2026-01-01T00:00:00Z' },
  { id: 'c2', name: 'Property Damage', code: 'PD', createdAt: '2026-01-01T00:00:00Z' },
];

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return React.createElement(QueryClientProvider, { client }, children);
}

describe('ClaimsConfigPage — Reserve Categories tab', () => {
  beforeEach(() => {
    cleanup();
    vi.clearAllMocks();
    get.mockResolvedValue({ data: { data: categories } });
    post.mockResolvedValue({ data: { data: { id: 'c3', name: 'Legal', code: 'LG', createdAt: '2026-01-01T00:00:00Z' } } });
  });

  it('lists reserve categories from the live endpoint', async () => {
    render(React.createElement(ClaimsConfigPage), { wrapper });
    expect(await screen.findByText('Bodily Injury')).toBeInTheDocument();
    expect(screen.getByText('Property Damage')).toBeInTheDocument();
    expect(get).toHaveBeenCalledWith('/api/v1/setup/claim-reserve-categories');
  });

  it('creates a category via POST { name, code }', async () => {
    const user = userEvent.setup();
    render(React.createElement(ClaimsConfigPage), { wrapper });
    await screen.findByText('Bodily Injury');

    // Only the tab's trigger button exists while the sheet is closed.
    await user.click(screen.getByRole('button', { name: /add category/i }));

    // Submit shares its label with the trigger — scope to the opened sheet.
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByPlaceholderText(/bodily injury/i), 'Legal');
    await user.type(within(dialog).getByPlaceholderText(/^bi$/i), 'LG');
    await user.click(within(dialog).getByRole('button', { name: /^add category$/i }));

    await waitFor(() => expect(post).toHaveBeenCalledWith('/api/v1/setup/claim-reserve-categories', { name: 'Legal', code: 'LG' }));
  });
});
