import { useMemo, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import {
  Badge, Button, Input,
  PageHeader, PageSection,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  StatCard,
  useToast,
} from '@cia/ui';
import {
  validatedGet, validatedPost,
  FiscalYearDtoSchema, FiscalPeriodDtoSchema,
  InvestmentHoldingDtoSchema,
  AmortisedCostResultDtoSchema,
  FairValueResultDtoSchema,
  EclRecognitionResultDtoSchema,
  PremiumReceivableEclResultDtoSchema,
  type FiscalYearDto, type FiscalPeriodDto,
  type InvestmentHoldingDto,
  type AmortisedCostResultDto,
  type FairValueResultDto,
  type EclRecognitionResultDto,
  type PremiumReceivableEclResultDto,
} from '@cia/api-client';

function formatNGN(amount: number) {
  return `₦${amount.toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function formatPeriodLabel(p: FiscalPeriodDto) {
  return new Date(p.startDate).toLocaleDateString('en-GB', { month: 'long', year: 'numeric' });
}

export default function Ifrs9MeasurementPage() {
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

  const holdingsQuery = useQuery<InvestmentHoldingDto[]>({
    queryKey: ['closures', 'ifrs9-holdings'],
    queryFn:  () => validatedGet('/api/v1/finance/ifrs9/holdings', z.array(InvestmentHoldingDtoSchema)),
  });
  const holdings = holdingsQuery.data ?? [];

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="IFRS 9 Measurement"
        description="Phase 3 measurement engines. Amortised Cost (§5.4.1 effective interest), Fair Value (§5.7 FVPL → P&L / FVOCI → OCI reserve), Investment ECL (§5.5 + §5.7.10A), Premium Receivable ECL (§5.5.15 simplified approach). Each engine is independently runnable; all are FINANCE_APPROVE gated."
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
            Pick a MONTH period to run IFRS 9 measurement engines.
          </div>
        </PageSection>
      )}

      {selectedPeriodId && (
        <>
          <AmortisedCostSection periodId={selectedPeriodId} />
          <FairValueSection     periodId={selectedPeriodId} holdings={holdings} />
          <InvestmentEclSection periodId={selectedPeriodId} holdings={holdings} />
          <PremiumReceivableEclSection periodId={selectedPeriodId} />
        </>
      )}
    </div>
  );
}

// ─── §5.4.1 Amortised Cost ─────────────────────────────────────────────────

function AmortisedCostSection({ periodId }: { periodId: string }) {
  const [result, setResult] = useState<AmortisedCostResultDto | null>(null);
  const { toast } = useToast();

  const mutation = useMutation({
    mutationFn: () => validatedPost(
      '/api/v1/finance/ifrs9/amortised-cost/recognise',
      { periodId },
      AmortisedCostResultDtoSchema,
    ),
    onSuccess: (r) => {
      setResult(r);
      toast({ title: 'Amortised cost recognised', description: `${r.holdingsProcessed} holdings · total interest ${formatNGN(r.totalInterestIncome)}` });
    },
    onError: (err: unknown) => {
      toast({ title: 'Amortised cost failed', description: err instanceof Error ? err.message : 'Request failed', variant: 'destructive' });
    },
  });

  return (
    <PageSection>
      <div className="flex items-start justify-between mb-3">
        <div>
          <h3 className="text-sm font-semibold">Amortised Cost — §5.4.1</h3>
          <p className="text-xs text-muted-foreground">Effective interest method. Sweeps every AC + FVOCI_DEBT holding for the period. No admin input.</p>
        </div>
        <Button onClick={() => mutation.mutate()} disabled={mutation.isPending}>
          {mutation.isPending ? 'Running…' : 'Run accrual'}
        </Button>
      </div>
      {result && (
        <>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 mb-3">
            <StatCard label="Holdings processed"  value={result.holdingsProcessed.toString()} />
            <StatCard label="Holdings with JE"    value={result.holdingsWithJournalEntry.toString()} />
            <StatCard label="Total interest"      value={formatNGN(result.totalInterestIncome)} />
          </div>
          {result.entries.length > 0 && (
            <table className="w-full text-xs border-collapse">
              <thead className="text-muted-foreground border-b">
                <tr>
                  <th className="text-left py-1 px-2">Security</th>
                  <th className="text-left py-1 px-2">Class</th>
                  <th className="text-right py-1 px-2">Opening</th>
                  <th className="text-right py-1 px-2">Interest</th>
                  <th className="text-right py-1 px-2">Closing</th>
                </tr>
              </thead>
              <tbody>
                {result.entries.map((e) => (
                  <tr key={e.holdingId} className="border-b last:border-0">
                    <td className="py-1 px-2">{e.securityName}</td>
                    <td className="py-1 px-2"><Badge variant="active" className="text-[10px]">{e.classification}</Badge></td>
                    <td className="py-1 px-2 text-right font-mono">{formatNGN(e.openingBalance)}</td>
                    <td className="py-1 px-2 text-right font-mono text-primary">{formatNGN(e.interestIncome)}</td>
                    <td className="py-1 px-2 text-right font-mono">{formatNGN(e.closingBalance)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {result.entries.length === 0 && (
            <p className="text-xs text-muted-foreground italic">No eligible holdings (AC + FVOCI_DEBT with non-null coupon rates) for this period.</p>
          )}
        </>
      )}
    </PageSection>
  );
}

// ─── §5.7 Fair Value ───────────────────────────────────────────────────────

interface ValuationRow { rowId: string; holdingId: string; fairValue: string; }

function FairValueSection({ periodId, holdings }: { periodId: string; holdings: InvestmentHoldingDto[] }) {
  const [rows, setRows]     = useState<ValuationRow[]>([{ rowId: crypto.randomUUID(), holdingId: '', fairValue: '' }]);
  const [result, setResult] = useState<FairValueResultDto | null>(null);
  const { toast } = useToast();

  // FV-eligible: FVPL / FVOCI_DEBT / FVOCI_EQUITY (anything except AC)
  const eligibleHoldings = holdings.filter((h) => h.classification !== 'AMORTISED_COST');

  const mutation = useMutation({
    mutationFn: () => validatedPost(
      '/api/v1/finance/ifrs9/fair-value/recognise',
      {
        periodId,
        valuations: rows
          .filter((r) => r.holdingId && r.fairValue)
          .map((r) => ({ holdingId: r.holdingId, fairValue: Number(r.fairValue) })),
      },
      FairValueResultDtoSchema,
    ),
    onSuccess: (r) => {
      setResult(r);
      toast({ title: 'Fair value recognised', description: `${r.holdingsProcessed} holdings · P&L ${formatNGN(r.totalFairValueChangePnl)} · OCI ${formatNGN(r.totalFairValueChangeOci)}` });
    },
    onError: (err: unknown) => {
      toast({ title: 'Fair value failed', description: err instanceof Error ? err.message : 'Request failed', variant: 'destructive' });
    },
  });

  const validRows = rows.filter((r) => r.holdingId && r.fairValue);

  return (
    <PageSection>
      <div className="mb-3">
        <h3 className="text-sm font-semibold">Fair Value — §5.7</h3>
        <p className="text-xs text-muted-foreground">FVPL → P&L · FVOCI_DEBT → OCI debt reserve · FVOCI_EQUITY → OCI equity reserve. Admin supplies the period-end fair value per holding (v2 will hook a market-data feed).</p>
      </div>
      <div className="space-y-2 mb-3">
        {rows.map((row, idx) => (
          <div key={row.rowId} className="flex items-center gap-2">
            <span className="font-mono text-xs text-muted-foreground w-6">#{idx + 1}</span>
            <Select
              value={row.holdingId || undefined}
              onValueChange={(v) => setRows((prev) => prev.map((r) => r.rowId === row.rowId ? { ...r, holdingId: v } : r))}
            >
              <SelectTrigger className="flex-1"><SelectValue placeholder="Choose holding…" /></SelectTrigger>
              <SelectContent>
                {eligibleHoldings.map((h) => (
                  <SelectItem key={h.id} value={h.id}>{h.securityName} ({h.classification})</SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Input
              type="number"
              step="0.01"
              placeholder="Fair value ₦"
              value={row.fairValue}
              onChange={(e) => setRows((prev) => prev.map((r) => r.rowId === row.rowId ? { ...r, fairValue: e.target.value } : r))}
              className="w-40 font-mono"
            />
            <Button size="sm" variant="ghost" onClick={() => setRows((prev) => prev.length > 1 ? prev.filter((r) => r.rowId !== row.rowId) : prev)}>×</Button>
          </div>
        ))}
        <Button size="sm" variant="outline" onClick={() => setRows((prev) => [...prev, { rowId: crypto.randomUUID(), holdingId: '', fairValue: '' }])} disabled={eligibleHoldings.length === 0}>+ Add row</Button>
      </div>
      <Button onClick={() => mutation.mutate()} disabled={mutation.isPending || validRows.length === 0}>
        {mutation.isPending ? 'Running…' : `Recognise (${validRows.length} ${validRows.length === 1 ? 'valuation' : 'valuations'})`}
      </Button>
      {result && (
        <div className="mt-4">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 mb-3">
            <StatCard label="Holdings processed" value={result.holdingsProcessed.toString()} />
            <StatCard label="P&L impact"         value={formatNGN(result.totalFairValueChangePnl)} />
            <StatCard label="OCI impact"         value={formatNGN(result.totalFairValueChangeOci)} />
          </div>
          {result.entries.length > 0 && (
            <table className="w-full text-xs border-collapse">
              <thead className="text-muted-foreground border-b">
                <tr>
                  <th className="text-left py-1 px-2">Security</th>
                  <th className="text-left py-1 px-2">Routing</th>
                  <th className="text-right py-1 px-2">Prior</th>
                  <th className="text-right py-1 px-2">New FV</th>
                  <th className="text-right py-1 px-2">Change</th>
                </tr>
              </thead>
              <tbody>
                {result.entries.map((e) => (
                  <tr key={e.holdingId} className="border-b last:border-0">
                    <td className="py-1 px-2">{e.securityName}</td>
                    <td className="py-1 px-2"><Badge variant={e.routing === 'PnL' ? 'rejected' : 'pending'} className="text-[10px]">{e.routing}</Badge></td>
                    <td className="py-1 px-2 text-right font-mono">{formatNGN(e.preFairValueBalance)}</td>
                    <td className="py-1 px-2 text-right font-mono">{formatNGN(e.newFairValue)}</td>
                    <td className={`py-1 px-2 text-right font-mono ${e.fairValueChange < 0 ? 'text-destructive' : 'text-primary'}`}>{formatNGN(e.fairValueChange)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </PageSection>
  );
}

// ─── §5.5 Investment ECL ──────────────────────────────────────────────────

interface EclRow { rowId: string; holdingId: string; eclAmount: string; eclStage: string; }

function InvestmentEclSection({ periodId, holdings }: { periodId: string; holdings: InvestmentHoldingDto[] }) {
  const [rows, setRows]     = useState<EclRow[]>([{ rowId: crypto.randomUUID(), holdingId: '', eclAmount: '', eclStage: '1' }]);
  const [result, setResult] = useState<EclRecognitionResultDto | null>(null);
  const { toast } = useToast();

  // ECL-eligible: AC + FVOCI_DEBT (FVPL has no ECL; FVOCI_EQUITY excluded)
  const eligibleHoldings = holdings.filter((h) => h.classification === 'AMORTISED_COST' || h.classification === 'FVOCI_DEBT');

  const mutation = useMutation({
    mutationFn: () => validatedPost(
      '/api/v1/finance/ifrs9/ecl/recognise',
      {
        periodId,
        ecls: rows
          .filter((r) => r.holdingId && r.eclAmount)
          .map((r) => ({ holdingId: r.holdingId, eclAmount: Number(r.eclAmount), eclStage: Number(r.eclStage) })),
      },
      EclRecognitionResultDtoSchema,
    ),
    onSuccess: (r) => {
      setResult(r);
      toast({ title: 'Investment ECL recognised', description: `${r.holdingsProcessed} holdings · net movement ${formatNGN(r.totalEclMovement)}` });
    },
    onError: (err: unknown) => {
      toast({ title: 'Investment ECL failed', description: err instanceof Error ? err.message : 'Request failed', variant: 'destructive' });
    },
  });

  const validRows = rows.filter((r) => r.holdingId && r.eclAmount);

  return (
    <PageSection>
      <div className="mb-3">
        <h3 className="text-sm font-semibold">Investment ECL — §5.5 + §5.7.10A</h3>
        <p className="text-xs text-muted-foreground">Admin supplies the target total ECL per holding. AC: ECL reduces asset directly. FVOCI_DEBT: ECL routes to OCI reserve while carrying value stays at fair value. v2 will compute ECL from PD × LGD × EAD actuarially.</p>
      </div>
      <div className="space-y-2 mb-3">
        {rows.map((row, idx) => (
          <div key={row.rowId} className="flex items-center gap-2">
            <span className="font-mono text-xs text-muted-foreground w-6">#{idx + 1}</span>
            <Select
              value={row.holdingId || undefined}
              onValueChange={(v) => setRows((prev) => prev.map((r) => r.rowId === row.rowId ? { ...r, holdingId: v } : r))}
            >
              <SelectTrigger className="flex-1"><SelectValue placeholder="Choose holding…" /></SelectTrigger>
              <SelectContent>
                {eligibleHoldings.map((h) => (
                  <SelectItem key={h.id} value={h.id}>{h.securityName} ({h.classification})</SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Input
              type="number"
              step="0.01"
              placeholder="ECL amount ₦"
              value={row.eclAmount}
              onChange={(e) => setRows((prev) => prev.map((r) => r.rowId === row.rowId ? { ...r, eclAmount: e.target.value } : r))}
              className="w-36 font-mono"
            />
            <Select
              value={row.eclStage}
              onValueChange={(v) => setRows((prev) => prev.map((r) => r.rowId === row.rowId ? { ...r, eclStage: v } : r))}
            >
              <SelectTrigger className="w-24"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="1">Stage 1</SelectItem>
                <SelectItem value="2">Stage 2</SelectItem>
                <SelectItem value="3">Stage 3</SelectItem>
              </SelectContent>
            </Select>
            <Button size="sm" variant="ghost" onClick={() => setRows((prev) => prev.length > 1 ? prev.filter((r) => r.rowId !== row.rowId) : prev)}>×</Button>
          </div>
        ))}
        <Button size="sm" variant="outline" onClick={() => setRows((prev) => [...prev, { rowId: crypto.randomUUID(), holdingId: '', eclAmount: '', eclStage: '1' }])} disabled={eligibleHoldings.length === 0}>+ Add row</Button>
      </div>
      <Button onClick={() => mutation.mutate()} disabled={mutation.isPending || validRows.length === 0}>
        {mutation.isPending ? 'Running…' : `Recognise (${validRows.length} ${validRows.length === 1 ? 'ECL' : 'ECLs'})`}
      </Button>
      {result && (
        <div className="mt-4">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 mb-3">
            <StatCard label="Holdings processed" value={result.holdingsProcessed.toString()} />
            <StatCard label="ECL increase"       value={formatNGN(result.totalEclIncrease)} />
            <StatCard label="ECL reversal"       value={formatNGN(result.totalEclReversal)} />
          </div>
          {result.entries.length > 0 && (
            <table className="w-full text-xs border-collapse">
              <thead className="text-muted-foreground border-b">
                <tr>
                  <th className="text-left py-1 px-2">Security</th>
                  <th className="text-center py-1 px-2">Prior → New stage</th>
                  <th className="text-right py-1 px-2">Prior ECL</th>
                  <th className="text-right py-1 px-2">New ECL</th>
                  <th className="text-right py-1 px-2">Movement</th>
                </tr>
              </thead>
              <tbody>
                {result.entries.map((e) => (
                  <tr key={e.holdingId} className="border-b last:border-0">
                    <td className="py-1 px-2">{e.securityName}</td>
                    <td className="py-1 px-2 text-center font-mono text-xs">{e.priorStage ?? '—'} → {e.newStage ?? '—'}</td>
                    <td className="py-1 px-2 text-right font-mono">{formatNGN(e.priorEcl)}</td>
                    <td className="py-1 px-2 text-right font-mono">{formatNGN(e.newEcl)}</td>
                    <td className={`py-1 px-2 text-right font-mono ${e.eclMovement > 0 ? 'text-destructive' : e.eclMovement < 0 ? 'text-primary' : ''}`}>{formatNGN(e.eclMovement)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </PageSection>
  );
}

// ─── §5.5.15 Premium Receivable ECL ────────────────────────────────────────

interface BucketRow { rowId: string; label: string; outstandingAmount: string; defaultRate: string; }

function PremiumReceivableEclSection({ periodId }: { periodId: string }) {
  const [rows, setRows] = useState<BucketRow[]>([
    { rowId: crypto.randomUUID(), label: 'Current (0-30 days)',  outstandingAmount: '', defaultRate: '0.005' },
    { rowId: crypto.randomUUID(), label: '31-60 days',           outstandingAmount: '', defaultRate: '0.02' },
    { rowId: crypto.randomUUID(), label: '61-90 days',           outstandingAmount: '', defaultRate: '0.05' },
    { rowId: crypto.randomUUID(), label: 'Over 90 days',         outstandingAmount: '', defaultRate: '0.15' },
  ]);
  const [result, setResult] = useState<PremiumReceivableEclResultDto | null>(null);
  const { toast } = useToast();

  const mutation = useMutation({
    mutationFn: () => validatedPost(
      '/api/v1/finance/ifrs9/premium-receivable-ecl/recognise',
      {
        periodId,
        agingBuckets: rows
          .filter((r) => r.label && r.outstandingAmount && r.defaultRate)
          .map((r) => ({
            label:             r.label,
            outstandingAmount: Number(r.outstandingAmount),
            defaultRate:       Number(r.defaultRate),
          })),
      },
      PremiumReceivableEclResultDtoSchema,
    ),
    onSuccess: (r) => {
      setResult(r);
      toast({ title: 'Premium-receivable ECL recognised', description: `Target ECL ${formatNGN(r.targetLifetimeEcl)} · ${r.direction}` });
    },
    onError: (err: unknown) => {
      toast({ title: 'Premium-receivable ECL failed', description: err instanceof Error ? err.message : 'Request failed', variant: 'destructive' });
    },
  });

  const validRows = rows.filter((r) => r.label && r.outstandingAmount && r.defaultRate);

  return (
    <PageSection>
      <div className="mb-3">
        <h3 className="text-sm font-semibold">Premium Receivable ECL — §5.5.15 simplified approach</h3>
        <p className="text-xs text-muted-foreground">Lifetime ECL via per-bucket provision matrix. Lifetime ECL = Σ (outstanding × default rate). Default rates reflect both historical experience AND forward-looking adjustment per §B5.5.35.</p>
      </div>
      <div className="space-y-2 mb-3">
        {rows.map((row, idx) => (
          <div key={row.rowId} className="flex items-center gap-2">
            <span className="font-mono text-xs text-muted-foreground w-6">#{idx + 1}</span>
            <Input
              placeholder="Bucket label"
              value={row.label}
              onChange={(e) => setRows((prev) => prev.map((r) => r.rowId === row.rowId ? { ...r, label: e.target.value } : r))}
              className="flex-1"
            />
            <Input
              type="number"
              step="0.01"
              placeholder="Outstanding ₦"
              value={row.outstandingAmount}
              onChange={(e) => setRows((prev) => prev.map((r) => r.rowId === row.rowId ? { ...r, outstandingAmount: e.target.value } : r))}
              className="w-40 font-mono"
            />
            <Input
              type="number"
              step="0.001"
              placeholder="Default rate"
              value={row.defaultRate}
              onChange={(e) => setRows((prev) => prev.map((r) => r.rowId === row.rowId ? { ...r, defaultRate: e.target.value } : r))}
              className="w-32 font-mono"
            />
            <Button size="sm" variant="ghost" onClick={() => setRows((prev) => prev.length > 1 ? prev.filter((r) => r.rowId !== row.rowId) : prev)}>×</Button>
          </div>
        ))}
        <Button size="sm" variant="outline" onClick={() => setRows((prev) => [...prev, { rowId: crypto.randomUUID(), label: '', outstandingAmount: '', defaultRate: '' }])}>+ Add bucket</Button>
      </div>
      <Button onClick={() => mutation.mutate()} disabled={mutation.isPending || validRows.length === 0}>
        {mutation.isPending ? 'Running…' : `Recognise (${validRows.length} ${validRows.length === 1 ? 'bucket' : 'buckets'})`}
      </Button>
      {result && (
        <div className="mt-4">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 mb-3">
            <StatCard label="Total outstanding"     value={formatNGN(result.totalOutstanding)} />
            <StatCard label="Target lifetime ECL"   value={formatNGN(result.targetLifetimeEcl)} />
            <StatCard label="Prior cumulative ECL"  value={formatNGN(result.priorCumulativeEcl)} />
            <StatCard label="Movement"              value={`${formatNGN(result.eclMovement)} · ${result.direction}`} />
          </div>
          <table className="w-full text-xs border-collapse">
            <thead className="text-muted-foreground border-b">
              <tr>
                <th className="text-left py-1 px-2">Bucket</th>
                <th className="text-right py-1 px-2">Outstanding</th>
                <th className="text-right py-1 px-2">Default rate</th>
                <th className="text-right py-1 px-2">Bucket ECL</th>
              </tr>
            </thead>
            <tbody>
              {result.buckets.map((b, i) => (
                <tr key={i} className="border-b last:border-0">
                  <td className="py-1 px-2">{b.label}</td>
                  <td className="py-1 px-2 text-right font-mono">{formatNGN(b.outstandingAmount)}</td>
                  <td className="py-1 px-2 text-right font-mono">{(b.defaultRate * 100).toFixed(2)}%</td>
                  <td className="py-1 px-2 text-right font-mono">{formatNGN(b.bucketEcl)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </PageSection>
  );
}
