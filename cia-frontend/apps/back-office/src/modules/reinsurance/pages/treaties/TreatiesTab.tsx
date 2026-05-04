import { useState } from 'react';
import {
  Badge, Button, Card, CardContent, DataTable, DataTableColumnHeader,
  DataTableRowActions,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  EmptyState, PageSection, Separator, Skeleton, toast,
} from '@cia/ui';
import { type ColumnDef, type Row } from '@tanstack/react-table';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import {
  apiClient, validatedGet, TreatyDtoSchema,
  type ApiError, type ApiResponse,
  type TreatyDto, type TreatyStatus, type TreatyType,
  type ClassOfBusinessDto,
} from '@cia/api-client';
import TreatySheet from './TreatySheet';
import BatchReallocationSheet from '../allocations/BatchReallocationSheet';

interface ApiHttpError { response?: { data?: ApiResponse<unknown> }; message?: string }

// Allocation shape expected by BatchReallocationSheet drilldown.
// Backend has no per-treaty allocation list endpoint yet; this stays mocked
// until that endpoint exists.
interface AllocationRow {
  id:             string;
  policyNumber:   string;
  classOfBusiness:string;
  sumInsured:     number;
  treatyName:     string;
  status:         string;
}

// allow-mock: per-treaty allocation drilldown — no list endpoint exposes this nested view
const MOCK_TREATY_ALLOCATIONS: Record<string, AllocationRow[]> = {};

const TYPE_LABELS: Record<TreatyType, string> = { SURPLUS: 'Surplus', QUOTA_SHARE: 'Quota Share', XOL: 'XOL' };
const TYPE_COLORS: Record<TreatyType, string> = {
  SURPLUS:     'bg-[var(--status-active-bg)] text-[var(--status-active-fg)]',
  QUOTA_SHARE: 'bg-[var(--status-pending-bg)] text-[var(--status-pending-fg)]',
  XOL:         'bg-[var(--status-draft-bg)] text-[var(--status-draft-fg)]',
};
const ST_VARIANT: Record<TreatyStatus, 'active' | 'pending' | 'draft' | 'rejected'> = {
  DRAFT:     'draft',
  ACTIVE:    'active',
  EXPIRED:   'pending',
  CANCELLED: 'rejected',
};

/**
 * Backend `TreatyDto` doesn't carry a display name or class-of-business
 * name (only UUIDs). Derive a presentation name and resolve the class
 * via the setup classes-of-business lookup.
 */
function displayName(t: TreatyDto, classNameById: Record<string, string>): string {
  if (t.description) return t.description;
  const cls = (t.classOfBusinessId && classNameById[t.classOfBusinessId]) || 'Treaty';
  return `${cls} ${TYPE_LABELS[t.treatyType]} ${t.treatyYear}`;
}

function showServerError(err: unknown, title: string) {
  const ax = err as ApiHttpError;
  const errors: ApiError[] = ax?.response?.data?.errors ?? [];
  const description = errors.length > 0
    ? errors.map(e => e.message).filter(Boolean).join('. ')
    : ax?.message ?? 'An unexpected error occurred. Please try again.';
  toast({ variant: 'destructive', title, description });
}

