import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import {
  Badge,
  Button,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  Input,
  Label,
  PageHeader, PageSection,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Skeleton, StatCard,
  Textarea,
  useToast,
} from '@cia/ui';
import {
  validatedGet, validatedPost,
  FiscalYearDtoSchema, FiscalPeriodDtoSchema,
  NaicomSubmissionDtoSchema,
  type FiscalYearDto, type FiscalPeriodDto,
  type NaicomSubmissionDto,
  type NaicomSubmissionState,
  type NaicomSubmissionType,
} from '@cia/api-client';
import NaicomSubmissionDetailSheet from './NaicomSubmissionDetailSheet';

const STATE_VARIANT: Record<NaicomSubmissionState, 'active' | 'pending' | 'draft' | 'rejected'> = {
  DRAFT:        'draft',
  SUBMITTED:    'pending',
  ACKNOWLEDGED: 'active',
  ARCHIVED:     'draft',
  RETRACTED:    'rejected',
};

const SUBMISSION_TYPES: { code: string; value: NaicomSubmissionType; label: string }[] = [
  { code: 'N01', value: 'ANNUAL_REVENUE_ACCOUNT', label: 'Annual Revenue Account' },
  { code: 'N02', value: 'BALANCE_SHEET',          label: 'Balance Sheet' },
  { code: 'N03', value: 'PRUDENTIAL_RETURN',      label: 'Prudential Return' },
  { code: 'N04', value: 'RI_QUARTERLY_RETURN',    label: 'RI Quarterly Return' },
  { code: 'N05', value: 'PREMIUM_BORDEREAUX',     label: 'Premium Bordereaux' },
  { code: 'N06', value: 'CLAIMS_BORDEREAUX',      label: 'Claims Bordereaux' },
  { code: 'N07', value: 'NIID_STATUS_SNAPSHOT',   label: 'NIID Status Snapshot' },
  { code: 'N08', value: 'INVESTMENT_STATEMENT',   label: 'Investment Statement' },
];

const SUBMISSION_TYPE_CODE: Record<NaicomSubmissionType, string> = Object.fromEntries(
  SUBMISSION_TYPES.map((t) => [t.value, t.code]),
) as Record<NaicomSubmissionType, string>;

const SUBMISSION_TYPE_LABEL: Record<NaicomSubmissionType, string> = Object.fromEntries(
  SUBMISSION_TYPES.map((t) => [t.value, t.label]),
) as Record<NaicomSubmissionType, string>;

function formatDate(iso: string) {
  return new Date(iso).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
}

function formatPeriodLabel(p: FiscalPeriodDto) {
  return new Date(p.startDate).toLocaleDateString('en-GB', { month: 'long', year: 'numeric' });
}

