import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import {
  Badge,
  Button,
  PageHeader, PageSection,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Skeleton, StatCard,
} from '@cia/ui';
import {
  validatedGet,
  InvestmentHoldingDtoSchema,
  type InvestmentHoldingDto,
  type AssetType,
  type InvestmentClassification,
  type HoldingStatus,
} from '@cia/api-client';
import HoldingClassificationHistorySheet from './HoldingClassificationHistorySheet';
import { formatDate } from '@/lib/format';

type AssetTypeFilter      = AssetType | 'ALL';
type ClassificationFilter = InvestmentClassification | 'ALL';
type HoldingStatusFilter  = HoldingStatus | 'ALL';

const CLASSIFICATION_VARIANT: Record<InvestmentClassification, 'active' | 'pending' | 'draft' | 'rejected'> = {
  AMORTISED_COST: 'active',
  FVOCI_DEBT:     'pending',
  FVOCI_EQUITY:   'draft',
  FVPL:           'rejected',
};

const STATUS_VARIANT: Record<HoldingStatus, 'active' | 'draft' | 'rejected' | 'pending'> = {
  ACTIVE:   'active',
  MATURED:  'draft',
  SOLD:     'draft',
  IMPAIRED: 'rejected',
};

function formatMoney(amount: number, currency: string) {
  return `${currency} ${amount.toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}


export default function HoldingsListPage() {
  const [assetTypeFilter,      setAssetTypeFilter]      = useState<AssetTypeFilter>('ALL');
  const [classificationFilter, setClassificationFilter] = useState<ClassificationFilter>('ALL');
  const [statusFilter,         setStatusFilter]         = useState<HoldingStatusFilter>('ALL');

  const [detailHolding, setDetailHolding] = useState<InvestmentHoldingDto | null>(null);

  const holdingsQuery = useQuery<InvestmentHoldingDto[]>({
    queryKey: ['closures', 'ifrs9-holdings'],
    queryFn:  () => validatedGet('/api/v1/finance/ifrs9/holdings', z.array(InvestmentHoldingDtoSchema)),
  });
  const allHoldings = holdingsQuery.data ?? [];

  const filtered = useMemo(() => {
    return allHoldings.filter((h) => {
      if (assetTypeFilter      !== 'ALL' && h.assetType      !== assetTypeFilter)      return false;
      if (classificationFilter !== 'ALL' && h.classification !== classificationFilter) return false;
      if (statusFilter         !== 'ALL' && h.status         !== statusFilter)         return false;
      return true;
    });
  }, [allHoldings, assetTypeFilter, classificationFilter, statusFilter]);

  const counts = useMemo(() => {
    return filtered.reduce(
      (acc, h) => {
        acc.total += 1;
        if (h.status === 'ACTIVE')           acc.active  += 1;
        if (h.classification === 'FVPL')     acc.fvpl    += 1;
        acc.totalAcquisitionCost += h.acquisitionCost;
        return acc;
      },
      { total: 0, active: 0, fvpl: 0, totalAcquisitionCost: 0 },
    );
  }, [filtered]);

  function resetFilters() {
    setAssetTypeFilter('ALL');
    setClassificationFilter('ALL');
    setStatusFilter('ALL');
  }

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Investment Holdings"
        description="IFRS 9 investment portfolio. Each holding is classified under §4.1 (SPPI test + business model → AMORTISED_COST / FVOCI_DEBT / FVOCI_EQUITY / FVPL) at recognition. Reclassifications follow §B4.1.26 (rare, audited via Type-2 SCD). Click a row to see the classification trail."
      />

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <StatCard label="Holdings (filtered)"     value={counts.total.toLocaleString()} />
        <StatCard label="Active"                  value={counts.active.toLocaleString()} />
        <StatCard label="FVPL holdings"           value={counts.fvpl.toLocaleString()} />
        <StatCard label="Total acquisition cost"  value={formatMoney(counts.totalAcquisitionCost, 'NGN')} />
      </div>

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
        <div className="space-y-1">
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Asset type</label>
          <Select value={assetTypeFilter} onValueChange={(v) => setAssetTypeFilter(v as AssetTypeFilter)}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All</SelectItem>
              <SelectItem value="DEBT">Debt</SelectItem>
              <SelectItem value="EQUITY">Equity</SelectItem>
              <SelectItem value="MONEY_MARKET">Money market</SelectItem>
              <SelectItem value="DERIVATIVE">Derivative</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-1">
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Classification (§4.1)</label>
          <Select value={classificationFilter} onValueChange={(v) => setClassificationFilter(v as ClassificationFilter)}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All</SelectItem>
              <SelectItem value="AMORTISED_COST">Amortised cost</SelectItem>
              <SelectItem value="FVOCI_DEBT">FVOCI · Debt</SelectItem>
              <SelectItem value="FVOCI_EQUITY">FVOCI · Equity</SelectItem>
              <SelectItem value="FVPL">FVPL</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-1">
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Status</label>
          <Select value={statusFilter} onValueChange={(v) => setStatusFilter(v as HoldingStatusFilter)}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All</SelectItem>
              <SelectItem value="ACTIVE">Active</SelectItem>
              <SelectItem value="MATURED">Matured</SelectItem>
              <SelectItem value="SOLD">Sold</SelectItem>
              <SelectItem value="IMPAIRED">Impaired</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-1">
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">&nbsp;</label>
          <Button variant="outline" onClick={resetFilters} className="w-full">Reset</Button>
        </div>
      </div>

      <PageSection>
        {holdingsQuery.isLoading ? (
          <Skeleton className="h-72 w-full rounded-lg" />
        ) : holdingsQuery.isError ? (
          <div className="rounded-md border border-destructive/50 bg-destructive/5 px-4 py-3 text-sm text-destructive">
            Failed to load investment holdings.
          </div>
        ) : allHoldings.length === 0 ? (
          <div className="rounded-md border bg-muted/40 px-4 py-12 text-center text-sm text-muted-foreground">
            No investment holdings yet. Holdings are registered via <code className="font-mono">POST /api/v1/finance/ifrs9/holdings</code> and trigger §4.1 classification automatically.
          </div>
        ) : filtered.length === 0 ? (
          <div className="rounded-md border bg-muted/40 px-4 py-12 text-center text-sm text-muted-foreground">
            No holdings match the current filters.
          </div>
        ) : (
          <table className="w-full text-sm border-collapse">
            <thead className="text-xs text-muted-foreground border-b">
              <tr>
                <th className="text-left font-medium py-2 px-2">Security</th>
                <th className="text-left font-medium py-2 px-2">ISIN</th>
                <th className="text-left font-medium py-2 px-2">Asset type</th>
                <th className="text-left font-medium py-2 px-2">Classification (§4.1)</th>
                <th className="text-right font-medium py-2 px-2">Acquisition</th>
                <th className="text-right font-medium py-2 px-2">Cost</th>
                <th className="text-center font-medium py-2 px-2">ECL stage</th>
                <th className="text-left font-medium py-2 px-2">Status</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((h) => (
                <tr
                  key={h.id}
                  className="border-b last:border-0 hover:bg-secondary/40 cursor-pointer"
                  onClick={() => setDetailHolding(h)}
                >
                  <td className="py-2 px-2">
                    <div className="font-medium">{h.securityName}</div>
                    {h.issuer && <div className="text-xs text-muted-foreground">{h.issuer}</div>}
                  </td>
                  <td className="py-2 px-2 font-mono text-xs">{h.isin ?? <span className="text-muted-foreground">—</span>}</td>
                  <td className="py-2 px-2 font-mono text-xs">{h.assetType}</td>
                  <td className="py-2 px-2">
                    <Badge variant={CLASSIFICATION_VARIANT[h.classification]}>{h.classification}</Badge>
                  </td>
                  <td className="py-2 px-2 text-right font-mono text-xs">{formatDate(h.acquisitionDate)}</td>
                  <td className="py-2 px-2 text-right font-mono text-xs">{formatMoney(h.acquisitionCost, h.currencyCode)}</td>
                  <td className="py-2 px-2 text-center font-mono text-xs">
                    {h.eclStage != null
                      ? <Badge variant="outline" className="text-[10px]">Stage {h.eclStage}</Badge>
                      : <span className="text-muted-foreground">—</span>}
                  </td>
                  <td className="py-2 px-2">
                    <Badge variant={STATUS_VARIANT[h.status]}>{h.status}</Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </PageSection>

      <HoldingClassificationHistorySheet
        holding={detailHolding}
        open={!!detailHolding}
        onOpenChange={(open) => !open && setDetailHolding(null)}
      />
    </div>
  );
}
