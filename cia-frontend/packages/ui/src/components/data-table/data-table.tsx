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

interface DataTableProps<TData, TValue> {
  columns:    ColumnDef<TData, TValue>[];
  data:       TData[];
  toolbar?:   Omit<DataTableToolbarProps<TData>, 'table'>;
  className?: string;
}

export function DataTable<TData, TValue>({
  columns,
  data,
  toolbar,
  className,
}: DataTableProps<TData, TValue>) {
  const [sorting,         setSorting]         = React.useState<SortingState>([]);
  const [columnFilters,   setColumnFilters]   = React.useState<ColumnFiltersState>([]);
  const [columnVisibility,setColumnVisibility]= React.useState<VisibilityState>({});
  const [rowSelection,    setRowSelection]    = React.useState({});

  const table = useReactTable({
    data,
    columns,
    state: { sorting, columnFilters, columnVisibility, rowSelection },
    enableRowSelection:      true,
    onRowSelectionChange:    setRowSelection,
    onSortingChange:         setSorting,
    onColumnFiltersChange:   setColumnFilters,
    onColumnVisibilityChange:setColumnVisibility,
    getCoreRowModel:         getCoreRowModel(),
    getFilteredRowModel:     getFilteredRowModel(),
    getPaginationRowModel:   getPaginationRowModel(),
    getSortedRowModel:       getSortedRowModel(),
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

      <DataTablePagination table={table} />
    </div>
  );
}
