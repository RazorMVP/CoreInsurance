import { Fragment, useMemo, useState } from 'react';
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
  Ifrs9MovementAnalysisDtoSchema,
  type FiscalYearDto, type FiscalPeriodDto,
  type Ifrs9MovementAnalysisDto,
  type Ifrs9InvestmentTotalsDto,
} from '@cia/api-client';

function formatNGN(amount: number) {
  return `₦${amount.toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function formatPeriodLabel(p: FiscalPeriodDto) {
  return new Date(p.startDate).toLocaleDateString('en-GB', { month: 'long', year: 'numeric' });
}

// Roll-forward rows in §B5.5.39 presentation order.
const INVESTMENT_ROWS: { key: keyof Ifrs9InvestmentTotalsDto; label: string; sign?: '+' | '−' }[] = [
  { key: 'openingBalance',          label: 'Opening balance' },
  { key: 'effectiveInterestIncome', label: 'Effective interest income (§5.4.1)',  sign: '+' },
  { key: 'couponReceived',          label: 'Coupon received',                     sign: '−' },
  { key: 'fairValueChangePnl',      label: 'Fair value change — P&L',             sign: '+' },
  { key: 'fairValueChangeOci',      label: 'Fair value change — OCI',             sign: '+' },
  { key: 'eclMovement',             label: 'ECL movement (§5.5)',                 sign: '+' },
  { key: 'impairmentLoss',          label: 'Impairment loss',                     sign: '−' },
  { key: 'disposals',               label: 'Disposals',                           sign: '−' },
  { key: 'closingBalance',          label: 'Closing balance' },
];

const DIRECTION_VARIANT: Record<string, 'active' | 'pending' | 'rejected' | 'draft'> = {
  INCREASE:  'pending',
  REVERSAL:  'active',
  NO_CHANGE: 'draft',
};

const CLASSIFICATION_VARIANT: Record<string, 'active' | 'pending' | 'draft' | 'rejected'> = {
  AMORTISED_COST: 'active',
  FVOCI_DEBT:     'pending',
  FVOCI_EQUITY:   'draft',
  FVPL:           'rejected',
};

export default function Ifrs9MovementAnalysisPage() {
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

  const maQuery = useQuery<Ifrs9MovementAnalysisDto>({
    queryKey: ['closures', 'ifrs9-movement-analysis', selectedPeriodId],
    queryFn:  () => validatedGet(
      `/api/v1/finance/ifrs9/movement-analysis/${selectedPeriodId}`,
      Ifrs9MovementAnalysisDtoSchema,
    ),
    enabled: !!selectedPeriodId,
  });
  const ma = maQuery.data;

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="IFRS 9 §B5.5.39 Movement Analysis"
        description="Combined disclosure shape — investment roll-forward across all Slice 3.3–3.6 engine writes plus the premium-receivable ECL aggregate. Read-only relay over V40's ifrs9_investment_movement_analysis SQL view + JE aggregates on accounts 5350 / 1340. Consumed downstream by Phase 4 NAICOM Ifrs9DisclosureEngine."
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
            Pick a MONTH period to view its §B5.5.39 movement analysis.
          </div>
        </PageSection>
      )}

      {selectedPeriodId && maQuery.isLoading && <Skeleton className="h-96 w-full rounded-lg" />}

      {selectedPeriodId && maQuery.isError && (
        <div className="rounded-md border border-destructive/50 bg-destructive/5 px-3 py-2 text-sm text-destructive">
          Failed to load §B5.5.39 movement analysis.
        </div>
      )}

      {ma && (
        <>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <StatCard label="Opening (investments)" value={formatNGN(ma.investments.totals.openingBalance)} />
            <StatCard label="Closing (investments)" value={formatNGN(ma.investments.totals.closingBalance)} />
            <StatCard label="Total P&L income"      value={formatNGN(ma.investments.totals.totalPnlIncome)} />
            <StatCard label="Total OCI movement"    value={formatNGN(ma.investments.totals.totalOciMovement)} />
          </div>

          <PageSection>
            <h3 className="text-sm font-semibold mb-2">Investment roll-forward (aggregate)</h3>
            <table className="w-full text-sm border-collapse">
              <tbody>
                {INVESTMENT_ROWS.map((r) => {
                  const isClosing = r.key === 'closingBalance';
                  const isOpening = r.key === 'openingBalance';
                  const amount    = ma.investments.totals[r.key];
                  return (
                    <tr
                      key={String(r.key)}
                      className={`border-b last:border-0 ${isClosing ? 'border-t-2 border-foreground/20 font-semibold' : ''}`}
                    >
                      <td className="py-1.5 px-2 w-12 text-center font-mono text-xs text-muted-foreground">{r.sign ?? ''}</td>
                      <td className="py-1.5 px-2">
                        {r.label}
                        {(isOpening || isClosing) && <span className="ml-2 text-xs text-muted-foreground">{isOpening ? '(start)' : '(end)'}</span>}
                      </td>
                      <td className="py-1.5 px-2 text-right font-mono">{formatNGN(amount)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </PageSection>

          <PageSection>
            <div className="flex items-center justify-between mb-2">
              <h3 className="text-sm font-semibold">Per-holding breakdown</h3>
              <span className="text-xs text-muted-foreground">
                {ma.investments.byHolding.length} {ma.investments.byHolding.length === 1 ? 'holding' : 'holdings'}
              </span>
            </div>
            {ma.investments.byHolding.length === 0 ? (
              <div className="rounded-md border bg-muted/40 px-4 py-8 text-center text-sm text-muted-foreground">
                No investment holdings with carrying-value rows for this period.
              </div>
            ) : (
              <table className="w-full text-xs border-collapse">
                <thead className="text-[10px] text-muted-foreground border-b uppercase tracking-wide">
                  <tr>
                    <th className="text-left py-2 px-2">Security</th>
                    <th className="text-left py-2 px-2">Class</th>
                    <th className="text-right py-2 px-2">Opening</th>
                    <th className="text-right py-2 px-2">Interest</th>
                    <th className="text-right py-2 px-2">Coupon</th>
                    <th className="text-right py-2 px-2">FV P&L</th>
                    <th className="text-right py-2 px-2">FV OCI</th>
                    <th className="text-right py-2 px-2">ECL move</th>
                    <th className="text-right py-2 px-2">Closing</th>
                    <th className="text-center py-2 px-2">Stage</th>
                  </tr>
                </thead>
                <tbody>
                  {ma.investments.byHolding.map((h) => (
                    <Fragment key={h.holdingId}>
                      <tr className="border-b last:border-0 hover:bg-secondary/30">
                        <td className="py-1.5 px-2">
                          <div className="font-medium">{h.securityName}</div>
                          {h.isin && <div className="font-mono text-[10px] text-muted-foreground">{h.isin}</div>}
                        </td>
                        <td className="py-1.5 px-2">
                          <Badge variant={CLASSIFICATION_VARIANT[h.classification]} className="text-[10px]">{h.classification}</Badge>
                        </td>
                        <td className="py-1.5 px-2 text-right font-mono">{formatNGN(h.openingBalance)}</td>
                        <td className="py-1.5 px-2 text-right font-mono">{formatNGN(h.effectiveInterestIncome)}</td>
                        <td className="py-1.5 px-2 text-right font-mono">{formatNGN(h.couponReceived)}</td>
                        <td className={`py-1.5 px-2 text-right font-mono ${h.fairValueChangePnl < 0 ? 'text-destructive' : ''}`}>{formatNGN(h.fairValueChangePnl)}</td>
                        <td className={`py-1.5 px-2 text-right font-mono ${h.fairValueChangeOci < 0 ? 'text-destructive' : ''}`}>{formatNGN(h.fairValueChangeOci)}</td>
                        <td className={`py-1.5 px-2 text-right font-mono ${h.eclMovement > 0 ? 'text-destructive' : ''}`}>{formatNGN(h.eclMovement)}</td>
                        <td className="py-1.5 px-2 text-right font-mono font-semibold">{formatNGN(h.closingBalance)}</td>
                        <td className="py-1.5 px-2 text-center">
                          {h.eclStage != null
                            ? <Badge variant="outline" className="text-[10px]">St{h.eclStage}</Badge>
                            : <span className="text-muted-foreground">—</span>}
                        </td>
                      </tr>
                    </Fragment>
                  ))}
                </tbody>
              </table>
            )}
          </PageSection>

          <PageSection>
            <div className="flex items-center justify-between mb-2">
              <h3 className="text-sm font-semibold">Premium-receivable ECL allowance</h3>
              <Badge variant={DIRECTION_VARIANT[ma.premiumReceivableEcl.direction] ?? 'draft'}>
                {ma.premiumReceivableEcl.direction}
              </Badge>
            </div>
            <p className="text-xs text-muted-foreground mb-3">
              Derived from JE aggregates on accounts 5350 (ECL expense) and 1340 (allowance for premium receivable). Opening = sum prior periods; closing = sum through period-end; movement = closing − opening.
            </p>
            <div className="grid grid-cols-3 gap-3">
              <StatCard label="Opening allowance" value={formatNGN(ma.premiumReceivableEcl.openingAllowance)} />
              <StatCard label="Period movement"   value={formatNGN(ma.premiumReceivableEcl.periodMovement)} />
              <StatCard label="Closing allowance" value={formatNGN(ma.premiumReceivableEcl.closingAllowance)} />
            </div>
          </PageSection>
        </>
      )}
    </div>
  );
}
