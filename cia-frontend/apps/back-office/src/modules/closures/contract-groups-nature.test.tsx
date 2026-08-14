import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, within, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { z } from 'zod';
import React from 'react';
import ContractGroupsPage from './pages/ContractGroupsPage';
import type { ContractGroupSummaryDto } from '@cia/api-client';

// Real Radix Select can't be driven reliably in jsdom (no hasPointerCapture /
// scrollIntoView), so — mirroring the DataTable-replacement idiom used by
// claims-list-server.test.tsx / FACTab.test.tsx — keep every other @cia/ui
// primitive real (importOriginal) and swap only the Select compound
// component for a plain native <select> built by walking the JSX children
// for SelectItem descendants. This makes filter interaction a single
// userEvent.selectOptions() call instead of a Radix portal dance.
vi.mock('@cia/ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@cia/ui')>();

  function SelectItemMock() {
    return null; // never actually rendered — only introspected via props
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  function collectItems(node: React.ReactNode, acc: { value: string; label: React.ReactNode }[]) {
    React.Children.forEach(node, (child) => {
      if (!React.isValidElement(child)) return;
      const props = child.props as Record<string, unknown>;
      if (child.type === SelectItemMock) {
        acc.push({ value: props.value as string, label: props.children as React.ReactNode });
      } else if (props.children) {
        collectItems(props.children as React.ReactNode, acc);
      }
    });
    return acc;
  }

  function SelectMock({
    value,
    onValueChange,
    disabled,
    children,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  }: any) {
    const items = collectItems(children, []);
    return React.createElement(
      'select',
      {
        value: value ?? '',
        disabled,
        onChange: (e: React.ChangeEvent<HTMLSelectElement>) => onValueChange?.(e.target.value),
      },
      items.map((it) => React.createElement('option', { key: it.value, value: it.value }, it.label)),
    );
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  function PassthroughMock({ children }: any) {
    return React.createElement(React.Fragment, null, children);
  }

  return {
    ...actual,
    Select: SelectMock,
    SelectTrigger: PassthroughMock,
    SelectContent: PassthroughMock,
    SelectValue: () => null,
    SelectItem: SelectItemMock,
  };
});

// ── mock @cia/api-client ─────────────────────────────────────────────────
const groups: ContractGroupSummaryDto[] = [
  {
    id: 'g1', portfolioId: 'p1', portfolioCode: 'PORT-D', portfolioName: 'Direct Fire',
    contractNature: 'DIRECT', cohortYear: 2026, onerousness: 'NOT_ONEROUS', status: 'OPEN',
    createdAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 'g2', portfolioId: 'p2', portfolioCode: 'PORT-FI', portfolioName: 'FAC Inward Marine',
    contractNature: 'FAC_INWARD', cohortYear: 2026, onerousness: 'NOT_ONEROUS', status: 'OPEN',
    createdAt: '2026-01-02T00:00:00Z',
  },
  {
    id: 'g3', portfolioId: 'p3', portfolioCode: 'PORT-FO', portfolioName: 'FAC Outward Aviation',
    contractNature: 'FAC_OUTWARD', cohortYear: 2026, onerousness: 'ONEROUS', status: 'OPEN',
    createdAt: '2026-01-03T00:00:00Z',
  },
];

// allow-mock: Vitest fixture — controls the mocked validatedGet response per URL
const mockValidatedGet = vi.fn((url: string) => {
  if (url.startsWith('/api/v1/finance/paa/contract-groups')) return Promise.resolve(groups);
  if (url.startsWith('/api/v1/finance/paa/portfolios')) return Promise.resolve([]);
  return Promise.resolve([]);
});

vi.mock('@cia/api-client', () => ({
  apiClient:                     { get: vi.fn(), post: vi.fn() },
  validatedGet:                  (url: string) => mockValidatedGet(url),
  ContractGroupSummaryDtoSchema: z.any(),
  PortfolioSummaryDtoSchema:     z.any(),
}));

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return React.createElement(QueryClientProvider, { client }, children);
}

describe('ContractGroupsPage — contract_nature column + filter', () => {
  beforeEach(() => {
    cleanup();
    vi.clearAllMocks();
    mockValidatedGet.mockImplementation((url: string) => {
      if (url.startsWith('/api/v1/finance/paa/contract-groups')) return Promise.resolve(groups);
      if (url.startsWith('/api/v1/finance/paa/portfolios')) return Promise.resolve([]);
      return Promise.resolve([]);
    });
  });

  it('renders a Nature column with all three contract-nature values', async () => {
    render(React.createElement(ContractGroupsPage), { wrapper });

    const table = await screen.findByRole('table');
    expect(within(table).getByRole('columnheader', { name: 'Nature' })).toBeInTheDocument();

    expect(within(table).getByText('Direct')).toBeInTheDocument();
    expect(within(table).getByText('FAC Inward')).toBeInTheDocument();
    expect(within(table).getByText('FAC Outward')).toBeInTheDocument();
    expect(within(table).getAllByRole('row')).toHaveLength(4); // header + 3 groups
  });

  it('narrows to one row when filtered by FAC_OUTWARD', async () => {
    const user = userEvent.setup();
    render(React.createElement(ContractGroupsPage), { wrapper });

    const table = await screen.findByRole('table');
    expect(within(table).getAllByRole('row')).toHaveLength(4);

    const natureLabel = screen.getByText('Nature', { selector: 'label' });
    const natureSelect = natureLabel.nextElementSibling as HTMLSelectElement;
    await user.selectOptions(natureSelect, 'FAC_OUTWARD');

    expect(within(table).getAllByRole('row')).toHaveLength(2); // header + 1 group
    expect(within(table).getByText('FAC Outward')).toBeInTheDocument();
    expect(within(table).queryByText('Direct')).not.toBeInTheDocument();
    expect(within(table).queryByText('FAC Inward')).not.toBeInTheDocument();
  });
});
