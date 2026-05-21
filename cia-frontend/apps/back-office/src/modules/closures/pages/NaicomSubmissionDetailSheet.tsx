import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import {
  Badge,
  Button,
  Input,
  Label,
  Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle,
  Skeleton,
  Textarea,
  useToast,
} from '@cia/ui';
import {
  apiClient,
  validatedGet, validatedPost,
  NaicomSubmissionDtoSchema,
  NaicomSubmissionEventDtoSchema,
  SubmissionArtifactDtoSchema,
  type NaicomSubmissionDto,
  type NaicomSubmissionEventDto,
  type NaicomSubmissionState,
  type SubmissionArtifactDto,
  type ArtifactFormat,
} from '@cia/api-client';
import { useAuth } from '@cia/auth';

interface NaicomSubmissionDetailSheetProps {
  submissionId: string | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const STATE_VARIANT: Record<NaicomSubmissionState, 'active' | 'pending' | 'draft' | 'rejected'> = {
  DRAFT:        'draft',
  SUBMITTED:    'pending',
  ACKNOWLEDGED: 'active',
  ARCHIVED:     'draft',
  RETRACTED:    'rejected',
};

function formatInstant(iso?: string | null) {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' });
}

function formatBytes(n: number) {
  if (n < 1024)         return `${n} B`;
  if (n < 1024 * 1024)  return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(2)} MB`;
}

const ARTIFACT_FORMATS: ArtifactFormat[] = ['PDF', 'CSV', 'JSON'];

export default function NaicomSubmissionDetailSheet({ submissionId, open, onOpenChange }: NaicomSubmissionDetailSheetProps) {
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const { hasRole } = useAuth();
  const canRender = hasRole('FINANCE_APPROVE');

  const submissionQuery = useQuery<NaicomSubmissionDto>({
    queryKey: ['closures', 'naicom-submission', submissionId],
    queryFn:  () => validatedGet(`/api/v1/finance/naicom/submissions/${submissionId}`, NaicomSubmissionDtoSchema),
    enabled:  open && !!submissionId,
  });

  const eventsQuery = useQuery<NaicomSubmissionEventDto[]>({
    queryKey: ['closures', 'naicom-events', submissionId],
    queryFn:  () => validatedGet(
      `/api/v1/finance/naicom/submissions/${submissionId}/events`,
      z.array(NaicomSubmissionEventDtoSchema),
    ),
    enabled:  open && !!submissionId,
  });

  const artifactsQuery = useQuery<SubmissionArtifactDto[]>({
    queryKey: ['closures', 'naicom-artifacts', submissionId],
    queryFn:  () => validatedGet(
      `/api/v1/finance/naicom/submissions/${submissionId}/artifacts`,
      z.array(SubmissionArtifactDtoSchema),
    ),
    enabled:  open && !!submissionId,
  });

  const submission = submissionQuery.data;
  const events     = eventsQuery.data ?? [];
  const artifacts  = artifactsQuery.data ?? [];

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: ['closures', 'naicom-submission', submissionId] });
    queryClient.invalidateQueries({ queryKey: ['closures', 'naicom-events', submissionId] });
    queryClient.invalidateQueries({ queryKey: ['closures', 'naicom-submissions'] });
  }

  // ── State-transition mutations ─────────────────────────────────────────

  const [submitReason,      setSubmitReason]      = useState('');
  const [acknowledgeUid,    setAcknowledgeUid]    = useState('');
  const [retractReason,     setRetractReason]     = useState('');

  const submitMutation = useMutation({
    mutationFn: () => validatedPost(
      `/api/v1/finance/naicom/submissions/${submissionId}/submit`,
      { reason: submitReason.trim() || undefined },
      NaicomSubmissionDtoSchema,
    ),
    onSuccess: () => {
      toast({ title: 'Submission marked SUBMITTED', description: 'Payload is now frozen.' });
      setSubmitReason(''); invalidate();
    },
    onError: (err: unknown) => {
      toast({ title: 'Submit failed', description: err instanceof Error ? err.message : 'Request failed', variant: 'destructive' });
    },
  });

  const acknowledgeMutation = useMutation({
    mutationFn: () => validatedPost(
      `/api/v1/finance/naicom/submissions/${submissionId}/acknowledge`,
      { naicomUid: acknowledgeUid.trim() },
      NaicomSubmissionDtoSchema,
    ),
    onSuccess: () => {
      toast({ title: 'NAICOM acknowledgement recorded', description: `UID ${acknowledgeUid} attached.` });
      setAcknowledgeUid(''); invalidate();
    },
    onError: (err: unknown) => {
      toast({ title: 'Acknowledge failed', description: err instanceof Error ? err.message : 'Request failed', variant: 'destructive' });
    },
  });

  const retractMutation = useMutation({
    mutationFn: () => validatedPost(
      `/api/v1/finance/naicom/submissions/${submissionId}/retract`,
      { reason: retractReason.trim() },
      NaicomSubmissionDtoSchema,
    ),
    onSuccess: () => {
      toast({ title: 'Submission retracted', description: 'Row soft-deleted; (type, period) slot now vacant for a fresh submission.' });
      setRetractReason(''); invalidate();
    },
    onError: (err: unknown) => {
      toast({ title: 'Retract failed', description: err instanceof Error ? err.message : 'Request failed', variant: 'destructive' });
    },
  });

  const archiveMutation = useMutation({
    mutationFn: () => validatedPost(
      `/api/v1/finance/naicom/submissions/${submissionId}/archive`,
      {},
      NaicomSubmissionDtoSchema,
    ),
    onSuccess: () => {
      toast({ title: 'Submission archived', description: 'Moved to long-term audit retention.' });
      invalidate();
    },
    onError: (err: unknown) => {
      toast({ title: 'Archive failed', description: err instanceof Error ? err.message : 'Request failed', variant: 'destructive' });
    },
  });

  // ── Artifact render + download ─────────────────────────────────────────

  const renderArtifactMutation = useMutation({
    mutationFn: (format: ArtifactFormat) => validatedPost(
      `/api/v1/finance/naicom/submissions/${submissionId}/artifacts/${format}`,
      {},
      SubmissionArtifactDtoSchema,
    ),
    onSuccess: (a) => {
      toast({
        title: `${a.format} artifact rendered`,
        description: `${formatBytes(a.sizeBytes)} · SHA-256 ${a.sha256Hex.slice(0, 12)}…`,
      });
      queryClient.invalidateQueries({ queryKey: ['closures', 'naicom-artifacts', submissionId] });
    },
    onError: (err: unknown) => {
      toast({ title: 'Render failed', description: err instanceof Error ? err.message : 'Request failed', variant: 'destructive' });
    },
  });

  function downloadArtifact(format: ArtifactFormat) {
    if (!submissionId) return;
    apiClient.get(
      `/api/v1/finance/naicom/submissions/${submissionId}/artifacts/${format}/download`,
      { responseType: 'blob' },
    )
      .then((res) => {
        const blob = new Blob([res.data as Blob]);
        const url  = URL.createObjectURL(blob);
        const a    = document.createElement('a');
        a.href = url;
        a.download = submission
          ? `naicom-${submission.submissionType.toLowerCase()}-${submission.periodEnd}.${format.toLowerCase()}`
          : `naicom-artifact.${format.toLowerCase()}`;
        a.click();
        URL.revokeObjectURL(url);
      })
      .catch((err: unknown) => {
        toast({ title: 'Download failed', description: err instanceof Error ? err.message : 'Request failed', variant: 'destructive' });
      });
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="w-full sm:max-w-2xl overflow-y-auto">
        <SheetHeader>
          <SheetTitle>NAICOM submission</SheetTitle>
          <SheetDescription>
            {submission
              ? `${submission.submissionType.replace(/_/g, ' ')} · ${submission.periodStart} → ${submission.periodEnd}`
              : submissionQuery.isLoading ? 'Loading…' : 'No submission selected'}
          </SheetDescription>
        </SheetHeader>

        {submissionQuery.isLoading && (
          <div className="mt-6 space-y-3">
            <Skeleton className="h-20 w-full rounded-md" />
            <Skeleton className="h-32 w-full rounded-md" />
          </div>
        )}

        {submission && (
          <div className="mt-6 space-y-5">
            {/* ── State + metadata ──────────────────────────────────── */}
            <div className="space-y-2.5">
              <Badge variant={STATE_VARIANT[submission.state]}>{submission.state}</Badge>
              <dl className="grid grid-cols-[10rem_1fr] gap-x-3 gap-y-1.5 text-sm">
                {submission.submittedAt && (
                  <>
                    <dt className="text-muted-foreground">Submitted</dt>
                    <dd className="font-mono text-xs">{formatInstant(submission.submittedAt)} · {submission.submittedBy ?? '—'}</dd>
                  </>
                )}
                {submission.acknowledgedAt && (
                  <>
                    <dt className="text-muted-foreground">Acknowledged</dt>
                    <dd className="font-mono text-xs">{formatInstant(submission.acknowledgedAt)} · {submission.acknowledgedBy ?? '—'}</dd>
                  </>
                )}
                {submission.naicomUid && (
                  <>
                    <dt className="text-muted-foreground">NAICOM UID</dt>
                    <dd className="font-mono">{submission.naicomUid}</dd>
                  </>
                )}
                {submission.archivedAt && (
                  <>
                    <dt className="text-muted-foreground">Archived</dt>
                    <dd className="font-mono text-xs">{formatInstant(submission.archivedAt)}</dd>
                  </>
                )}
                {submission.retractedAt && (
                  <>
                    <dt className="text-muted-foreground">Retracted</dt>
                    <dd className="font-mono text-xs">{formatInstant(submission.retractedAt)} · {submission.retractedBy ?? '—'}</dd>
                    <dt className="text-muted-foreground">Retraction reason</dt>
                    <dd className="italic">{submission.retractionReason ?? '—'}</dd>
                  </>
                )}
              </dl>
            </div>

            {/* ── State-transition controls ─────────────────────────── */}
            <div className="rounded-md border bg-muted/30 px-3 py-3 space-y-3">
              <h4 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">State transitions</h4>

              {submission.state === 'DRAFT' && (
                <div className="space-y-2">
                  <Label htmlFor="submit-reason" className="text-xs">Submit to NAICOM <span className="text-muted-foreground">(reason optional)</span></Label>
                  <div className="flex items-center gap-2">
                    <Input id="submit-reason" value={submitReason} onChange={(e) => setSubmitReason(e.target.value)} placeholder="Optional reason" className="flex-1" />
                    <Button size="sm" onClick={() => submitMutation.mutate()} disabled={submitMutation.isPending}>
                      {submitMutation.isPending ? 'Submitting…' : 'Submit'}
                    </Button>
                  </div>
                  <p className="text-[10px] text-muted-foreground italic">After SUBMITTED, the payload is frozen — re-generation will be rejected.</p>
                </div>
              )}

              {submission.state === 'SUBMITTED' && (
                <>
                  <div className="space-y-2">
                    <Label htmlFor="ack-uid" className="text-xs">Record NAICOM acknowledgement</Label>
                    <div className="flex items-center gap-2">
                      <Input id="ack-uid" value={acknowledgeUid} onChange={(e) => setAcknowledgeUid(e.target.value)} placeholder="NAICOM UID (required)" className="flex-1" />
                      <Button size="sm" onClick={() => acknowledgeMutation.mutate()} disabled={acknowledgeMutation.isPending || !acknowledgeUid.trim()}>
                        {acknowledgeMutation.isPending ? 'Recording…' : 'Acknowledge'}
                      </Button>
                    </div>
                  </div>
                  <div className="space-y-2 border-t border-border pt-3">
                    <Label htmlFor="retract-reason" className="text-xs">Or retract <span className="text-destructive">(audit branch)</span></Label>
                    <Textarea id="retract-reason" value={retractReason} onChange={(e) => setRetractReason(e.target.value)} placeholder="Retraction reason (required)" rows={2} />
                    <Button size="sm" variant="destructive" onClick={() => retractMutation.mutate()} disabled={retractMutation.isPending || !retractReason.trim()}>
                      {retractMutation.isPending ? 'Retracting…' : 'Retract'}
                    </Button>
                  </div>
                </>
              )}

              {submission.state === 'ACKNOWLEDGED' && (
                <Button size="sm" onClick={() => archiveMutation.mutate()} disabled={archiveMutation.isPending}>
                  {archiveMutation.isPending ? 'Archiving…' : 'Archive'}
                </Button>
              )}

              {(submission.state === 'ARCHIVED' || submission.state === 'RETRACTED') && (
                <p className="text-xs text-muted-foreground italic">Terminal state — no further transitions available.</p>
              )}
            </div>

            {/* ── Events timeline ──────────────────────────────────── */}
            <div>
              <h4 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground mb-2">Event history ({events.length})</h4>
              {eventsQuery.isLoading && <Skeleton className="h-16 w-full rounded-md" />}
              {!eventsQuery.isLoading && events.length === 0 && (
                <div className="rounded-md border bg-muted/40 px-3 py-3 text-xs text-muted-foreground italic">
                  No transition events recorded.
                </div>
              )}
              <ol className="relative space-y-2 border-l border-border pl-4">
                {events.map((e) => (
                  <li key={e.id} className="relative">
                    <span className="absolute -left-[21px] top-1 h-3 w-3 rounded-full border-2 border-background bg-muted-foreground" />
                    <div className="rounded-md border bg-card px-3 py-2 text-xs">
                      <div className="flex items-center gap-2 flex-wrap">
                        {e.fromState
                          ? <><Badge variant={STATE_VARIANT[e.fromState]} className="text-[10px]">{e.fromState}</Badge><span className="text-muted-foreground">→</span></>
                          : <span className="text-muted-foreground text-[10px] italic">(initial)</span>}
                        <Badge variant={STATE_VARIANT[e.toState]} className="text-[10px]">{e.toState}</Badge>
                        <span className="ml-auto font-mono text-[10px] text-muted-foreground">{formatInstant(e.occurredAt)}</span>
                      </div>
                      <dl className="mt-1.5 grid grid-cols-[5rem_1fr] gap-x-2 gap-y-0.5 text-[11px]">
                        <dt className="text-muted-foreground">Actor</dt>
                        <dd>{e.actor}</dd>
                        {e.reason && (
                          <>
                            <dt className="text-muted-foreground">Reason</dt>
                            <dd className="italic">{e.reason}</dd>
                          </>
                        )}
                      </dl>
                    </div>
                  </li>
                ))}
              </ol>
            </div>

            {/* ── Artifacts ────────────────────────────────────────── */}
            <div className="space-y-2">
              <h4 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                Rendered artifacts {artifacts.length > 0 && <span className="text-muted-foreground/70 normal-case">({artifacts.length} live)</span>}
              </h4>
              <p className="text-[11px] text-muted-foreground italic">
                One live artifact per (submission, format). Re-rendering soft-deletes the prior row and inserts a fresh one — every render attempt survives in audit history.
              </p>
              {artifactsQuery.isLoading && <Skeleton className="h-24 w-full rounded-md" />}
              {!artifactsQuery.isLoading && (
                <div className="space-y-1.5">
                  {ARTIFACT_FORMATS.map((format) => {
                    const artifact     = artifacts.find((x) => x.format === format);
                    const isRendering  = renderArtifactMutation.isPending && renderArtifactMutation.variables === format;
                    return (
                      <div key={format} className="flex items-center gap-2 rounded-md border bg-card px-3 py-2 text-xs">
                        <Badge variant="outline" className="font-mono">{format}</Badge>
                        <div className="flex-1 min-w-0">
                          {artifact ? (
                            <div className="space-y-0.5">
                              <div className="font-mono text-[10px] text-muted-foreground truncate">
                                {formatBytes(artifact.sizeBytes)} · SHA {artifact.sha256Hex.slice(0, 12)}…
                              </div>
                              <div className="text-[10px] text-muted-foreground">
                                Rendered {formatInstant(artifact.renderedAt)}{artifact.renderedBy ? ` · ${artifact.renderedBy}` : ''}
                              </div>
                            </div>
                          ) : (
                            <span className="text-muted-foreground italic">Not yet rendered</span>
                          )}
                        </div>
                        <div className="flex items-center gap-1.5 shrink-0">
                          {canRender && (
                            <Button
                              size="sm"
                              variant={artifact ? 'ghost' : 'outline'}
                              onClick={() => renderArtifactMutation.mutate(format)}
                              disabled={isRendering}
                            >
                              {isRendering ? '…' : artifact ? 'Re-render' : 'Render'}
                            </Button>
                          )}
                          {artifact && (
                            <Button size="sm" variant="outline" onClick={() => downloadArtifact(format)}>
                              Download
                            </Button>
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            {/* ── Payload preview ──────────────────────────────────── */}
            {submission.payload && Object.keys(submission.payload).length > 0 && (
              <details>
                <summary className="cursor-pointer text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                  Payload preview (engine output)
                </summary>
                <pre className="mt-2 rounded-md border bg-muted/30 px-3 py-2 text-[10px] font-mono overflow-x-auto max-h-96">
                  {JSON.stringify(submission.payload, null, 2)}
                </pre>
              </details>
            )}
          </div>
        )}
      </SheetContent>
    </Sheet>
  );
}
