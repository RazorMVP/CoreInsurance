import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  Badge,
  Button,
  Checkbox,
  Input,
  Label,
  PageHeader, PageSection,
  Skeleton,
  Switch,
  useToast,
} from '@cia/ui';
import {
  validatedGet, validatedPost,
  StartBackfillResponseDtoSchema,
  BackfillStatusResponseDtoSchema,
  type BackfillEventType,
  type BackfillStatusResponseDto,
  type BackfillEventTypeCountDto,
  type BackfillResultStatus,
} from '@cia/api-client';

const EVENT_TYPES: BackfillEventType[] = [
  'POLICY_APPROVED',
  'CLAIM_APPROVED',
  'CLAIM_SETTLED',
  'CLAIM_EXPENSE_APPROVED',
  'ENDORSEMENT_APPROVED',
  'FAC_PREMIUM_CEDED',
];

const RESULT_VARIANT: Record<BackfillResultStatus, 'active' | 'pending' | 'rejected'> = {
  SUCCESS:         'active',
  PARTIAL_FAILURE: 'pending',
  REFUSED:         'rejected',
};

function execStatusVariant(s: string): 'active' | 'pending' | 'rejected' | 'draft' {
  if (s === 'COMPLETED') return 'active';
  if (s === 'RUNNING')   return 'pending';
  if (s === 'NOT_FOUND') return 'draft';
  return 'rejected'; // FAILED / CANCELED / TERMINATED / TIMED_OUT
}

const TRACKED_KEY = 'cia.closures.backfill.tracked';

interface TrackedRun {
  workflowId: string;
  dryRun:     boolean;
  startedAt:  string;  // ISO
}

