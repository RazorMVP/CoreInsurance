import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import {
  Badge,
  Button,
  DataTable, DataTableColumnHeader,
  PageHeader, PageSection,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Skeleton, StatCard,
  useToast,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import {
  validatedGet,
  validatedPost,
  FiscalYearDtoSchema,
  FiscalPeriodDtoSchema,
  type FiscalYearDto,
  type FiscalPeriodDto,
  type FiscalPeriodStatus,
  type FiscalPeriodType,
} from '@cia/api-client';
import { useAuth } from '@cia/auth';
import ClosePeriodDialog from './ClosePeriodDialog';
import ReopenPeriodDialog from './ReopenPeriodDialog';
import LockHistorySheet from './LockHistorySheet';
import CreateFiscalYearSheet from './CreateFiscalYearSheet';

const STATUS_VARIANT: Record<FiscalPeriodStatus, 'active' | 'pending' | 'rejected' | 'draft'> = {
  OPEN:        'active',
  SOFT_CLOSED: 'pending',
  HARD_CLOSED: 'rejected',
  REOPENED:    'draft',
};

const PERIOD_TYPES: FiscalPeriodType[] = ['MONTH', 'QUARTER', 'HALF_YEAR', 'YEAR'];

function formatDate(iso: string) {
  return new Date(iso).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
}

function formatPeriodLabel(p: FiscalPeriodDto) {
  if (p.periodType === 'MONTH')     return new Date(p.startDate).toLocaleDateString('en-GB', { month: 'long', year: 'numeric' });
  if (p.periodType === 'QUARTER')   return `Q${Math.floor(new Date(p.startDate).getMonth() / 3) + 1} ${new Date(p.startDate).getFullYear()}`;
  if (p.periodType === 'HALF_YEAR') return `H${new Date(p.startDate).getMonth() < 6 ? 1 : 2} ${new Date(p.startDate).getFullYear()}`;
  if (p.periodType === 'YEAR')      return new Date(p.startDate).getFullYear().toString();
  return `${p.startDate} → ${p.endDate}`;
}

export default function PeriodLockListPage() {
  const { hasRole } = useAuth();
  const canApprove = hasRole('FINANCE_APPROVE');
  const canReopen  = hasRole('FINANCE_REOPEN_PERIOD');

  const [selectedFyId, setSelectedFyId] = useState<string | null>(null);
  const [selectedType, setSelectedType] = useState<FiscalPeriodType>('MONTH');

  const [closeDialog, setCloseDialog] = useState<{ period: FiscalPeriodDto; mode: 'SOFT' | 'HARD' } | null>(null);
  const [reopenDialog, setReopenDialog] = useState<FiscalPeriodDto | null>(null);
  const [historyTarget, setHistoryTarget] = useState<FiscalPeriodDto | null>(null);
  const [createOpen, setCreateOpen] = useState(false);

  const queryClient = useQueryClient();
  const { toast } = useToast();

  const yearsQuery = useQuery<FiscalYearDto[]>({
    queryKey: ['closures', 'fiscal-years'],
    queryFn: () => validatedGet('/api/v1/finance/fiscal-years', z.array(FiscalYearDtoSchema)),
  });
  const years = yearsQuery.data ?? [];

  const activeFy = useMemo(
    () => years.find((y) => y.status === 'ACTIVE') ?? years[0] ?? null,
    [years],
  );
  const effectiveFyId = selectedFyId ?? activeFy?.id ?? null;

  const selectedFy = useMemo(
    () => years.find((y) => y.id === effectiveFyId) ?? null,
    [years, effectiveFyId],
  );

  const activateMutation = useMutation({
    mutationFn: (id: string) => validatedPost(`/api/v1/finance/fiscal-years/${id}/activate`, {}, FiscalYearDtoSchema),
    onSuccess: (fy) => {
      toast({ title: 'Fiscal year activated', description: `${fy.name} is now the tenant's ACTIVE fiscal year.` });
      queryClient.invalidateQueries({ queryKey: ['closures', 'fiscal-years'] });
    },
    onError: (err: unknown) => {
      toast({ title: 'Activate failed', description: err instanceof Error ? err.message : 'Request failed', variant: 'destructive' });
    },
  });

  const closeYearMutation = useMutation({
    mutationFn: (id: string) => validatedPost(`/api/v1/finance/fiscal-years/${id}/close`, {}, FiscalYearDtoSchema),
    onSuccess: (fy) => {
      toast({ title: 'Fiscal year closed', description: `${fy.name} closed. Any OPEN periods were hard-closed.` });
      queryClient.invalidateQueries({ queryKey: ['closures', 'fiscal-years'] });
      queryClient.invalidateQueries({ queryKey: ['closures', 'periods', fy.id] });
    },
    onError: (err: unknown) => {
      toast({ title: 'Close failed', description: err instanceof Error ? err.message : 'Request failed', variant: 'destructive' });
    },
  });

  const periodsQuery = useQuery<FiscalPeriodDto[]>({
    queryKey: ['closures', 'periods', effectiveFyId],
    queryFn: () => validatedGet(
      `/api/v1/finance/fiscal-years/${effectiveFyId}/periods`,
      z.array(FiscalPeriodDtoSchema),
    ),
    enabled: !!effectiveFyId,
  });
  const periods = periodsQuery.data ?? [];

  const filtered = useMemo(
    () => periods.filter((p) => p.periodType === selectedType),
    [periods, selectedType],
  );

  const counts = useMemo(() => {
    return filtered.reduce(
      (acc, p) => {
        acc[p.status] = (acc[p.status] ?? 0) + 1;
        return acc;
      },
      { OPEN: 0, SOFT_CLOSED: 0, HARD_CLOSED: 0, REOPENED: 0 } as Record<FiscalPeriodStatus, number>,
    );
  }, [filtered]);

  const columns: ColumnDef<FiscalPeriodDto>[] = [
    {
      id: 'label',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Period" />,
      cell: ({ row }) => <span className="font-medium">{formatPeriodLabel(row.original)}</span>,
    },
    {
      accessorKey: 'startDate',
      header: 'Start',
      cell: ({ getValue }) => <span className="font-mono text-xs">{formatDate(getValue() as string)}</span>,
    },
    {
      accessorKey: 'endDate',
      header: 'End',
      cell: ({ getValue }) => <span className="font-mono text-xs">{formatDate(getValue() as string)}</span>,
    },
    {
      accessorKey: 'status',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Status" />,
      cell: ({ getValue }) => {
        const status = getValue() as FiscalPeriodStatus;
        return <Badge variant={STATUS_VARIANT[status]}>{status}</Badge>;
      },
    },
    {
      accessorKey: 'softClosedAt',
      header: 'Soft-closed at',
      cell: ({ getValue }) => {
        const iso = getValue() as string | null | undefined;
        return iso
          ? <span className="font-mono text-xs">{formatDate(iso)}</span>
          : <span className="text-xs text-muted-foreground">—</span>;
      },
    },
    {
      accessorKey: 'hardClosedAt',
      header: 'Hard-closed at',
      cell: ({ getValue }) => {
        const iso = getValue() as string | null | undefined;
        return iso
          ? <span className="font-mono text-xs">{formatDate(iso)}</span>
          : <span className="text-xs text-muted-foreground">—</span>;
      },
    },
    {
      id: 'actions',
      header: () => <span className="text-right block">Actions</span>,
      cell: ({ row }) => {
        const p = row.original;
        const isOpen   = p.status === 'OPEN'        || p.status === 'REOPENED';
        const isSoft   = p.status === 'SOFT_CLOSED';
        const isHard   = p.status === 'HARD_CLOSED';
        return (
          <div className="flex items-center justify-end gap-1.5">
            {(isOpen || isSoft) && canApprove && (
              <>
                {isOpen && (
                  <Button size="sm" variant="outline" onClick={() => setCloseDialog({ period: p, mode: 'SOFT' })}>
                    Soft-close
                  </Button>
                )}
                <Button size="sm" variant="destructive" onClick={() => setCloseDialog({ period: p, mode: 'HARD' })}>
                  Hard-close
                </Button>
              </>
            )}
            {(isSoft || isHard) && canReopen && (
              <Button size="sm" variant="outline" onClick={() => setReopenDialog(p)}>
                Reopen
              </Button>
            )}
            <Button size="sm" variant="ghost" onClick={() => setHistoryTarget(p)}>
              History
            </Button>
          </div>
        );
      },
    },
  ];

  if (yearsQuery.isLoading) {
    return (
      <div className="p-6 space-y-4">
        <Skeleton className="h-9 w-64" />
        <Skeleton className="h-4 w-96" />
        <Skeleton className="h-72 w-full rounded-lg" />
      </div>
    );
  }

  if (years.length === 0) {
    return (
      <div className="p-6 space-y-5">
        <PageHeader
          title="Period Closures"
          description="Open / soft-close / hard-close / reopen the fiscal periods that drive every monetary entry."
        />
        <PageSection>
          <div className="rounded-md border bg-muted/40 px-4 py-12 text-center space-y-3">
            <p className="text-sm text-muted-foreground">
              No fiscal years configured for this tenant yet.
            </p>
            {canApprove && (
              <Button onClick={() => setCreateOpen(true)}>+ Create fiscal year</Button>
            )}
          </div>
        </PageSection>
        <CreateFiscalYearSheet
          open={createOpen}
          onOpenChange={setCreateOpen}
          onCreated={(fy) => setSelectedFyId(fy.id)}
        />
      </div>
    );
  }

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Period Closures"
        description="Open / soft-close / hard-close / reopen the fiscal periods that drive every monetary entry. Locks are enforced by the Hibernate PeriodLockInterceptor — soft-close opens a 5-business-day grace window; hard-close terminates writes."
      />

      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1">
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Fiscal year</label>
          <Select value={effectiveFyId ?? undefined} onValueChange={setSelectedFyId}>
            <SelectTrigger className="w-56">
              <SelectValue placeholder="Choose fiscal year…" />
            </SelectTrigger>
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
          <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Granularity</label>
          <Select value={selectedType} onValueChange={(v) => setSelectedType(v as FiscalPeriodType)}>
            <SelectTrigger className="w-48">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {PERIOD_TYPES.map((t) => (
                <SelectItem key={t} value={t}>{t.replace('_', ' ')}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        {selectedFy && (
          <div className="flex items-center gap-1.5">
            <Badge variant={selectedFy.status === 'ACTIVE' ? 'active' : selectedFy.status === 'CLOSED' ? 'rejected' : 'pending'}>
              {selectedFy.status}
            </Badge>
            {canApprove && selectedFy.status === 'PLANNING' && (
              <Button
                size="sm"
                variant="outline"
                onClick={() => activateMutation.mutate(selectedFy.id)}
                disabled={activateMutation.isPending}
              >
                {activateMutation.isPending ? 'Activating…' : 'Activate'}
              </Button>
            )}
            {canApprove && selectedFy.status === 'ACTIVE' && (
              <Button
                size="sm"
                variant="destructive"
                onClick={() => closeYearMutation.mutate(selectedFy.id)}
                disabled={closeYearMutation.isPending}
              >
                {closeYearMutation.isPending ? 'Closing…' : 'Close year'}
              </Button>
            )}
          </div>
        )}
        <div className="ml-auto">
          {canApprove && (
            <Button onClick={() => setCreateOpen(true)}>+ Create fiscal year</Button>
          )}
        </div>
      </div>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-4">
        <StatCard label="Open"        value={counts.OPEN.toString()} />
        <StatCard label="Soft-closed" value={counts.SOFT_CLOSED.toString()} />
        <StatCard label="Hard-closed" value={counts.HARD_CLOSED.toString()} />
        <StatCard label="Reopened"    value={counts.REOPENED.toString()} />
      </div>

      <PageSection>
        {periodsQuery.isLoading ? (
          <Skeleton className="h-72 w-full rounded-lg" />
        ) : (
          <DataTable columns={columns} data={filtered} />
        )}
      </PageSection>

      <ClosePeriodDialog
        period={closeDialog?.period ?? null}
        mode={closeDialog?.mode ?? 'SOFT'}
        open={!!closeDialog}
        onOpenChange={(open) => !open && setCloseDialog(null)}
      />
      <ReopenPeriodDialog
        period={reopenDialog}
        open={!!reopenDialog}
        onOpenChange={(open) => !open && setReopenDialog(null)}
      />
      <LockHistorySheet
        period={historyTarget}
        open={!!historyTarget}
        onOpenChange={(open) => !open && setHistoryTarget(null)}
      />
      <CreateFiscalYearSheet
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={(fy) => setSelectedFyId(fy.id)}
      />
    </div>
  );
}
