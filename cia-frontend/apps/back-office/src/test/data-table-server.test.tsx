import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { DataTable } from '@cia/ui';
import type { ColumnDef } from '@tanstack/react-table';

type Row = { id: string; name: string };
const columns: ColumnDef<Row>[] = [
  { accessorKey: 'name', header: 'Name', cell: ({ row }) => row.original.name },
];
const data: Row[] = [{ id: '1', name: 'Alpha' }, { id: '2', name: 'Beta' }];

describe('DataTable server mode', () => {
  beforeEach(() => cleanup()); // vitest globals:false → no auto-cleanup between tests

  it('renders the server footer + total, and emits onPageChange on next', () => {
    const onPageChange = vi.fn();
    const { container } = render(
      <DataTable
        columns={columns}
        data={data}
        serverPagination={{ page: 0, size: 20, total: 57, onPageChange, onSizeChange: vi.fn() }}
      />,
    );
    // Footer shows the page window (page*size), not the fixture row count:
    // page 0, size 20, total 57 → "Showing 1–20 of 57", "Page 1 of 3".
    // Text is split across nodes ({from}–{to}), so assert on container text.
    expect(container.textContent).toContain('Showing 1–20 of 57');
    expect(container.textContent).toContain('Page 1 of 3');
    fireEvent.click(screen.getByRole('button', { name: /next page/i }));
    expect(onPageChange).toHaveBeenCalledWith(1);
  });

  it('without serverPagination, renders the client pager (parity)', () => {
    const { container } = render(<DataTable columns={columns} data={data} />);
    expect(container.textContent).toContain('row(s)');       // client DataTablePagination
    expect(container.textContent).not.toContain('Showing 1–'); // no server footer
  });
});