function loadTracked(): TrackedRun[] {
  try {
    const raw = localStorage.getItem(TRACKED_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function saveTracked(runs: TrackedRun[]) {
  localStorage.setItem(TRACKED_KEY, JSON.stringify(runs));
}

function formatInstant(iso: string | null | undefined) {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' });
}

function today(offsetDays = 0): string {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return d.toISOString().slice(0, 10);
}

// ── Start form ────────────────────────────────────────────────────────────

interface StartFormProps {
  onStarted: (run: TrackedRun) => void;
}

function StartBackfillForm({ onStarted }: StartFormProps) {
  const [fromDate,   setFromDate]   = useState(today(-90));
  const [toDate,     setToDate]     = useState(today());
  const [eventTypes, setEventTypes] = useState<BackfillEventType[]>([...EVENT_TYPES]);
  const [dryRun,     setDryRun]     = useState(true);
  const { toast } = useToast();

  const startMutation = useMutation({
    mutationFn: () => validatedPost(
      '/api/v1/admin/finance/backfill-journal-entries',
      {
        fromDate, toDate,
        eventTypes: eventTypes.length === EVENT_TYPES.length ? null : eventTypes,
        dryRun,
      },
      StartBackfillResponseDtoSchema,
    ),
    onSuccess: (resp) => {
      toast({
        title: dryRun ? 'Dry-run backfill started' : 'Backfill workflow started',
        description: `Workflow ${resp.workflowId.slice(0, 12)}… is now running. Poll status below.`,
      });
      onStarted({ workflowId: resp.workflowId, dryRun: resp.dryRun, startedAt: resp.startedAt });
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : 'Request failed';
      toast({ title: 'Backfill failed to start', description: msg, variant: 'destructive' });
    },
  });

  function toggleEventType(t: BackfillEventType) {
    setEventTypes((prev) => prev.includes(t) ? prev.filter((x) => x !== t) : [...prev, t]);
  }

  function selectAll() { setEventTypes([...EVENT_TYPES]); }
  function selectNone() { setEventTypes([]); }

  const disabled = startMutation.isPending || !fromDate || !toDate || eventTypes.length === 0;

  return (
    <div className="rounded-lg border bg-card p-5 space-y-4">
      <div className="space-y-1">
        <h3 className="text-base font-semibold">Start backfill</h3>
        <p className="text-xs text-muted-foreground">
          Walks source tables (policies / claims / claim_expenses / endorsements / ri_fac_covers) and posts the JEs <code className="font-mono">SubledgerPostingService</code> would have written. Idempotent via the (source_module, source_event_type, source_reference) gateway triple. Pre-flight check refuses the run if any target period is HARD_CLOSED or past SOFT grace.
        </p>
      </div>

      <div className="grid grid-cols-2 gap-3 max-w-md">
        <div className="space-y-1">
          <Label htmlFor="bf-from">From date</Label>
          <Input id="bf-from" type="date" value={fromDate} onChange={(e) => setFromDate(e.target.value)} />
        </div>
        <div className="space-y-1">
          <Label htmlFor="bf-to">To date</Label>
          <Input id="bf-to" type="date" value={toDate} onChange={(e) => setToDate(e.target.value)} />
        </div>
      </div>

      <div className="space-y-1.5">
        <div className="flex items-center justify-between">
          <Label>Event types ({eventTypes.length} / 6)</Label>
          <div className="flex gap-1.5">
            <Button type="button" size="sm" variant="ghost" onClick={selectAll}>All</Button>
            <Button type="button" size="sm" variant="ghost" onClick={selectNone}>None</Button>
          </div>
        </div>
        <div className="grid grid-cols-2 gap-1.5 sm:grid-cols-3">
          {EVENT_TYPES.map((t) => (
            <label key={t} className="flex items-center gap-2 rounded-md border bg-background px-2.5 py-1.5 text-xs cursor-pointer hover:bg-secondary/40">
              <Checkbox checked={eventTypes.includes(t)} onCheckedChange={() => toggleEventType(t)} />
              <span className="font-mono">{t}</span>
            </label>
          ))}
        </div>
      </div>

      <div className="flex items-center justify-between rounded-md border bg-muted/40 px-3 py-2.5">
        <div>
          <Label htmlFor="bf-dryrun" className="cursor-pointer">Dry run</Label>
          <p className="text-xs text-muted-foreground">
            When on, the workflow walks the same sources and counts what it would post, but writes no JEs. Recommended before any real run.
          </p>
        </div>
        <Switch id="bf-dryrun" checked={dryRun} onCheckedChange={setDryRun} />
      </div>

      <Button
        onClick={() => startMutation.mutate()}
        disabled={disabled}
        variant={dryRun ? 'default' : 'destructive'}
      >
        {startMutation.isPending
          ? 'Starting…'
          : dryRun ? 'Start dry run' : 'Start backfill (writes JEs)'}
      </Button>
    </div>
  );
}

// ── Tracked workflow row ──────────────────────────────────────────────────

interface TrackedRunCardProps {
  run: TrackedRun;
  onForget: () => void;
}

function TrackedRunCard({ run, onForget }: TrackedRunCardProps) {
  const statusQuery = useQuery<BackfillStatusResponseDto>({
    queryKey: ['closures', 'backfill', run.workflowId],
    queryFn:  () => validatedGet(
      `/api/v1/admin/finance/backfill-journal-entries/${encodeURIComponent(run.workflowId)}`,
      BackfillStatusResponseDtoSchema,
    ),
    refetchInterval: (q) => {
      const data = q.state.data as BackfillStatusResponseDto | undefined;
      return data?.executionStatus === 'RUNNING' ? 3000 : false;
    },
  });

  const data   = statusQuery.data;
  const result = data?.result;

  return (
    <div className="rounded-md border bg-card px-3 py-3 space-y-2.5">
      <div className="flex items-start justify-between gap-3">
        <div className="space-y-0.5">
          <div className="font-mono text-xs">{run.workflowId}</div>
          <div className="text-xs text-muted-foreground">
            Started {formatInstant(run.startedAt)}
            {run.dryRun && <Badge variant="outline" className="ml-2 text-[10px]">DRY RUN</Badge>}
          </div>
        </div>
        <div className="flex items-center gap-1.5">
          {data && (
            <Badge variant={execStatusVariant(data.executionStatus)}>{data.executionStatus}</Badge>
          )}
          {result && (
            <Badge variant={RESULT_VARIANT[result.status]}>{result.status}</Badge>
          )}
          <Button size="sm" variant="ghost" onClick={onForget}>Forget</Button>
        </div>
      </div>

      {statusQuery.isLoading && <Skeleton className="h-16 w-full rounded-md" />}

      {result && (
        <>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 text-sm">
            <div className="rounded-md bg-muted/40 px-2 py-1.5">
              <div className="text-xs text-muted-foreground">Attempted</div>
              <div className="font-mono">{result.totalAttempted.toLocaleString()}</div>
            </div>
            <div className="rounded-md bg-muted/40 px-2 py-1.5">
              <div className="text-xs text-muted-foreground">Posted</div>
              <div className="font-mono text-primary">{result.totalPosted.toLocaleString()}</div>
            </div>
            <div className="rounded-md bg-muted/40 px-2 py-1.5">
              <div className="text-xs text-muted-foreground">Already exists</div>
              <div className="font-mono">{result.totalAlreadyExists.toLocaleString()}</div>
            </div>
            <div className="rounded-md bg-muted/40 px-2 py-1.5">
              <div className="text-xs text-muted-foreground">Failed</div>
              <div className={`font-mono ${result.totalFailed > 0 ? 'text-destructive' : ''}`}>{result.totalFailed.toLocaleString()}</div>
            </div>
          </div>

          {result.refusalReason && (
            <div className="rounded-md border border-destructive/50 bg-destructive/5 px-3 py-2 text-xs text-destructive">
              <span className="font-semibold">Refused:</span> {result.refusalReason}
            </div>
          )}

          {result.byEventType.length > 0 && (
            <details className="text-xs">
              <summary className="cursor-pointer text-muted-foreground hover:text-foreground">Per-event-type breakdown</summary>
              <table className="mt-2 w-full text-xs">
                <thead className="text-muted-foreground border-b">
                  <tr>
                    <th className="text-left font-medium py-1 px-1">Event type</th>
                    <th className="text-right font-medium py-1 px-1">Attempted</th>
                    <th className="text-right font-medium py-1 px-1">Posted</th>
                    <th className="text-right font-medium py-1 px-1">Dup</th>
                    <th className="text-right font-medium py-1 px-1">Failed</th>
                  </tr>
                </thead>
                <tbody>
                  {result.byEventType.map((c: BackfillEventTypeCountDto) => (
                    <tr key={c.eventType} className="border-b last:border-0">
                      <td className="py-1 px-1 font-mono">{c.eventType}</td>
                      <td className="py-1 px-1 text-right font-mono">{c.attempted}</td>
                      <td className="py-1 px-1 text-right font-mono">{c.posted}</td>
                      <td className="py-1 px-1 text-right font-mono">{c.alreadyExists}</td>
                      <td className={`py-1 px-1 text-right font-mono ${c.failed > 0 ? 'text-destructive' : ''}`}>{c.failed}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </details>
          )}

          <div className="text-xs text-muted-foreground">
            Completed {formatInstant(result.completedAt)}
          </div>
        </>
      )}
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────

export default function BackfillAdminPage() {
  const [tracked, setTracked] = useState<TrackedRun[]>([]);

  // Hydrate from localStorage on mount; persist on change.
  useEffect(() => { setTracked(loadTracked()); }, []);
  useEffect(() => { saveTracked(tracked); }, [tracked]);

  function addRun(run: TrackedRun) {
    setTracked((prev) => [run, ...prev.filter((r) => r.workflowId !== run.workflowId)].slice(0, 20));
  }
  function forgetRun(workflowId: string) {
    setTracked((prev) => prev.filter((r) => r.workflowId !== workflowId));
  }

  const sortedTracked = useMemo(
    () => [...tracked].sort((a, b) => b.startedAt.localeCompare(a.startedAt)),
    [tracked],
  );

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="GL Backfill"
        description="Slice 1.8 retroactive journal-entry backfill. One-time mechanism for moving from 'no GL history' to 'all GL history reconstructed'. PLATFORM_ADMIN gated — intentionally out of reach of normal finance day-to-day work. Workflow IDs are tracked in your browser's localStorage so you can return and check status later."
      />

      <StartBackfillForm onStarted={addRun} />

      <PageSection>
        <h3 className="text-sm font-semibold mb-3">Tracked workflows ({sortedTracked.length})</h3>
        {sortedTracked.length === 0 ? (
          <div className="rounded-md border bg-muted/40 px-4 py-8 text-center text-sm text-muted-foreground">
            No tracked workflows. Start one above; it will appear here and poll for status automatically.
          </div>
        ) : (
          <div className="space-y-2.5">
            {sortedTracked.map((r) => (
              <TrackedRunCard
                key={r.workflowId}
                run={r}
                onForget={() => forgetRun(r.workflowId)}
              />
            ))}
          </div>
        )}
      </PageSection>
    </div>
  );
}