export default function NaicomSubmissionsPage() {
  const [selectedFyId,     setSelectedFyId]     = useState<string | null>(null);
  const [selectedPeriodId, setSelectedPeriodId] = useState<string | null>(null);
  const [stateFilter,      setStateFilter]      = useState<NaicomSubmissionState | 'ALL'>('ALL');
  const [detailId,         setDetailId]         = useState<string | null>(null);
  const [generateOpen,     setGenerateOpen]     = useState(false);

  // FY + Period selection
  const yearsQuery = useQuery<FiscalYearDto[]>({
    queryKey: ['closures', 'fiscal-years'],
    queryFn: () => validatedGet('/api/v1/finance/fiscal-years', z.array(FiscalYearDtoSchema)),
  });
  const years = yearsQuery.data ?? [];
  const activeFy = useMemo(() => years.find((y) => y.status === 'ACTIVE') ?? years[0] ?? null, [years]);
  const effectiveFyId = selectedFyId ?? activeFy?.id ?? null;

  const periodsQuery = useQuery<FiscalPeriodDto[]>({
    queryKey: ['closures', 'periods', effectiveFyId],
    queryFn: () => validatedGet(
      `/api/v1/finance/fiscal-years/${effectiveFyId}/periods`,
      z.array(FiscalPeriodDtoSchema),
    ),
    enabled: !!effectiveFyId,
  });
  const monthPeriods = useMemo(
    () => (periodsQuery.data ?? []).filter((p) => p.periodType === 'MONTH'),
    [periodsQuery.data],
  );

  // Submissions list — backend requires at least one of (periodId, state)
  const canList = selectedPeriodId !== null || stateFilter !== 'ALL';
  const queryString = useMemo(() => {
    const p = new URLSearchParams();
    if (selectedPeriodId)      p.set('periodId', selectedPeriodId);
    if (stateFilter !== 'ALL') p.set('state', stateFilter);
    return p.toString();
  }, [selectedPeriodId, stateFilter]);

  const submissionsQuery = useQuery<NaicomSubmissionDto[]>({
    queryKey: ['closures', 'naicom-submissions', queryString],
    queryFn:  () => validatedGet(
      `/api/v1/finance/naicom/submissions?${queryString}`,
      z.array(NaicomSubmissionDtoSchema),
    ),
    enabled: canList,
  });
  const submissions = submissionsQuery.data ?? [];

  const counts = useMemo(() => {
    return submissions.reduce(
      (acc, s) => {
        acc.total += 1;
        if (s.state === 'DRAFT')        acc.draft += 1;
        if (s.state === 'SUBMITTED')    acc.submitted += 1;
        if (s.state === 'ACKNOWLEDGED') acc.acknowledged += 1;
        return acc;
      },
      { total: 0, draft: 0, submitted: 0, acknowledged: 0 },
    );
  }, [submissions]);

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="NAICOM Submissions"
        description="Monthly recap submissions to NAICOM (N01–N08). Period must be HARD_CLOSED before generation. State machine: DRAFT → SUBMITTED → ACKNOWLEDGED → ARCHIVED, with a RETRACTED branch from SUBMITTED. All transitions append-only via naicom_submission_event (Type-2 SCD audit trail)."
      />

      {/* ── Filter row + Generate CTA ───────────────────────────── */}
      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1">
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Fiscal year</label>
          <Select
            value={effectiveFyId ?? undefined}
            onValueChange={(v) => { setSelectedFyId(v); setSelectedPeriodId(null); }}
          >
            <SelectTrigger className="w-56"><SelectValue placeholder="Choose fiscal year…" /></SelectTrigger>
            <SelectContent>
              {years.map((y) => (
                <SelectItem key={y.id} value={y.id}>
                  {y.name} {y.status === 'ACTIVE' && <span className="ml-1 text-primary">●</span>}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-1">
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Period (MONTH)</label>
          <Select
            value={selectedPeriodId ?? undefined}
            onValueChange={(v) => setSelectedPeriodId(v)}
            disabled={monthPeriods.length === 0}
          >
            <SelectTrigger className="w-64"><SelectValue placeholder="Any period…" /></SelectTrigger>
            <SelectContent>
              {monthPeriods.map((p) => (
                <SelectItem key={p.id} value={p.id}>{formatPeriodLabel(p)}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-1">
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">State</label>
          <Select value={stateFilter} onValueChange={(v) => setStateFilter(v as NaicomSubmissionState | 'ALL')}>
            <SelectTrigger className="w-44"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All states</SelectItem>
              <SelectItem value="DRAFT">DRAFT</SelectItem>
              <SelectItem value="SUBMITTED">SUBMITTED</SelectItem>
              <SelectItem value="ACKNOWLEDGED">ACKNOWLEDGED</SelectItem>
              <SelectItem value="ARCHIVED">ARCHIVED</SelectItem>
              <SelectItem value="RETRACTED">RETRACTED</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <div className="ml-auto">
          <Button onClick={() => setGenerateOpen(true)} disabled={!selectedPeriodId}>+ Generate submission</Button>
        </div>
      </div>

      {!canList && (
        <PageSection>
          <div className="rounded-md border bg-muted/40 px-4 py-12 text-center text-sm text-muted-foreground">
            Pick a period and/or state to list submissions. Backend rejects queries with both filters omitted (full-table scan guard).
          </div>
        </PageSection>
      )}

      {canList && (
        <>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <StatCard label="Submissions (filtered)" value={counts.total.toLocaleString()} />
            <StatCard label="DRAFT"                  value={counts.draft.toLocaleString()} />
            <StatCard label="SUBMITTED"              value={counts.submitted.toLocaleString()} />
            <StatCard label="ACKNOWLEDGED"           value={counts.acknowledged.toLocaleString()} />
          </div>

          <PageSection>
            {submissionsQuery.isLoading ? (
              <Skeleton className="h-72 w-full rounded-lg" />
            ) : submissionsQuery.isError ? (
              <div className="rounded-md border border-destructive/50 bg-destructive/5 px-4 py-3 text-sm text-destructive">
                Failed to load submissions.
              </div>
            ) : submissions.length === 0 ? (
              <div className="rounded-md border bg-muted/40 px-4 py-12 text-center text-sm text-muted-foreground">
                No submissions match the current filters. Click "+ Generate submission" to create one for the selected period.
              </div>
            ) : (
              <table className="w-full text-sm border-collapse">
                <thead className="text-xs text-muted-foreground border-b">
                  <tr>
                    <th className="text-left font-medium py-2 px-2">Type</th>
                    <th className="text-left font-medium py-2 px-2">Period</th>
                    <th className="text-left font-medium py-2 px-2">State</th>
                    <th className="text-left font-medium py-2 px-2">Submitted</th>
                    <th className="text-left font-medium py-2 px-2">Acknowledged</th>
                    <th className="text-left font-medium py-2 px-2">NAICOM UID</th>
                  </tr>
                </thead>
                <tbody>
                  {submissions.map((s) => (
                    <tr
                      key={s.id}
                      className="border-b last:border-0 hover:bg-secondary/40 cursor-pointer"
                      onClick={() => setDetailId(s.id)}
                    >
                      <td className="py-2 px-2">
                        <div className="font-mono text-xs">{SUBMISSION_TYPE_CODE[s.submissionType]}</div>
                        <div className="text-xs text-muted-foreground">{SUBMISSION_TYPE_LABEL[s.submissionType]}</div>
                      </td>
                      <td className="py-2 px-2 font-mono text-xs">{formatDate(s.periodStart)} → {formatDate(s.periodEnd)}</td>
                      <td className="py-2 px-2"><Badge variant={STATE_VARIANT[s.state]}>{s.state}</Badge></td>
                      <td className="py-2 px-2 font-mono text-xs">{s.submittedAt ? formatDate(s.submittedAt) : <span className="text-muted-foreground">—</span>}</td>
                      <td className="py-2 px-2 font-mono text-xs">{s.acknowledgedAt ? formatDate(s.acknowledgedAt) : <span className="text-muted-foreground">—</span>}</td>
                      <td className="py-2 px-2 font-mono text-xs">{s.naicomUid ?? <span className="text-muted-foreground">—</span>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </PageSection>
        </>
      )}

      <GenerateSubmissionDialog
        open={generateOpen}
        onOpenChange={setGenerateOpen}
        periodId={selectedPeriodId}
        periodLabel={monthPeriods.find((p) => p.id === selectedPeriodId)?.startDate ? formatPeriodLabel(monthPeriods.find((p) => p.id === selectedPeriodId)!) : ''}
      />

      <NaicomSubmissionDetailSheet
        submissionId={detailId}
        open={!!detailId}
        onOpenChange={(open) => !open && setDetailId(null)}
      />
    </div>
  );
}

// ── Generate dialog ──────────────────────────────────────────────────────

interface GenerateSubmissionDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  periodId: string | null;
  periodLabel: string;
}

function GenerateSubmissionDialog({ open, onOpenChange, periodId, periodLabel }: GenerateSubmissionDialogProps) {
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const [submissionType, setSubmissionType] = useState<NaicomSubmissionType>('ANNUAL_REVENUE_ACCOUNT');
  const [reason, setReason] = useState('');

  const generateMutation = useMutation({
    mutationFn: () => validatedPost(
      '/api/v1/finance/naicom/submissions/generate',
      { submissionType, periodId, reason: reason.trim() || undefined },
      NaicomSubmissionDtoSchema,
    ),
    onSuccess: (s) => {
      toast({
        title: 'Submission generated',
        description: `${SUBMISSION_TYPE_CODE[s.submissionType]} ${SUBMISSION_TYPE_LABEL[s.submissionType]} — ${s.state}`,
      });
      queryClient.invalidateQueries({ queryKey: ['closures', 'naicom-submissions'] });
      setReason('');
      onOpenChange(false);
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : 'Request failed';
      toast({ title: 'Generate failed', description: msg, variant: 'destructive' });
    },
  });

  function handleClose(nextOpen: boolean) {
    if (!nextOpen) setReason('');
    onOpenChange(nextOpen);
  }

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>Generate NAICOM submission</DialogTitle>
          <DialogDescription>
            Period <span className="font-mono">{periodLabel || '(none selected)'}</span> must be HARD_CLOSED. Idempotent under (type, period) — re-generating an existing DRAFT updates its payload in place. Once SUBMITTED, the payload is frozen.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <div className="space-y-1.5">
            <Label htmlFor="naicom-type">Submission type</Label>
            <Select value={submissionType} onValueChange={(v) => setSubmissionType(v as NaicomSubmissionType)}>
              <SelectTrigger id="naicom-type"><SelectValue /></SelectTrigger>
              <SelectContent>
                {SUBMISSION_TYPES.map((t) => (
                  <SelectItem key={t.value} value={t.value}>
                    <span className="font-mono text-xs mr-2">{t.code}</span>{t.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="naicom-reason">Reason <span className="text-muted-foreground">(optional)</span></Label>
            <Textarea
              id="naicom-reason"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="Audit-log note (e.g. 're-generation after corrected JE')"
              rows={2}
            />
          </div>
        </div>
        <DialogFooter className="gap-2">
          <Button type="button" variant="outline" onClick={() => handleClose(false)} disabled={generateMutation.isPending}>Cancel</Button>
          <Button onClick={() => generateMutation.mutate()} disabled={generateMutation.isPending || !periodId}>
            {generateMutation.isPending ? 'Generating…' : 'Generate'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
