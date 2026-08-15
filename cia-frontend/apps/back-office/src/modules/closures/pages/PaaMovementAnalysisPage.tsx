import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import {
  Badge,
  PageHeader, PageSection,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Skeleton, StatCard,
} from '@cia/ui';
import {
  validatedGet,
  FiscalYearDtoSchema, FiscalPeriodDtoSchema,
  MovementAnalysisDtoSchema,
  CONTRACT_NATURE_LABELS,
  CONTRACT_NATURE_VARIANTS,
  type FiscalYearDto, type FiscalPeriodDto,
  type MovementAnalysisDto,
  type LrcMovementTotalsDto,
  type LicMovementTotalsDto,
} from '@cia/api-client';
import { RollforwardTable } from '../components/RollforwardTable';

function formatNGN(amount: number) {
  return `₦${amount.toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function formatPeriodLabel(p: FiscalPeriodDto) {
  return new Date(p.startDate).toLocaleDateString('en-GB', { month: 'long', year: 'numeric' });
}

// contractNature arrives as the raw enum name string (DIRECT / FAC_INWARD /
// FAC_OUTWARD) — same portfolio dimension surfaced on ContractGroupsPage.
// Labels + badge variants are the shared CONTRACT_NATURE_LABELS /
// CONTRACT_NATURE_VARIANTS from @cia/api-client (one source of truth, no
// independent Record<ContractNature, ...> copies to drift out of sync — see
// FAC / IFRS-17 PAA workstream Task 7 review, M3).

// LRC row keys + labels, in §103(a) presentation order.
const LRC_ROWS: { key: keyof LrcMovementTotalsDto; label: string; sign?: '+' | '−' }[] = [
  { key: 'opening',                   label: 'Opening balance' },
  { key: 'premiumsReceived',          label: 'Premiums received',          sign: '+' },
  { key: 'premiumEarned',             label: 'Premium earned (revenue)',   sign: '−' },
  { key: 'acquisitionCostsDeferred',  label: 'Acquisition costs deferred', sign: '+' },
  { key: 'acquisitionCostsAmortised', label: 'Acquisition costs amortised', sign: '−' },
  { key: 'lossComponentChange',       label: 'Loss-component change',      sign: '+' },
  { key: 'closing',                   label: 'Closing balance' },
];

// LIC row keys + labels, in §103(b) presentation order.
const LIC_ROWS: { key: keyof LicMovementTotalsDto; label: string; sign?: '+' | '−' }[] = [
  { key: 'opening',              label: 'Opening balance' },
  { key: 'claimsIncurred',       label: 'Claims incurred',         sign: '+' },
  { key: 'claimsPaid',           label: 'Claims paid',             sign: '−' },
  { key: 'caseReserveChange',    label: 'Case reserve change',     sign: '+' },
  { key: 'ibnrChange',           label: 'IBNR change',             sign: '+' },
  { key: 'riskAdjustmentChange', label: 'Risk adjustment change',  sign: '+' },
  { key: 'discountUnwind',       label: 'Discount unwind',         sign: '+' },
  { key: 'closing',              label: 'Closing balance' },
];

export default function PaaMovementAnalysisPage() {
  const [selectedFyId,     setSelectedFyId]     = useState<string | null>(null);
  const [selectedPeriodId, setSelectedPeriodId] = useState<string | null>(null);

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

  const maQuery = useQuery<MovementAnalysisDto>({
    queryKey: ['closures', 'movement-analysis', selectedPeriodId],
    queryFn:  () => validatedGet(
      `/api/v1/finance/paa/movement-analysis/${selectedPeriodId}`,
      MovementAnalysisDtoSchema,
    ),
    enabled: !!selectedPeriodId,
  });

  const ma = maQuery.data;

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="§103 Movement Analysis"
        description="IFRS 17 §103 disclosure shape — full LRC + LIC roll-forward for one fiscal period. Read-only relay over Slice 2.8's V38 paa_movement_analysis SQL view; consumed downstream by Phase 4 NAICOM Ifrs17DisclosureEngine. The §103 invariant: opening ± movements = closing, per group."
      />

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
            onValueChange={setSelectedPeriodId}
            disabled={monthPeriods.length === 0}
          >
            <SelectTrigger className="w-64"><SelectValue placeholder="Choose period…" /></SelectTrigger>
            <SelectContent>
              {monthPeriods.map((p) => (
                <SelectItem key={p.id} value={p.id}>{formatPeriodLabel(p)}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>

      {!selectedPeriodId && (
        <PageSection>
          <div className="rounded-md border bg-muted/40 px-4 py-12 text-center text-sm text-muted-foreground">
            Pick a MONTH period to view its §103 movement analysis.
          </div>
        </PageSection>
      )}

      {selectedPeriodId && maQuery.isLoading && (
        <Skeleton className="h-96 w-full rounded-lg" />
      )}

      {selectedPeriodId && maQuery.isError && (
        <div className="rounded-md border border-destructive/50 bg-destructive/5 px-3 py-2 text-sm text-destructive">
          Failed to load §103 movement analysis.
        </div>
      )}

      {ma && (
        <>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <StatCard label="Opening liability (LRC + LIC)" value={formatNGN(ma.totalOpeningLiability)} />
            <StatCard label="Closing liability (LRC + LIC)" value={formatNGN(ma.totalClosingLiability)} />
            <StatCard
              label="Net movement"
              value={formatNGN(ma.totalClosingLiability - ma.totalOpeningLiability)}
            />
          </div>

          <PageSection>
            <h3 className="text-sm font-semibold mb-2">§103(a) — LRC roll-forward</h3>
            <RollforwardTable rows={LRC_ROWS} totals={ma.lrcTotals} />
          </PageSection>

          <PageSection>
            <h3 className="text-sm font-semibold mb-2">§103(b) — LIC roll-forward</h3>
            <RollforwardTable rows={LIC_ROWS} totals={ma.licTotals} />
          </PageSection>

          <PageSection>
            <div className="flex items-center justify-between mb-2">
              <h3 className="text-sm font-semibold">Per-group breakdown</h3>
              <span className="text-xs text-muted-foreground">
                {ma.byGroup.length} {ma.byGroup.length === 1 ? 'group' : 'groups'} · preserves §22 grouping
              </span>
            </div>
            {ma.byGroup.length === 0 ? (
              <div className="rounded-md border bg-muted/40 px-4 py-8 text-center text-sm text-muted-foreground">
                No contract groups for this period.
              </div>
            ) : (
              <table className="w-full text-xs border-collapse">
                <thead className="text-[10px] text-muted-foreground border-b uppercase tracking-wide">
                  <tr>
                    <th className="text-left py-2 px-2">Group</th>
                    <th className="text-left py-2 px-2">Portfolio</th>
                    <th className="text-left py-2 px-2">Nature</th>
                    <th className="text-right py-2 px-2">Cohort</th>
                    <th className="text-left py-2 px-2">Onerous?</th>
                    <th className="text-right py-2 px-2">LRC opening</th>
                    <th className="text-right py-2 px-2">LRC closing</th>
                    <th className="text-right py-2 px-2">LIC opening</th>
                    <th className="text-right py-2 px-2">LIC closing</th>
                    <th className="text-right py-2 px-2">Total closing</th>
                  </tr>
                </thead>
                <tbody>
                  {ma.byGroup.map((g) => (
                    <tr key={g.groupId} className="border-b last:border-0 hover:bg-secondary/30">
                      <td className="py-1.5 px-2 font-mono text-[11px]">{g.groupId.slice(0, 8)}…</td>
                      <td className="py-1.5 px-2">
                        <div>{g.portfolioName ?? '—'}</div>
                        <div className="font-mono text-[10px] text-muted-foreground">{g.portfolioCode ?? '—'}</div>
                      </td>
                      <td className="py-1.5 px-2">
                        {g.contractNature ? (
                          <Badge variant={CONTRACT_NATURE_VARIANTS[g.contractNature as keyof typeof CONTRACT_NATURE_VARIANTS] ?? 'outline'} className="text-[10px]">
                            {CONTRACT_NATURE_LABELS[g.contractNature as keyof typeof CONTRACT_NATURE_LABELS] ?? g.contractNature}
                          </Badge>
                        ) : (
                          <span className="text-muted-foreground">—</span>
                        )}
                      </td>
                      <td className="py-1.5 px-2 text-right font-mono">{g.cohortYear ?? '—'}</td>
                      <td className="py-1.5 px-2">
                        {g.onerousness === 'ONEROUS' && <Badge variant="rejected" className="text-[10px]">{g.onerousness}</Badge>}
                        {g.onerousness === 'PROFITABLE_AT_RECOGNITION' && <Badge variant="active" className="text-[10px]">PROFITABLE</Badge>}
                        {g.onerousness === 'POTENTIAL_ONEROUS' && <Badge variant="pending" className="text-[10px]">POTENTIAL</Badge>}
                        {!g.onerousness && <span className="text-muted-foreground">—</span>}
                      </td>
                      <td className="py-1.5 px-2 text-right font-mono">{formatNGN(g.lrcOpening)}</td>
                      <td className="py-1.5 px-2 text-right font-mono">{formatNGN(g.lrcClosing)}</td>
                      <td className="py-1.5 px-2 text-right font-mono">{formatNGN(g.licOpening)}</td>
                      <td className="py-1.5 px-2 text-right font-mono">{formatNGN(g.licClosing)}</td>
                      <td className="py-1.5 px-2 text-right font-mono font-semibold">{formatNGN(g.totalClosing)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </PageSection>
        </>
      )}
    </div>
  );
}

