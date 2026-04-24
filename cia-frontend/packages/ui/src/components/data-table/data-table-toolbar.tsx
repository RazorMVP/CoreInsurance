import { type Table } from '@tanstack/react-table';
import { X } from 'lucide-react';
import { Button } from '../button';
import { Input } from '../input';

export interface DataTableToolbarProps<TData> {
  table:           Table<TData>;
  searchColumn?:   string;
  searchPlaceholder?: string;
  children?:       React.ReactNode;
}

export function DataTableToolbar<TData>({
  table,
  searchColumn,
  searchPlaceholder = 'Search…',
  children,
}: DataTableToolbarProps<TData>) {
  const isFiltered = table.getState().columnFilters.length > 0;

  return (
    <div className="flex items-center justify-between gap-2">
      <div className="flex flex-1 items-center gap-2">
        {searchColumn && (
          <Input
            placeholder={searchPlaceholder}
            value={(table.getColumn(searchColumn)?.getFilterValue() as string) ?? ''}
            onChange={(e) => table.getColumn(searchColumn)?.setFilterValue(e.target.value)}
            className="h-8 w-full max-w-xs"
          />
        )}
        {children}
        {isFiltered && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => table.resetColumnFilters()}
            className="h-8 px-2 text-xs"
          >
            Reset
            <X className="ml-1 h-3.5 w-3.5" />
          </Button>
        )}
      </div>
    </div>
  );
}
