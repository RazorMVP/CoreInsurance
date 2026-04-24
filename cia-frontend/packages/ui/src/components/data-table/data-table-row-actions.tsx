import { type Row } from '@tanstack/react-table';
import { MoreHorizontal } from 'lucide-react';
import { Button } from '../button';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem,
  DropdownMenuSeparator, DropdownMenuTrigger,
} from '../dropdown-menu';

export interface RowAction<TData> {
  label:      string;
  onClick:    (row: Row<TData>) => void;
  separator?: boolean;
  className?: string;
}

interface DataTableRowActionsProps<TData> {
  row:     Row<TData>;
  actions: RowAction<TData>[];
}

export function DataTableRowActions<TData>({ row, actions }: DataTableRowActionsProps<TData>) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" className="h-8 w-8 p-0 opacity-60 hover:opacity-100">
          <MoreHorizontal className="h-4 w-4" />
          <span className="sr-only">Open menu</span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-40">
        {actions.map((action, i) => (
          <span key={i}>
            {action.separator && i > 0 && <DropdownMenuSeparator />}
            <DropdownMenuItem
              onClick={() => action.onClick(row)}
              className={action.className}
            >
              {action.label}
            </DropdownMenuItem>
          </span>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