export default function TreatiesTab() {
  const queryClient = useQueryClient();
  const [sheetOpen,   setSheetOpen]   = useState(false);
  const [batchTarget, setBatchTarget] = useState<TreatyDto | null>(null);
  const [confirm,     setConfirm]     = useState<{ treaty: TreatyDto; action: 'activate' | 'cancel' } | null>(null);

  const treatiesQuery = useQuery<TreatyDto[]>({
    queryKey: ['ri', 'treaties'],
    queryFn: () => validatedGet('/api/v1/ri/treaties', z.array(TreatyDtoSchema)),
  });
  const treaties = treatiesQuery.data ?? [];

  const classesQuery = useQuery<ClassOfBusinessDto[]>({
    queryKey: ['setup', 'classes-of-business'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: ClassOfBusinessDto[] }>('/api/v1/setup/classes-of-business');
      return res.data.data;
    },
  });
  const classNameById: Record<string, string> = Object.fromEntries(
    (classesQuery.data ?? []).map(c => [c.id, c.name]),
  );

  const transition = useMutation({
    mutationFn: async ({ id, action }: { id: string; action: 'activate' | 'cancel' }) => {
      await apiClient.post(`/api/v1/ri/treaties/${id}/${action}`);
    },
    onSuccess: (_data, { action }) => {
      queryClient.invalidateQueries({ queryKey: ['ri', 'treaties'] });
      toast({ title: action === 'activate' ? 'Treaty activated' : 'Treaty cancelled' });
      setConfirm(null);
    },
    onError: (err) => showServerError(err, 'Treaty transition failed'),
  });

  const columns: ColumnDef<TreatyDto>[] = [
    {
      id:     'name',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Treaty" />,
      accessorFn: row => displayName(row, classNameById),
      cell: ({ row }) => {
        const cls = row.original.classOfBusinessId
          ? (classNameById[row.original.classOfBusinessId] ?? '—')
          : '—';
        return (
          <div>
            <p className="font-medium text-foreground">{displayName(row.original, classNameById)}</p>
            <p className="text-xs text-muted-foreground">{cls} · {row.original.treatyYear}</p>
          </div>
        );
      },
    },
    {
      accessorKey: 'treatyType',
      header: 'Type',
      cell: ({ getValue }) => {
        const t = getValue() as TreatyType;
        return (
          <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-[10px] font-semibold ${TYPE_COLORS[t]}`}>
            {TYPE_LABELS[t]}
          </span>
        );
      },
    },
    {
      id:     'retention',
      header: 'Retention',
      cell: ({ row }) => {
        const t = row.original;
        if (t.treatyType === 'QUOTA_SHARE') return <span className="text-sm text-muted-foreground">N/A (% split)</span>;
        const value = t.treatyType === 'XOL' ? t.xolPerRiskRetention : t.retentionLimit;
        return <span className="text-sm tabular-nums">₦{(value ?? 0).toLocaleString()}</span>;
      },
    },
    {
      id:     'capacity',
      header: 'Capacity',
      cell: ({ row }) => {
        const t = row.original;
        if (t.treatyType === 'QUOTA_SHARE') return <span className="text-sm text-muted-foreground">—</span>;
        const value = t.treatyType === 'XOL' ? t.xolPerRiskLimit : t.surplusCapacity;
        return <span className="text-sm tabular-nums">₦{(value ?? 0).toLocaleString()}</span>;
      },
    },
    {
      accessorKey: 'participants',
      header:      'Reinsurers',
      cell: ({ row }) => (
        <div className="text-xs text-muted-foreground space-y-0.5">
          {row.original.participants.map(p => (
            <p key={p.id}>{p.reinsuranceCompanyName} {p.sharePercentage}%{p.isLead ? ' (lead)' : ''}</p>
          ))}
        </div>
      ),
    },
    {
      accessorKey: 'status',
      header:      'Status',
      cell: ({ getValue }) => {
        const s = getValue() as TreatyStatus;
        return <Badge variant={ST_VARIANT[s]} className="text-[10px]">{s.toLowerCase()}</Badge>;
      },
    },
    {
      id: 'actions',
      cell: ({ row }) => {
        const t = row.original;
        const actions = [
          { label: 'Batch reallocation', onClick: (r: Row<TreatyDto>) => setBatchTarget(r.original) },
        ];
        if (t.status === 'DRAFT') {
          actions.push({
            label:     'Activate',
            separator: true,
            onClick:   (r: Row<TreatyDto>) => setConfirm({ treaty: r.original, action: 'activate' }),
          } as typeof actions[0]);
        } else if (t.status === 'ACTIVE') {
          actions.push({
            label:     'Cancel',
            className: 'text-destructive',
            separator: true,
            onClick:   (r: Row<TreatyDto>) => setConfirm({ treaty: r.original, action: 'cancel' }),
          } as typeof actions[0]);
        }
        return <DataTableRowActions row={row as Row<TreatyDto>} actions={actions} />;
      },
    },
  ];

  // Treaty-type summary derived from real data.
  const summaryByType = (['SURPLUS', 'QUOTA_SHARE', 'XOL'] as TreatyType[]).map(type => {
    const list = treaties.filter(t => t.treatyType === type && t.status === 'ACTIVE');
    return { type, label: `${TYPE_LABELS[type]} Treaties`, count: list.length };
  });

  return (
    <div className="space-y-6">
      <PageSection
        title="Reinsurance Treaties"
        description="Surplus, Quota Share and XOL treaties define how risks are ceded each year."
        actions={<Button onClick={() => setSheetOpen(true)}>Add Treaty</Button>}
      >
        {treatiesQuery.isLoading ? (
          <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
        ) : treaties.length === 0 ? (
          <EmptyState title="No treaties configured" action={<Button onClick={() => setSheetOpen(true)}>Add Treaty</Button>} />
        ) : (
          <DataTable
            columns={columns}
            data={treaties}
            toolbar={{ searchColumn: 'name', searchPlaceholder: 'Search treaties…' }}
          />
        )}
      </PageSection>

      <Separator />

      <PageSection title="Treaty Summary" description="Active treaty counts by type for the current year.">
        <div className="grid grid-cols-3 gap-4">
          {summaryByType.map(s => (
            <Card key={s.type}>
              <CardContent className="p-4 space-y-1">
                <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-[10px] font-semibold ${TYPE_COLORS[s.type]}`}>
                  {s.label}
                </span>
                <p className="text-2xl font-semibold font-display text-foreground mt-2">{s.count}</p>
                <p className="text-xs text-muted-foreground">Active</p>
              </CardContent>
            </Card>
          ))}
        </div>
      </PageSection>

      {/* Create-treaty sheet (backend has no PUT — edit is not yet supported). */}
      <TreatySheet
        open={sheetOpen}
        onOpenChange={setSheetOpen}
        treaty={null}
        onSuccess={() => setSheetOpen(false)}
      />

      {/* Batch reallocation sheet — drilldown allocations remain mocked
          (no backend per-treaty allocation list endpoint). */}
      <BatchReallocationSheet
        open={batchTarget !== null}
        onOpenChange={(v) => { if (!v) setBatchTarget(null); }}
        allocations={batchTarget ? (MOCK_TREATY_ALLOCATIONS[batchTarget.id] ?? []) : []}
        onSuccess={() => setBatchTarget(null)}
      />

      {/* Activate / Cancel confirmation dialog */}
      <Dialog open={confirm !== null} onOpenChange={(v) => { if (!v) setConfirm(null); }}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>{confirm?.action === 'activate' ? 'Activate Treaty' : 'Cancel Treaty'}</DialogTitle>
            <DialogDescription>
              {confirm && (confirm.action === 'activate'
                ? <>Activating <span className="font-medium text-foreground">{displayName(confirm.treaty, classNameById)}</span> will allow new policies to be allocated to it.</>
                : <>Cancelling <span className="font-medium text-foreground">{displayName(confirm.treaty, classNameById)}</span> will prevent new allocations. Existing allocations are not affected.</>)}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirm(null)} disabled={transition.isPending}>Cancel</Button>
            <Button
              variant={confirm?.action === 'cancel' ? 'destructive' : 'default'}
              disabled={transition.isPending}
              onClick={() => confirm && transition.mutate({ id: confirm.treaty.id, action: confirm.action })}
            >
              {transition.isPending
                ? '…'
                : confirm?.action === 'activate' ? 'Activate' : 'Cancel Treaty'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
