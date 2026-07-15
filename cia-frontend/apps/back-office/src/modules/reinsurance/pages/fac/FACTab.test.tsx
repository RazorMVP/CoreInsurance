import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { z } from 'zod';
import React from 'react';
import FACTab from './FACTab';
import type { FacInwardDto } from '@cia/api-client';

// ── mock @cia/ui (DataTable + DataTableRowActions only) ─────────────────────
// FACTab mounts sibling sheets/dialogs (CreateFACOfferSheet, AddInwardFACSheet,
// InwardFACActionSheet, FACCreditNoteDialog, FACOfferSlipDialog) unconditionally
// (they're always in the tree, just closed) — those unconditionally reference
// many more @cia/ui primitives (Form*, Select*, Sheet*, Dialog*, Separator…),
// so a hand-picked mock object throws "no export defined" the moment any of
// them is referenced in JSX, even while closed. Real Radix Sheet/Dialog don't
// render their content to the DOM while `open=false`, so keeping the real
// components (via importOriginal) and only replacing DataTable +
// DataTableRowActions (which need deterministic row/cell access without a
// real TanStack table + a real Radix DropdownMenu portal) is simpler and safer.
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
const activeFac: FacInwardDto = {
  id: 'fac-in-1',
  facInwardReference: 'FACIN-2026-00001',
  cedingCompanyId: 'cc-1',
  cedingCompanyName: 'Continental Re',
  classOfBusinessId: 'cob-1',
  classOfBusinessName: 'Fire',
  riskDescription: null,
  sumInsured: 50000000,
  ourSharePct: 25,
  acceptedSumInsured: 12500000,
  premiumRate: 2,
  grossPremium: 250000,
  commissionRate: 10,
  commissionAmount: 25000,
  netPremium: 225000,
  currencyCode: 'NGN',
  coverFrom: '2026-01-01',
  coverTo: '2026-12-31',
  status: 'ACTIVE',
  renewedFromId: null,
  guarantyDocumentPath: 'ri-fac-inward/fac-in-1/guaranty.pdf',
  cancelledBy: null,
  cancelledAt: null,
  cancellationReason: null,
  createdAt: '2026-01-01T00:00:00Z',
};

const cancelledFac: FacInwardDto = {
  ...activeFac,
  id: 'fac-in-2',
  facInwardReference: 'FACIN-2026-00002',
  cedingCompanyName: 'West Africa Re',
  status: 'CANCELLED',
  cancelledBy: 'jdoe',
  cancelledAt: '2026-02-01T00:00:00Z',
  cancellationReason: 'Risk lapsed',
};

// allow-mock: Vitest fixture — controls the mocked validatedGet response per URL
const mockValidatedGet = vi.fn((url: string) => {
  if (url === '/api/v1/ri/fac-inwards') return Promise.resolve([activeFac, cancelledFac]);
  return Promise.resolve([]); // outward fac-covers — empty, not under test here
});

vi.mock('@cia/api-client', () => ({
  apiClient:     { get: vi.fn(), post: vi.fn() },
  validatedGet:  (url: string) => mockValidatedGet(url),
  validatedPost: vi.fn(),
  FacCoverDtoSchema:  z.any(),
  FacInwardDtoSchema: z.any(),
}));

// ── wrapper ───────────────────────────────────────────────────────────────
function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return React.createElement(QueryClientProvider, { client }, children);
}

async function renderInwardTab() {
  const user = userEvent.setup();
  render(React.createElement(FACTab), { wrapper });
  await user.click(screen.getByText(/Inward FAC \(\d+\)/));
  await waitFor(() => expect(screen.getByText('FACIN-2026-00001')).toBeInTheDocument());
  return user;
}

describe('FACTab — Inward FAC', () => {
  beforeEach(() => {
    cleanup();
    vi.clearAllMocks();
    mockValidatedGet.mockImplementation((url: string) => {
      if (url === '/api/v1/ri/fac-inwards') return Promise.resolve([activeFac, cancelledFac]);
      return Promise.resolve([]);
    });
  });

  it('renders inward rows from the mocked query — reference, ceding company, status badge', async () => {
    await renderInwardTab();

    expect(screen.getByText('FACIN-2026-00001')).toBeInTheDocument();
    expect(screen.getByText('Continental Re')).toBeInTheDocument();
    expect(screen.getByText('Active')).toBeInTheDocument();

    expect(screen.getByText('FACIN-2026-00002')).toBeInTheDocument();
    expect(screen.getByText('West Africa Re')).toBeInTheDocument();
    expect(screen.getByText('Cancelled')).toBeInTheDocument();
  });

  it('exposes Renew/Extend/Cancel on an ACTIVE row, and no Cancel on a CANCELLED row', async () => {
    await renderInwardTab();

    const rows = screen.getAllByRole('row');
    expect(rows).toHaveLength(2);

    const activeRow = within(rows[0]);
    expect(activeRow.getByText('Renew')).toBeInTheDocument();
    expect(activeRow.getByText('Extend period')).toBeInTheDocument();
    expect(activeRow.getByText('Cancel')).toBeInTheDocument();

    const cancelledRow = within(rows[1]);
    expect(cancelledRow.queryByText('Renew')).not.toBeInTheDocument();
    expect(cancelledRow.queryByText('Extend period')).not.toBeInTheDocument();
    expect(cancelledRow.queryByText('Cancel')).not.toBeInTheDocument();
  });

  it('shows the "Add Inward FAC" action button', async () => {
    await renderInwardTab();
    expect(screen.getByText('Add Inward FAC')).toBeInTheDocument();
  });
});
