import {
  flexRender,
  getCoreRowModel,
  getFilteredRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  useReactTable,
  type ColumnDef,
  type ColumnFiltersState,
  type SortingState,
  type VisibilityState,
} from '@tanstack/react-table';
import * as React from 'react';
import { cn } from '../../lib/utils';
import { DataTablePagination } from './data-table-pagination';
import { DataTableToolbar, type DataTableToolbarProps } from './data-table-toolbar';
import { ServerPaginationFooter, type ServerPaginationFooterProps } from './server-pagination-footer';

interface DataTableProps<TData, TValue> {
  columns:    ColumnDef<TData, TValue>[];
  data:       TData[];
  toolbar?:   Omit<DataTableToolbarProps<TData>, 'table'>;
  className?: string;
  /**
   * Present ⇒ server-driven pagination: the client pagination row model is
   * dropped and ServerPaginationFooter replaces the client pager. Absent ⇒
   * fully client-side, behaviour unchanged.
   *
   * `sort` + `onSortChange` opt into server sort (manualSorting): the header
   * sort controls emit `<accessorKey>,<asc|desc>` instead of sorting the
   * current page client-side. Omit them to keep client sort of the page.
   */
  serverPagination?: ServerPaginationFooterProps & {
    sort?:         string;
    onSortChange?: (sort: string) => void;
  };
}

export function DataTable<TData, TValue>({
  columns,
  data,
  toolbar,
  className,
  serverPagination,
}: DataTableProps<TData, TValue>) {
  const [sorting,         setSorting]         = React.useState<SortingState>([]);
  const [columnFilters,   setColumnFilters]   = React.useState<ColumnFiltersState>([]);
  const [columnVisibility,setColumnVisibility]= React.useState<VisibilityState>({});
  const [rowSelection,    setRowSelection]    = React.useState({});

  const useServerSort = !!serverPagination?.onSortChange;
  const serverSorting: SortingState = React.useMemo(() => {
    if (!serverPagination?.sort) return [];
    const [id, dir] = serverPagination.sort.split(',');
    return id ? [{ id, desc: dir === 'desc' }] : [];
  }, [serverPagination?.sort]);

  const table = useReactTable({
    data,
    columns,
    state: { sorting: useServerSort ? serverSorting : sorting, columnFilters, columnVisibility, rowSelection },
    manualPagination:        !!serverPagination,
    manualSorting:           useServerSort,
    enableRowSelection:      true,
    onRowSelectionChange:    setRowSelection,
    onSortingChange: useServerSort
      ? (updater) => {
          const next = typeof updater === 'function' ? updater(serverSorting) : updater;
          const s = next[0];
          serverPagination!.onSortChange!(s ? `${s.id},${s.desc ? 'desc' : 'asc'}` : '');
        }
      : setSorting,
    onColumnFiltersChange:   setColumnFilters,
    onColumnVisibilityChange:setColumnVisibility,
    getCoreRowModel:         getCoreRowModel(),
    getFilteredRowModel:     getFilteredRowModel(),
    ...(useServerSort ? {} : { getSortedRowModel: getSortedRowModel() }),
    ...(serverPagination ? {} : { getPaginationRowModel: getPaginationRowModel() }),
  });

  return (
    <div className={cn('space-y-3', className)}>
      {toolbar && <DataTableToolbar table={table} {...toolbar} />}

      <div className="rounded-lg border overflow-hidden">
        <table className="w-full caption-bottom text-sm">
          <thead>
            {table.getHeaderGroups().map((headerGroup) => (
              <tr key={headerGroup.id} className="border-b bg-muted/40">
                {headerGroup.headers.map((header) => (
                  <th
                    key={header.id}
                    className="h-10 px-4 text-left align-middle text-xs font-semibold text-muted-foreground [&:has([role=checkbox])]:pr-0"
                  >
                    {header.isPlaceholder
                      ? null
                      : flexRender(header.column.columnDef.header, header.getContext())}
                  </th>
                ))}
              </tr>
            ))}
          </thead>

          <tbody>
            {table.getRowModel().rows?.length ? (
              table.getRowModel().rows.map((row) => (
                <tr
                  key={row.id}
                  data-state={row.getIsSelected() && 'selected'}
                  className="border-b bg-card transition-colors hover:bg-muted/30 data-[state=selected]:bg-teal-50"
                >
                  {row.getVisibleCells().map((cell) => (
                    <td
                      key={cell.id}
                      className="h-12 px-4 align-middle [&:has([role=checkbox])]:pr-0"
                    >
                      {flexRender(cell.column.columnDef.cell, cell.getContext())}
                    </td>
                  ))}
                </tr>
              ))
            ) : (
              <tr>
                <td colSpan={columns.length} className="h-32 text-center text-sm text-muted-foreground">
                  No results.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {serverPagination
        ? <ServerPaginationFooter {...serverPagination} />
        : <DataTablePagination table={table} />}
    </div>
  );
}
