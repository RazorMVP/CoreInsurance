import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import React from 'react';
import VehicleRegistryPage from './VehicleRegistryPage';
import type { VehicleMakeDto } from '@cia/api-client';

// ── mock @cia/ui (DataTable + DataTableRowActions only) ─────────────────────
// Keep the real Radix Tabs/Sheet/Form (they render fine in jsdom; closed
// sheets don't mount their content), and replace only DataTable +
// DataTableRowActions so rows/actions are deterministic without a real
// TanStack table + Radix DropdownMenu portal. Mirrors FACTab.test.tsx.
vi.mock('@cia/ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@cia/ui')>();

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  function DataTable({ columns, data }: { columns: any[]; data: any[] }) {
    return React.createElement(
      'table',
      null,
      React.createElement(
        'tbody',
        null,
        data.map((rowData, i) => {
          const row = { original: rowData };
          return React.createElement(
            'tr',
            { key: rowData.id ?? i },
            columns.map((col, j) => {
              const ctx = {
                getValue: () => (col.accessorKey ? rowData[col.accessorKey] : undefined),
                row,
              };
              const content = col.cell ? col.cell(ctx) : ctx.getValue();
              return React.createElement('td', { key: col.id ?? col.accessorKey ?? j }, content);
            }),
          );
        }),
      ),
    );
  }

  function DataTableRowActions({
    row,
    actions,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  }: { row: any; actions: { label: string; onClick: (row: any) => void }[] }) {
    return React.createElement(
      'div',
      null,
      actions.map((a, i) =>
        React.createElement('button', { key: i, onClick: () => a.onClick(row) }, a.label),
      ),
    );
  }

  return { ...actual, DataTable, DataTableRowActions };
});

// ── mock @cia/api-client ─────────────────────────────────────────────────
const get = vi.fn();
const post = vi.fn();
vi.mock('@cia/api-client', () => ({
  apiClient: {
    get:    (...a: unknown[]) => get(...a),
    post:   (...a: unknown[]) => post(...a),
    put:    vi.fn(),
    delete: vi.fn(),
  },
}));

const makes: VehicleMakeDto[] = [
  { id: 'm1', name: 'Toyota', createdAt: '2026-01-01T00:00:00Z' },
  { id: 'm2', name: 'Honda',  createdAt: '2026-01-01T00:00:00Z' },
];

// ── wrapper ───────────────────────────────────────────────────────────────
function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return React.createElement(QueryClientProvider, { client }, children);
}

describe('VehicleRegistryPage — Makes tab', () => {
  beforeEach(() => {
    cleanup();
    vi.clearAllMocks();
    get.mockResolvedValue({ data: { data: makes } });
    post.mockResolvedValue({ data: { data: { id: 'm3', name: 'Ford', createdAt: '2026-01-01T00:00:00Z' } } });
  });

  it('lists makes from the live endpoint', async () => {
    render(React.createElement(VehicleRegistryPage), { wrapper });

    expect(await screen.findByText('Toyota')).toBeInTheDocument();
    expect(screen.getByText('Honda')).toBeInTheDocument();
    expect(get).toHaveBeenCalledWith('/api/v1/setup/vehicle-makes');
  });

  it('creates a make via POST { name }', async () => {
    const user = userEvent.setup();
    render(React.createElement(VehicleRegistryPage), { wrapper });
    await screen.findByText('Toyota');

    // Only the tab's trigger button exists while the sheet is closed.
    await user.click(screen.getByRole('button', { name: /add make/i }));

    // The submit button shares its label with the trigger — scope to the sheet.
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByPlaceholderText(/toyota/i), 'Ford');
    await user.click(within(dialog).getByRole('button', { name: /^add make$/i }));

    await waitFor(() => expect(post).toHaveBeenCalledWith('/api/v1/setup/vehicle-makes', { name: 'Ford' }));
  });
});
