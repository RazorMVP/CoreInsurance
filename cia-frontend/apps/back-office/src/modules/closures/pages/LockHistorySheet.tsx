import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import {
  Badge,
  Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle,
  Skeleton,
} from '@cia/ui';
import {
  validatedGet,
  PeriodLockDtoSchema,
  type FiscalPeriodDto,
  type PeriodLockDto,
} from '@cia/api-client';

interface LockHistorySheetProps {
  period: FiscalPeriodDto | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

function formatInstant(iso: string | null | undefined) {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' });
}

function lockTypeVariant(t: PeriodLockDto['lockType']): 'pending' | 'rejected' {
  return t === 'HARD' ? 'rejected' : 'pending';
}

export default function LockHistorySheet({ period, open, onOpenChange }: LockHistorySheetProps) {
  const historyQuery = useQuery<PeriodLockDto[]>({
    queryKey: ['closures', 'history', period?.id],
    queryFn: () => validatedGet(
      `/api/v1/finance/period-locks/${period!.id}/history`,
      z.array(PeriodLockDtoSchema),
    ),
    enabled: open && !!period,
  });

  const history = historyQuery.data ?? [];

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="w-full sm:max-w-lg">
        <SheetHeader>
          <SheetTitle>Lock history</SheetTitle>
          <SheetDescription>
            {period
              ? `${period.periodType} · ${period.startDate} → ${period.endDate}`
              : 'No period selected'}
          </SheetDescription>
        </SheetHeader>

        <div className="mt-6 space-y-3">
          {historyQuery.isLoading && (
            <>
              <Skeleton className="h-20 w-full rounded-lg" />
              <Skeleton className="h-20 w-full rounded-lg" />
            </>
          )}
          {historyQuery.isError && (
            <div className="rounded-md border border-destructive/50 bg-destructive/5 px-3 py-2 text-sm text-destructive">
              Failed to load history.
            </div>
          )}
          {!historyQuery.isLoading && !historyQuery.isError && history.length === 0 && (
            <div className="rounded-md border bg-muted/40 px-3 py-6 text-center text-sm text-muted-foreground">
              No lock history yet. This period has been OPEN since creation.
            </div>
          )}

          <ol className="relative space-y-3 border-l border-border pl-4">
            {history.map((row) => {
              const isActive = row.releasedAt == null;
              return (
                <li key={row.id} className="relative">
                  <span
                    className={`absolute -left-[21px] top-1 h-3 w-3 rounded-full border-2 border-background ${
                      isActive ? 'bg-primary' : 'bg-muted-foreground'
                    }`}
                  />
                  <div className="rounded-md border bg-card px-3 py-2.5 text-sm">
                    <div className="flex items-center justify-between gap-2">
                      <Badge variant={lockTypeVariant(row.lockType)}>{row.lockType}</Badge>
                      {isActive && <span className="text-xs font-medium text-primary">ACTIVE</span>}
                    </div>
                    <dl className="mt-2 grid grid-cols-[8rem_1fr] gap-x-2 gap-y-1 text-xs">
                      <dt className="text-muted-foreground">Locked at</dt>
                      <dd className="font-mono">{formatInstant(row.lockedAt)}</dd>
                      <dt className="text-muted-foreground">Locked by</dt>
                      <dd>{row.lockedBy ?? '—'}</dd>
                      {row.graceWindowUntil && (
                        <>
                          <dt className="text-muted-foreground">Grace until</dt>
                          <dd className="font-mono">{formatInstant(row.graceWindowUntil)}</dd>
                        </>
                      )}
                      {row.releasedAt && (
                        <>
                          <dt className="text-muted-foreground">Released at</dt>
                          <dd className="font-mono">{formatInstant(row.releasedAt)}</dd>
                          <dt className="text-muted-foreground">Released by</dt>
                          <dd>{row.releasedBy ?? '—'}</dd>
                          {row.releaseReason && (
                            <>
                              <dt className="text-muted-foreground">Reason</dt>
                              <dd className="italic">{row.releaseReason}</dd>
                            </>
                          )}
                        </>
                      )}
                    </dl>
                  </div>
                </li>
              );
            })}
          </ol>
        </div>
      </SheetContent>
    </Sheet>
  );
}
