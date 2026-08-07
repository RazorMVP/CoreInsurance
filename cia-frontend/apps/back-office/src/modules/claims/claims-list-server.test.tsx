import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import React from 'react';
import ClaimsListPage from './pages/ClaimsListPage';

// Isolate the page: stub the create/submit/cancel child sheets to no-ops so the
// test only exercises ClaimsListPage's own server-pagination + stats wiring.
vi.mock('./pages/register/RegisterClaimSheet', () => ({ default: () => null }));
vi.mock('./pages/detail/SubmitClaimDialog',    () => ({ default: () => null }));
vi.mock('./pages/detail/CancelClaimDialog',    () => ({ default: () => null }));

// Keep real @cia/ui (StatCard etc. render in jsdom); replace only DataTable so
// rows are deterministic without a real TanStack table. Mirrors FACTab.test.tsx.
vi.mock('@cia/ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@cia/ui')>();
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  function DataTable({ columns, data }: { columns: any[]; data: any[] }) {
    return React.createElement('table', null, React.createElement('tbody', null,
      data.map((rowData, i) => React.createElement('tr', { key: rowData.id ?? i },
        columns.map((col, j) => {
          const ctx = { getValue: () => (col.accessorKey ? rowData[col.accessorKey] : undefined), row: { original: rowData } };
          return React.createElement('td', { key: col.id ?? col.accessorKey ?? j }, col.cell ? col.cell(ctx) : ctx.getValue());
        }))),
    ));
  }
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  function DataTableRowActions() { return null; }
  return { ...actual, DataTable, DataTableRowActions };
});

// ── mock @cia/api-client ─────────────────────────────────────────────────
const validatedList = vi.fn();
const validatedGet  = vi.fn();
vi.mock('@cia/api-client', () => ({
  apiClient:        { get: vi.fn(), post: vi.fn() },
  validatedList:    (...a: unknown[]) => validatedList(...a),
  validatedGet:     (...a: unknown[]) => validatedGet(...a),
  ClaimDtoSchema:      { _tag: 'ClaimDtoSchema' },
  ClaimStatsDtoSchema: { _tag: 'ClaimStatsDtoSchema' },
}));

const claims = [
  { id: 'c1', claimNumber: 'CLM-1', policyNumber: 'POL-1', customerName: 'Acme Ltd', description: 'Fire', reserveAmount: 1000, approvedAmount: 0, status: 'REGISTERED', incidentDate: '2026-01-01' },
  { id: 'c2', claimNumber: 'CLM-2', policyNumber: 'POL-2', customerName: 'Beta Co', description: 'Theft', reserveAmount: 2000, approvedAmount: 500, status: 'APPROVED', incidentDate: '2026-01-02' },
];

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return React.createElement(QueryClientProvider, { client },
    React.createElement(MemoryRouter, null, children));
}

describe('ClaimsListPage — server pagination + stats', () => {
  beforeEach(() => {
    cleanup();
    vi.clearAllMocks();
    validatedList.mockResolvedValue({ data: claims, meta: { total: 2, page: 0, size: 20 } });
    validatedGet.mockResolvedValue({ openCount: 7, totalReserve: 5_000_000, totalApproved: 1_250_000 });
  });

  it('fetches the list via validatedList with pagination params', async () => {
    render(React.createElement(ClaimsListPage), { wrapper });
    expect(await screen.findByText('Acme Ltd')).toBeInTheDocument();
    expect(screen.getByText('Beta Co')).toBeInTheDocument();
    expect(validatedList).toHaveBeenCalledWith(
      '/api/v1/claims',
      expect.anything(),
      expect.objectContaining({ params: expect.objectContaining({ page: 0, size: 20, sort: 'createdAt,desc' }) }),
    );
  });

  it('renders StatCards from the server stats endpoint, not the paged array', async () => {
    render(React.createElement(ClaimsListPage), { wrapper });
    // openCount=7 comes from /claims/stats — NOT the 2 rows on the current page.
    expect(await screen.findByText('7')).toBeInTheDocument();
    expect(screen.getByText('₦5,000,000')).toBeInTheDocument();
    expect(screen.getByText('₦1,250,000')).toBeInTheDocument();
    await waitFor(() => expect(validatedGet).toHaveBeenCalledWith('/api/v1/claims/stats', expect.anything()));
  });
});
