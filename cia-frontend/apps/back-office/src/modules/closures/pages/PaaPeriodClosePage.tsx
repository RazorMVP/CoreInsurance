import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import {
  Badge, Button,
  PageHeader, PageSection,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Skeleton, StatCard,
  useToast,
} from '@cia/ui';
import {
  validatedGet, validatedPost,
  FiscalYearDtoSchema, FiscalPeriodDtoSchema,
  PaaPeriodCloseResultDtoSchema, InsuranceServiceResultDtoSchema,
  type FiscalYearDto, type FiscalPeriodDto,
  type PaaPeriodCloseResultDto,
  type InsuranceServiceResultDto,
} from '@cia/api-client';

function formatNGN(amount: number) {
  return `₦${amount.toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function formatPeriodLabel(p: FiscalPeriodDto) {
  return new Date(p.startDate).toLocaleDateString('en-GB', { month: 'long', year: 'numeric' });
}

export default function PaaPeriodClosePage() {
  const [selectedFyId,     setSelectedFyId]     = useState<string | null>(null);
  const [selectedPeriodId, setSelectedPeriodId] = useState<string | null>(null);
  const [closeResult,      setCloseResult]      = useState<PaaPeriodCloseResultDto | null>(null);

  const queryClient = useQueryClient();
  const { toast } = useToast();

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

  // ISR view (read-only, recomputable on demand)
  const isrQuery = useQuery<InsuranceServiceResultDto>({
    queryKey: ['closures', 'paa-isr', selectedPeriodId],
    queryFn:  () => validatedGet(
      `/api/v1/finance/paa/insurance-service-result/${selectedPeriodId}`,
      InsuranceServiceResultDtoSchema,
    ),
    enabled: !!selectedPeriodId,
  });

  const closeMutation = useMutation({
    mutationFn: () => validatedPost(
      `/api/v1/finance/paa/period-close/${selectedPeriodId}`,
      {},
      PaaPeriodCloseResultDtoSchema,
    ),
    onSuccess: (result) => {
      setCloseResult(result);
      toast({
        title: 'PAA period close complete',
        description: `Insurance Service Result: ${formatNGN(result.insuranceServiceResult.totalInsuranceServiceResult)}.`,
      });
      queryClient.invalidateQueries({ queryKey: ['closures', 'paa-isr', selectedPeriodId] });
    },
    onError: (err: unknown) => {
      toast({
        title: 'PAA period close failed',
        description: err instanceof Error ? err.message : 'Request failed',
        variant: 'destructive',
      });
    },
  });

  const selectedPeriod = monthPeriods.find((p) => p.id === selectedPeriodId);

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="PAA Period Close"
        description="IFRS 17 Premium Allocation Approach (Phase 2 — Slice 2.5). The orchestrator runs LRC + LIC + Discount Unwind + Onerous test engines for a period and returns the §83/§84 Insurance Service Result. Idempotent — engines that have already run for the period are skipped."
      />

      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1">
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Fiscal year</label>
          <Select
            value={effectiveFyId ?? undefined}
            onValueChange={(v) => { setSelectedFyId(v); setSelectedPeriodId(null); setCloseResult(null); }}
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
            onValueChange={(v) => { setSelectedPeriodId(v); setCloseResult(null); }}
            disabled={monthPeriods.length === 0}
          >
            <SelectTrigger className="w-64"><SelectValue placeholder="Choose period…" /></SelectTrigger>
            <SelectContent>
              {monthPeriods.map((p) => (
                <SelectItem key={p.id} value={p.id}>
                  {formatPeriodLabel(p)} <span className="ml-1 text-muted-foreground text-xs">({p.status})</span>
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <Button
          onClick={() => closeMutation.mutate()}
          disabled={!selectedPeriodId || closeMutation.isPending}
        >
          {closeMutation.isPending ? 'Running close…' : 'Run PAA close'}
        </Button>
        {selectedPeriod && (
          <div className="ml-auto text-xs text-muted-foreground">
            <Badge variant={selectedPeriod.status === 'OPEN' ? 'active' : selectedPeriod.status === 'HARD_CLOSED' ? 'rejected' : 'pending'}>
              {selectedPeriod.status}
            </Badge>
          </div>
        )}
      </div>

      {!selectedPeriodId && (
        <PageSection>
          <div className="rounded-md border bg-muted/40 px-4 py-12 text-center text-sm text-muted-foreground">
            Pick a MONTH period to view its IFRS 17 §83/§84 Insurance Service Result, or to run the PAA close orchestrator.
          </div>
        </PageSection>
      )}

      {selectedPeriodId && (
        <>
          <PageSection>
            <h3 className="text-sm font-semibold mb-3">§83 / §84 Insurance Service Result</h3>
            {isrQuery.isLoading ? (
              <Skeleton className="h-20 w-full rounded-md" />
            ) : isrQuery.isError ? (
              <div className="rounded-md border border-destructive/50 bg-destructive/5 px-3 py-2 text-sm text-destructive">
                Failed to load §83/§84 result.
              </div>
            ) : isrQuery.data && (
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
                <StatCard label="Insurance revenue (§83)"        value={formatNGN(isrQuery.data.totalInsuranceRevenue)} />
                <StatCard label="Insurance service expense (§84)" value={formatNGN(isrQuery.data.totalInsuranceServiceExpense)} />
                <StatCard label="Insurance service result"        value={formatNGN(isrQuery.data.totalInsuranceServiceResult)} />
              </div>
            )}
            <p className="mt-2 text-xs text-muted-foreground italic">
              Read-only. Recomputed on demand from <code className="font-mono">paa_lrc</code> + <code className="font-mono">paa_lic</code> roll-forward state. Run PAA close to refresh those tables.
            </p>
          </PageSection>

          {closeResult && (
            <PageSection>
              <h3 className="text-sm font-semibold mb-3">Engine output (from last close)</h3>
              <div className="space-y-3">
                <EngineCard
                  title="LRC — Liability for Remaining Coverage"
                  subtitle="§44(a) — straight-line daily premium recognition"
                  badge={closeResult.lrc ? 'RAN' : 'SKIPPED'}
                  body={closeResult.lrc ? (
                    <>
                      <StatRow label="Groups processed"        value={closeResult.lrc.groupsProcessed.toString()} />
                      <StatRow label="Groups with JE"          value={closeResult.lrc.groupsWithJournalEntry.toString()} />
                      <StatRow label="Total premium earned"    value={formatNGN(closeResult.lrc.totalPremiumEarned)} />
                      {closeResult.lrc.entries.length > 0 && (
                        <details className="mt-2 text-xs">
                          <summary className="cursor-pointer text-muted-foreground hover:text-foreground">
                            Per-group breakdown ({closeResult.lrc.entries.length})
                          </summary>
                          <table className="mt-2 w-full text-xs">
                            <thead className="border-b text-muted-foreground">
                              <tr>
                                <th className="text-left py-1">Group</th>
                                <th className="text-right py-1">Opening</th>
                                <th className="text-right py-1">Received</th>
                                <th className="text-right py-1">Earned</th>
                                <th className="text-right py-1">Closing</th>
                              </tr>
                            </thead>
                            <tbody>
                              {closeResult.lrc.entries.map((e) => (
                                <tr key={e.groupId} className="border-b last:border-0">
                                  <td className="py-1 font-mono">{e.groupId.slice(0, 8)}…</td>
                                  <td className="text-right font-mono">{formatNGN(e.openingBalance)}</td>
                                  <td className="text-right font-mono">{formatNGN(e.premiumReceived)}</td>
                                  <td className="text-right font-mono">{formatNGN(e.premiumEarned)}</td>
                                  <td className="text-right font-mono">{formatNGN(e.closingBalance)}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </details>
                      )}
                    </>
                  ) : (
                    <p className="text-xs text-muted-foreground italic">Already ran for this period; skipped (idempotent).</p>
                  )}
                />

                <EngineCard
                  title="LIC — Liability for Incurred Claims"
                  subtitle="§40(b) — claim roll-forward (no JE in v1)"
                  badge={closeResult.lic ? 'RAN' : 'SKIPPED'}
                  body={closeResult.lic ? (
                    <>
                      <StatRow label="Groups processed"      value={closeResult.lic.groupsProcessed.toString()} />
                      <StatRow label="Total claims incurred" value={formatNGN(closeResult.lic.totalClaimsIncurred)} />
                      <StatRow label="Total claims paid"     value={formatNGN(closeResult.lic.totalClaimsPaid)} />
                    </>
                  ) : (
                    <p className="text-xs text-muted-foreground italic">Already ran for this period; skipped.</p>
                  )}
                />

                <EngineCard
                  title="Discount Unwind"
                  subtitle="§87-92 — discount unwind on LIC"
                  badge={closeResult.discountUnwind.discountingDisabled ? 'DISABLED' : 'RAN'}
                  body={closeResult.discountUnwind.discountingDisabled ? (
                    <p className="text-xs text-muted-foreground italic">
                      Discounting disabled per tenant <code className="font-mono">paa_config.discount_lic</code> election (Nigerian short-tail GB default).
                    </p>
                  ) : (
                    <>
                      <StatRow label="Routing"           value={closeResult.discountUnwind.routing ?? '—'} />
                      <StatRow label="Groups processed"  value={closeResult.discountUnwind.groupsProcessed.toString()} />
                      <StatRow label="Total unwind"      value={formatNGN(closeResult.discountUnwind.totalUnwind)} />
                    </>
                  )}
                />

                <EngineCard
                  title="Onerous Contract Test"
                  subtitle="§47-49 — loss-component recognition + reversal"
                  badge={closeResult.onerousTest.groupsWithLossComponentChange > 0 ? 'CHANGES' : 'NO-CHANGE'}
                  body={
                    <>
                      <StatRow label="Groups tested"                    value={closeResult.onerousTest.groupsTested.toString()} />
                      <StatRow label="Groups with change"               value={closeResult.onerousTest.groupsWithLossComponentChange.toString()} />
                      <StatRow label="Total loss-component increase"    value={formatNGN(closeResult.onerousTest.totalLossComponentIncrease)} />
                      <StatRow label="Total loss-component reversal"    value={formatNGN(closeResult.onerousTest.totalLossComponentReversal)} />
                    </>
                  }
                />
              </div>
            </PageSection>
          )}
        </>
      )}
    </div>
  );
}

function StatRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between text-sm py-0.5">
      <span className="text-muted-foreground">{label}</span>
      <span className="font-mono">{value}</span>
    </div>
  );
}

function EngineCard({ title, subtitle, badge, body }: {
  title: string;
  subtitle: string;
  badge: 'RAN' | 'SKIPPED' | 'DISABLED' | 'CHANGES' | 'NO-CHANGE';
  body: React.ReactNode;
}) {
  const variant: Record<typeof badge, 'active' | 'pending' | 'rejected' | 'draft'> = {
    'RAN':       'active',
    'SKIPPED':   'draft',
    'DISABLED':  'draft',
    'CHANGES':   'pending',
    'NO-CHANGE': 'draft',
  };
  return (
    <div className="rounded-md border bg-card px-4 py-3">
      <div className="flex items-start justify-between gap-2 mb-2">
        <div>
          <h4 className="text-sm font-semibold">{title}</h4>
          <p className="text-xs text-muted-foreground">{subtitle}</p>
        </div>
        <Badge variant={variant[badge]}>{badge}</Badge>
      </div>
      {body}
    </div>
  );
}
