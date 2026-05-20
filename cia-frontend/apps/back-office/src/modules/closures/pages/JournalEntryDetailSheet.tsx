import { useQuery } from '@tanstack/react-query';
import {
  Badge,
  Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle,
  Skeleton,
} from '@cia/ui';
import {
  validatedGet,
  JournalEntryDtoSchema,
  type JournalEntryDto,
  type JournalEntryStatus,
} from '@cia/api-client';

interface JournalEntryDetailSheetProps {
  jeId: string | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const STATUS_VARIANT: Record<JournalEntryStatus, 'active' | 'rejected' | 'draft'> = {
  POSTED:   'active',
  REVERSED: 'rejected',
  DRAFT:    'draft',
};

function formatMoney(amount: number, currency: string) {
  if (amount === 0) return '';
  return `${currency} ${amount.toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

export default function JournalEntryDetailSheet({ jeId, open, onOpenChange }: JournalEntryDetailSheetProps) {
  const detailQuery = useQuery<JournalEntryDto>({
    queryKey: ['closures', 'journal-entry', jeId],
    queryFn:  () => validatedGet(`/api/v1/finance/journal-entries/${jeId}`, JournalEntryDtoSchema),
    enabled:  open && !!jeId,
  });
  const je = detailQuery.data;

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="w-full sm:max-w-2xl">
        <SheetHeader>
          <SheetTitle>Journal entry</SheetTitle>
          <SheetDescription>
            {je
              ? `${je.businessDate} · ${je.sourceModule} · ${je.sourceEventType}`
              : detailQuery.isLoading ? 'Loading…' : 'No entry selected'}
          </SheetDescription>
        </SheetHeader>

        {detailQuery.isLoading && (
          <div className="mt-6 space-y-3">
            <Skeleton className="h-20 w-full rounded-md" />
            <Skeleton className="h-48 w-full rounded-md" />
          </div>
        )}

        {detailQuery.isError && (
          <div className="mt-6 rounded-md border border-destructive/50 bg-destructive/5 px-3 py-2 text-sm text-destructive">
            Failed to load journal entry.
          </div>
        )}

        {je && (
          <div className="mt-6 space-y-4">
            <div className="space-y-2.5">
              <div className="flex items-center gap-2">
                <Badge variant={STATUS_VARIANT[je.status]}>{je.status}</Badge>
                {je.reversalOf && (
                  <Badge variant="outline" className="font-mono text-[10px]">
                    Reversal of {je.reversalOf.slice(0, 8)}…
                  </Badge>
                )}
              </div>
              <dl className="grid grid-cols-[10rem_1fr] gap-x-3 gap-y-1.5 text-sm">
                <dt className="text-muted-foreground">Business date</dt>
                <dd className="font-mono">{je.businessDate}</dd>
                <dt className="text-muted-foreground">Posting date</dt>
                <dd className="font-mono">{je.postingDate}</dd>
                <dt className="text-muted-foreground">Period</dt>
                <dd className="font-mono text-xs">{je.periodId.slice(0, 8)}…</dd>
                <dt className="text-muted-foreground">Posted by</dt>
                <dd>{je.postedBy}</dd>
                {je.narrative && (
                  <>
                    <dt className="text-muted-foreground">Narrative</dt>
                    <dd className="italic">{je.narrative}</dd>
                  </>
                )}
              </dl>
            </div>

            <div className="rounded-md border bg-muted/30 px-3 py-2.5">
              <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Idempotency triple</div>
              <dl className="mt-1 grid grid-cols-[7rem_1fr] gap-x-3 gap-y-0.5 text-xs">
                <dt className="text-muted-foreground">Source module</dt>
                <dd className="font-mono">{je.sourceModule}</dd>
                <dt className="text-muted-foreground">Event type</dt>
                <dd className="font-mono">{je.sourceEventType}</dd>
                <dt className="text-muted-foreground">Reference</dt>
                <dd className="font-mono">{je.sourceReference}</dd>
              </dl>
            </div>

            <div>
              <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground mb-1.5">Lines ({je.lines.length})</div>
              <table className="w-full text-sm border-collapse">
                <thead className="text-xs text-muted-foreground border-b">
                  <tr>
                    <th className="text-left font-medium py-1.5 px-1">#</th>
                    <th className="text-left font-medium py-1.5 px-1">Account</th>
                    <th className="text-right font-medium py-1.5 px-1">Debit</th>
                    <th className="text-right font-medium py-1.5 px-1">Credit</th>
                    <th className="text-left font-medium py-1.5 px-1">Class</th>
                  </tr>
                </thead>
                <tbody>
                  {je.lines.map((l) => (
                    <tr key={l.id} className="border-b last:border-0">
                      <td className="py-1.5 px-1 font-mono text-xs text-muted-foreground">{l.lineNo}</td>
                      <td className="py-1.5 px-1">
                        <div className="font-mono text-xs">{l.accountCode}</div>
                        <div className="text-xs text-muted-foreground">{l.accountName}</div>
                      </td>
                      <td className="py-1.5 px-1 text-right font-mono text-xs">{formatMoney(l.debitAmount, l.currencyCode)}</td>
                      <td className="py-1.5 px-1 text-right font-mono text-xs">{formatMoney(l.creditAmount, l.currencyCode)}</td>
                      <td className="py-1.5 px-1 text-left">
                        {l.classOfBusinessId
                          ? <Badge variant="outline" className="font-mono text-[10px]">{l.classOfBusinessId.slice(0, 8)}…</Badge>
                          : <span className="text-muted-foreground">—</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </SheetContent>
    </Sheet>
  );
}
