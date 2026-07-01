import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import {
  Badge,
  Button,
  Input,
  PageHeader, PageSection,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Skeleton, StatCard,
} from '@cia/ui';
import {
  validatedGet,
  ContractGroupSummaryDtoSchema,
  PortfolioSummaryDtoSchema,
  type ContractGroupSummaryDto,
  type PortfolioSummaryDto,
  type Onerousness,
  type GroupStatus,
} from '@cia/api-client';
import { formatDate } from '@/lib/format';

const ONEROUSNESS_LABEL: Record<Onerousness, string> = {
  NOT_ONEROUS:                'Not onerous',
  NO_SIGNIFICANT_POSSIBILITY: 'No significant possibility',
  ONEROUS:                    'Onerous',
};

const ONEROUSNESS_VARIANT: Record<Onerousness, 'active' | 'pending' | 'rejected'> = {
  NOT_ONEROUS:                'active',
  NO_SIGNIFICANT_POSSIBILITY: 'pending',
  ONEROUS:                    'rejected',
};

const STATUS_VARIANT: Record<GroupStatus, 'active' | 'rejected'> = {
  OPEN:   'active',
  CLOSED: 'rejected',
};

type OnerousnessFilter = Onerousness | 'ALL';
type StatusFilter     = GroupStatus | 'ALL';


