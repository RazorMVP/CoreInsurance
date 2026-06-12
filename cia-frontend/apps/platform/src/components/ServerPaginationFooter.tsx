import { Button } from '@cia/ui';

interface Props {
  page: number;          // zero-based
  size: number;
  total: number;
  onPageChange: (page: number) => void;
  noun: string;          // e.g. "tenants"
}

export default function ServerPaginationFooter({ page, size, total, onPageChange, noun }: Props) {
  const first = total === 0 ? 0 : page * size + 1;
  const last = Math.min(total, (page + 1) * size);
  const isLast = (page + 1) * size >= total;
  return (
    <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
      <span>Showing {first}–{last} of {total} {noun}</span>
      <div className="flex gap-2">
        <Button variant="outline" size="sm" disabled={page === 0} onClick={() => onPageChange(Math.max(0, page - 1))}>
          Previous
        </Button>
        <Button variant="outline" size="sm" disabled={isLast} onClick={() => onPageChange(page + 1)}>
          Next
        </Button>
      </div>
    </div>
  );
}
