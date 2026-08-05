import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from 'lucide-react';
import { Button } from '../button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../select';

export interface ServerPaginationFooterProps {
  /** 0-based page index. */
  page:            number;
  size:            number;
  total:           number;
  onPageChange:    (p: number) => void;
  onSizeChange:    (s: number) => void;
  pageSizeOptions?: number[];
}

/**
 * Presentational pager for server-driven lists. Unlike DataTablePagination it is
 * not bound to a TanStack table — it is driven purely by {page,size,total} props,
 * so any list (incl. raw-<table> pages) can render a consistent footer.
 */
export function ServerPaginationFooter({
  page, size, total, onPageChange, onSizeChange, pageSizeOptions = [10, 20, 50, 100],
}: ServerPaginationFooterProps) {
  const pageCount = Math.max(1, Math.ceil(total / size));
  const from = total === 0 ? 0 : page * size + 1;
  const to   = Math.min(total, (page + 1) * size);
  const canPrev = page > 0;
  const canNext = page < pageCount - 1;

  return (
    <div className="flex items-center justify-between px-1">
      <div className="text-xs text-muted-foreground">
        Showing {from}–{to} of {total.toLocaleString()}
      </div>

      <div className="flex items-center gap-4">
        <div className="flex items-center gap-2">
          <p className="text-xs font-medium text-muted-foreground">Rows per page</p>
          <Select value={`${size}`} onValueChange={(v) => onSizeChange(Number(v))}>
            <SelectTrigger className="h-8 w-16 text-xs"><SelectValue placeholder={size} /></SelectTrigger>
            <SelectContent side="top">
              {pageSizeOptions.map((sz) => (
                <SelectItem key={sz} value={`${sz}`} className="text-xs">{sz}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <p className="text-xs font-medium text-muted-foreground">Page {page + 1} of {pageCount}</p>

        <div className="flex items-center gap-1">
          <Button variant="outline" size="icon" className="h-7 w-7" onClick={() => onPageChange(0)} disabled={!canPrev}><ChevronsLeft className="h-4 w-4" /></Button>
          <Button variant="outline" size="icon" className="h-7 w-7" onClick={() => onPageChange(page - 1)} disabled={!canPrev}><ChevronLeft className="h-4 w-4" /></Button>
          <Button variant="outline" size="icon" className="h-7 w-7" onClick={() => onPageChange(page + 1)} disabled={!canNext}><ChevronRight className="h-4 w-4" /></Button>
          <Button variant="outline" size="icon" className="h-7 w-7" onClick={() => onPageChange(pageCount - 1)} disabled={!canNext}><ChevronsRight className="h-4 w-4" /></Button>
        </div>
      </div>
    </div>
  );
}