export default function ContractGroupsPage() {
  const [portfolioId, setPortfolioId] = useState<string | 'ALL'>('ALL');
  const [cohortYear,  setCohortYear]  = useState<string>('');
  const [onerousness, setOnerousness] = useState<OnerousnessFilter>('ALL');
  const [status,      setStatus]      = useState<StatusFilter>('ALL');

  // Portfolios for the filter dropdown
  const portfoliosQuery = useQuery<PortfolioSummaryDto[]>({
    queryKey: ['closures', 'portfolios'],
    queryFn:  () => validatedGet('/api/v1/finance/paa/portfolios', z.array(PortfolioSummaryDtoSchema)),
  });
  const portfolios = portfoliosQuery.data ?? [];

  // Groups list, scoped by filters
  const queryString = useMemo(() => {
    const p = new URLSearchParams();
    if (portfolioId !== 'ALL') p.set('portfolioId', portfolioId);
    if (cohortYear.trim())     p.set('cohortYear', cohortYear.trim());
    if (onerousness !== 'ALL') p.set('onerousness', onerousness);
    if (status !== 'ALL')      p.set('status', status);
    return p.toString();
  }, [portfolioId, cohortYear, onerousness, status]);

  const groupsQuery = useQuery<ContractGroupSummaryDto[]>({
    queryKey: ['closures', 'contract-groups', queryString],
    queryFn:  () => validatedGet(
      `/api/v1/finance/paa/contract-groups${queryString ? `?${queryString}` : ''}`,
      z.array(ContractGroupSummaryDtoSchema),
    ),
  });
  const groups = groupsQuery.data ?? [];

  const counts = useMemo(() => {
    return groups.reduce(
      (acc, g) => {
        acc.total += 1;
        if (g.onerousness === 'ONEROUS') acc.onerous += 1;
        if (g.status      === 'OPEN')    acc.open    += 1;
        return acc;
      },
      { total: 0, onerous: 0, open: 0 },
    );
  }, [groups]);

  function resetFilters() {
    setPortfolioId('ALL');
    setCohortYear('');
    setOnerousness('ALL');
    setStatus('ALL');
  }

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Contract Groups"
        description="IFRS 17 §16-22 groups of contracts. Each row is a (portfolio × cohort year × onerousness) triple — assignment is permanent per §22. Groups are created event-driven by Slice 2.2's ContractGroupingService on every PolicyApprovedEvent; this page is read-only."
      />

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <StatCard label="Groups (filtered)" value={counts.total.toLocaleString()} />
        <StatCard label="Onerous groups"    value={counts.onerous.toLocaleString()} />
        <StatCard label="Open cohorts"      value={counts.open.toLocaleString()} />
      </div>

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
        <div className="space-y-1">
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Portfolio</label>
          <Select value={portfolioId} onValueChange={(v) => setPortfolioId(v as string | 'ALL')}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All portfolios</SelectItem>
              {portfolios.map((p) => (
                <SelectItem key={p.id} value={p.id}>{p.code} — {p.name}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-1">
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Cohort year</label>
          <Input
            type="number"
            placeholder="e.g. 2027"
            value={cohortYear}
            onChange={(e) => setCohortYear(e.target.value)}
          />
        </div>
        <div className="space-y-1">
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Onerousness</label>
          <Select value={onerousness} onValueChange={(v) => setOnerousness(v as OnerousnessFilter)}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All</SelectItem>
              <SelectItem value="NOT_ONEROUS">Not onerous</SelectItem>
              <SelectItem value="NO_SIGNIFICANT_POSSIBILITY">No significant possibility</SelectItem>
              <SelectItem value="ONEROUS">Onerous</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-1">
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Status</label>
          <Select value={status} onValueChange={(v) => setStatus(v as StatusFilter)}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All</SelectItem>
              <SelectItem value="OPEN">OPEN</SelectItem>
              <SelectItem value="CLOSED">CLOSED</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-1">
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">&nbsp;</label>
          <Button variant="outline" onClick={resetFilters} className="w-full">Reset</Button>
        </div>
      </div>

      <PageSection>
        {groupsQuery.isLoading ? (
          <Skeleton className="h-72 w-full rounded-lg" />
        ) : groupsQuery.isError ? (
          <div className="rounded-md border border-destructive/50 bg-destructive/5 px-4 py-3 text-sm text-destructive">
            Failed to load contract groups.
          </div>
        ) : groups.length === 0 ? (
          <div className="rounded-md border bg-muted/40 px-4 py-12 text-center text-sm text-muted-foreground">
            {portfolios.length === 0
              ? 'No portfolios exist yet. Portfolios + contract groups are auto-created by Slice 2.2 on the first PolicyApprovedEvent — seed a policy to populate this view.'
              : 'No contract groups match the current filters.'}
          </div>
        ) : (
          <table className="w-full text-sm border-collapse">
            <thead className="text-xs text-muted-foreground border-b">
              <tr>
                <th className="text-left font-medium py-2 px-2">Portfolio</th>
                <th className="text-right font-medium py-2 px-2">Cohort year</th>
                <th className="text-left font-medium py-2 px-2">Onerousness (§16)</th>
                <th className="text-left font-medium py-2 px-2">Status</th>
                <th className="text-left font-medium py-2 px-2">Created</th>
                <th className="text-left font-medium py-2 px-2">Group ID</th>
              </tr>
            </thead>
            <tbody>
              {groups.map((g) => (
                <tr key={g.id} className="border-b last:border-0 hover:bg-secondary/40">
                  <td className="py-2 px-2">
                    <div className="font-mono text-xs">{g.portfolioCode}</div>
                    <div className="text-xs text-muted-foreground">{g.portfolioName}</div>
                  </td>
                  <td className="py-2 px-2 text-right font-mono">{g.cohortYear}</td>
                  <td className="py-2 px-2">
                    <Badge variant={ONEROUSNESS_VARIANT[g.onerousness]}>
                      {ONEROUSNESS_LABEL[g.onerousness]}
                    </Badge>
                  </td>
                  <td className="py-2 px-2">
                    <Badge variant={STATUS_VARIANT[g.status]}>{g.status}</Badge>
                  </td>
                  <td className="py-2 px-2 font-mono text-xs">{formatDate(g.createdAt)}</td>
                  <td className="py-2 px-2 font-mono text-xs text-muted-foreground">{g.id.slice(0, 8)}…</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </PageSection>
    </div>
  );
}
