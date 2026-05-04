import { useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  PageSection, Skeleton, toast,
} from '@cia/ui';
import { type ColumnDef, type Row } from '@tanstack/react-table';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import {
  apiClient, validatedGet, AllocationDtoSchema, TreatyDtoSchema,
  type ApiError, type ApiResponse,
  type AllocationDto, type AllocationStatus, type ClassOfBusinessDto, type TreatyDto, type TreatyType,
} from '@cia/api-client';
import PolicyAllocationSheet  from './PolicyAllocationSheet';
import BatchReallocationSheet from './BatchReallocationSheet';
import CreateFACOfferSheet    from '../fac/CreateFACOfferSheet';

interface ApiHttpError { response?: { data?: ApiResponse<unknown> }; message?: string }

const TYPE_LABELS: Record<TreatyType, string> = { SURPLUS: 'Surplus', QUOTA_SHARE: 'Quota Share', XOL: 'XOL' };

// Presentation status: backend has DRAFT/CONFIRMED/CANCELLED. The
// "Excess Capacity" UI state is derived from excessAmount > 0 rather
// than a backend status.
type DisplayStatus = AllocationStatus | 'EXCESS_CAPACITY';

const STATUS_VARIANT: Record<DisplayStatus, 'active' | 'pending' | 'draft' | 'rejected'> = {
  DRAFT:           'draft',
  CONFIRMED:       'active',
  CANCELLED:       'rejected',
  EXCESS_CAPACITY: 'rejected',
};

const STATUS_LABEL: Record<DisplayStatus, string> = {
  DRAFT:           'Auto-allocated',
  CONFIRMED:       'Confirmed',
  CANCELLED:       'Cancelled',
  EXCESS_CAPACITY: 'Excess Capacity',
};

function displayStatus(a: AllocationDto): DisplayStatus {
  if (a.excessAmount > 0 && a.status !== 'CANCELLED') return 'EXCESS_CAPACITY';
  return a.status;
}

function reinsurersDisplay(a: AllocationDto): string {
  if (!a.lines || a.lines.length === 0) return 'Pending FAC';
  return a.lines.map(l => `${l.reinsuranceCompanyName} ${l.sharePercentage}%`).join(', ');
}

function showServerError(err: unknown, title: string) {
  const ax = err as ApiHttpError;
  const errors: ApiError[] = ax?.response?.data?.errors ?? [];
  const description = errors.length > 0
    ? errors.map(e => e.message).filter(Boolean).join('. ')
    : ax?.message ?? 'An unexpected error occurred. Please try again.';
  toast({ variant: 'destructive', title, description });
}

export default function AllocationsTab() {
  const queryClient = useQueryClient();

  const allocationsQuery = useQuery<AllocationDto[]>({
    queryKey: ['ri', 'allocations'],
    queryFn: () => validatedGet('/api/v1/ri/allocations', z.array(AllocationDtoSchema)),
  });
  const allocations = allocationsQuery.data ?? [];

  // Auxiliary lookups so the UI can show class-of-business and treaty year
  // (backend AllocationResponse only carries IDs).
  const classesQuery = useQuery<ClassOfBusinessDto[]>({
    queryKey: ['setup', 'classes-of-business'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: ClassOfBusinessDto[] }>('/api/v1/setup/classes-of-business');
      return res.data.data;
    },
  });
  const classNameById = Object.fromEntries((classesQuery.data ?? []).map(c => [c.id, c.name]));

  const treatiesQuery = useQuery<TreatyDto[]>({
    queryKey: ['ri', 'treaties'],
    queryFn: () => validatedGet('/api/v1/ri/treaties', z.array(TreatyDtoSchema)),
  });
  const treatyById = Object.fromEntries((treatiesQuery.data ?? []).map(t => [t.id, t]));

  const [viewAllocation, setViewAllocation] = useState<AllocationDto | null>(null);
  const [batchRealloc,   setBatchRealloc]   = useState(false);
  const [confirmAllOpen, setConfirmAllOpen] = useState(false);
  const [facOpen,        setFacOpen]        = useState(false);

  const pendingConfirmation = allocations.filter(a => displayStatus(a) === 'DRAFT');
  const excessCapacity      = allocations.filter(a => displayStatus(a) === 'EXCESS_CAPACITY');

  // Backend has no batch-confirm endpoint; fan out individual /confirm calls
  // (closes G3 TODO 7). Settles with Promise.all so a single failure rolls
  // back the optimistic toast.
  const confirmBatch = useMutation({
    mutationFn: async (ids: string[]) => {
      await Promise.all(
        ids.map(id => apiClient.post(`/api/v1/ri/allocations/${id}/confirm`)),
      );
    },
    onSuccess: (_data, ids) => {
      queryClient.invalidateQueries({ queryKey: ['ri', 'allocations'] });
      toast({ title: `Confirmed ${ids.length} allocation${ids.length === 1 ? '' : 's'}` });
      setConfirmAllOpen(false);
    },
    onError: (err) => showServerError(err, 'Batch confirmation failed'),
  });

  function classNameFor(a: AllocationDto): string {
    const treaty = a.treatyId ? treatyById[a.treatyId] : null;
    if (treaty?.classOfBusinessId) return classNameById[treaty.classOfBusinessId] ?? '—';
    return '—';
  }

  function treatyDisplayFor(a: AllocationDto): string {
    if (!a.treatyId) return 'No treaty';
    const treaty = treatyById[a.treatyId];
    if (!treaty) return a.treatyId.slice(0, 8);
    if (treaty.description) return treaty.description;
    const cls = treaty.classOfBusinessId ? (classNameById[treaty.classOfBusinessId] ?? 'Treaty') : 'Treaty';
    return `${cls} ${TYPE_LABELS[treaty.treatyType]} ${treaty.treatyYear}`;
  }

  const columns: ColumnDef<AllocationDto>[] = [
    {
      accessorKey: 'policyNumber',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Policy" />,
      cell: ({ row }) => (
        <button
          type="button"
          className="font-mono text-xs text-primary hover:underline underline-offset-2"
          onClick={() => setViewAllocation(row.original)}
        >
          {row.original.policyNumber}
        </button>
      ),
    },
    {
      id:     'class',
      header: 'Class',
      cell: ({ row }) => <span className="text-sm text-muted-foreground">{classNameFor(row.original)}</span>,
    },
    {
      accessorKey: 'ourShareSumInsured',
      header:      'Sum Insured',
      cell: ({ getValue }) => <span className="text-sm tabular-nums">₦{(getValue() as number).toLocaleString()}</span>,
    },
    {
      accessorKey: 'retainedAmount',
      header:      'Retention',
      cell: ({ getValue }) => <span className="text-sm tabular-nums">₦{(getValue() as number).toLocaleString()}</span>,
    },
    {
      accessorKey: 'cededAmount',
      header:      'Ceding',
      cell: ({ getValue }) => <span className="text-sm font-medium tabular-nums text-primary">₦{(getValue() as number).toLocaleString()}</span>,
    },
    {
      id:     'treaty',
      header: 'Treaty',
      cell: ({ row }) => (
        <div>
          <p className="text-sm">{treatyDisplayFor(row.original)}</p>
          <p className="text-xs text-muted-foreground">{reinsurersDisplay(row.original)}</p>
        </div>
      ),
    },
    {
      id:     'status',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Status" />,
      accessorFn: row => displayStatus(row),
      cell: ({ getValue }) => {
        const s = getValue() as DisplayStatus;
        return <Badge variant={STATUS_VARIANT[s]} className="text-[10px] whitespace-nowrap">{STATUS_LABEL[s]}</Badge>;
      },
    },
    {
      id: 'actions',
      cell: ({ row }) => {
        const ds = displayStatus(row.original);
        return (
          <DataTableRowActions
            row={row as Row<AllocationDto>}
            actions={[
              ...(ds === 'DRAFT'           ? [{ label: 'Confirm allocation', onClick: () => setViewAllocation(row.original) }] : []),
              ...(ds === 'EXCESS_CAPACITY' ? [{ label: 'Create FAC cover',    onClick: () => setFacOpen(true) }] : []),
              { label: 'View details', onClick: () => setViewAllocation(row.original) },
            ]}
          />
        );
      },
    },
  ];

  return (
    <>
      <div className="space-y-5">
        {/* Alert banners */}
        {(pendingConfirmation.length > 0 || excessCapacity.length > 0) && (
          <div className="flex gap-3 flex-wrap">
            {pendingConfirmation.length > 0 && (
              <div className="flex items-center gap-3 rounded-lg border bg-[var(--status-draft-bg)] px-4 py-3 flex-1 min-w-[280px]">
                <Badge variant="draft" className="text-[10px] shrink-0">{pendingConfirmation.length}</Badge>
                <p className="text-sm text-foreground">allocations awaiting confirmation</p>
                <Button size="sm" className="ml-auto shrink-0" onClick={() => setConfirmAllOpen(true)}>
                  Confirm All
                </Button>
              </div>
            )}
            {excessCapacity.length > 0 && (
              <div className="flex items-center gap-3 rounded-lg border bg-[var(--status-rejected-bg)] px-4 py-3 flex-1 min-w-[280px]">
                <Badge variant="rejected" className="text-[10px] shrink-0">{excessCapacity.length}</Badge>
                <p className="text-sm text-foreground">policies exceed gross treaty capacity — FAC required</p>
                <Button size="sm" variant="outline" className="ml-auto shrink-0" onClick={() => setFacOpen(true)}>
                  Create FAC
                </Button>
              </div>
            )}
          </div>
        )}

        <PageSection
          title="RI Allocations"
          description="Automatic treaty allocations per policy. Confirm auto-allocated items and approve for booking."
          actions={
            <Button variant="outline" size="sm" onClick={() => setBatchRealloc(true)}>
              Batch Reallocation
            </Button>
          }
        >
          {allocationsQuery.isLoading ? (
            <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
          ) : (
            <DataTable
              columns={columns}
              data={allocations}
              toolbar={{ searchColumn: 'policyNumber', searchPlaceholder: 'Search by policy…' }}
            />
          )}
        </PageSection>
      </div>

      {/* Policy / allocation detail sheet */}
      <PolicyAllocationSheet
        open={viewAllocation !== null}
        onOpenChange={(v) => { if (!v) setViewAllocation(null); }}
        allocation={viewAllocation}
        displayStatus={viewAllocation ? displayStatus(viewAllocation) : 'DRAFT'}
        classOfBusinessName={viewAllocation ? classNameFor(viewAllocation) : '—'}
        treatyDisplayName={viewAllocation ? treatyDisplayFor(viewAllocation) : ''}
        treatyYear={viewAllocation && viewAllocation.treatyId
          ? (treatyById[viewAllocation.treatyId]?.treatyYear ?? null)
          : null}
        reinsurersDisplay={viewAllocation ? reinsurersDisplay(viewAllocation) : ''}
        onCreateFAC={() => setFacOpen(true)}
      />

      {/* Batch reallocation sheet */}
      <BatchReallocationSheet
        open={batchRealloc}
        onOpenChange={setBatchRealloc}
        allocations={allocations.map(a => ({
          id:              a.id,
          policyNumber:    a.policyNumber,
          classOfBusiness: classNameFor(a),
          sumInsured:      a.ourShareSumInsured,
          treatyName:      treatyDisplayFor(a),
          status:          displayStatus(a),
        }))}
        onSuccess={() => setBatchRealloc(false)}
      />

      {/* Create FAC offer sheet */}
      <CreateFACOfferSheet
        open={facOpen}
        onOpenChange={setFacOpen}
        onSuccess={() => setFacOpen(false)}
      />

      {/* Confirm All dialog — fans out individual /confirm calls (G3 TODO 7) */}
      <Dialog open={confirmAllOpen} onOpenChange={setConfirmAllOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Confirm All Allocations</DialogTitle>
            <DialogDescription>
              The following {pendingConfirmation.length} auto-allocated polic{pendingConfirmation.length === 1 ? 'y' : 'ies'} will be marked as Confirmed.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2 max-h-56 overflow-y-auto py-1">
            {pendingConfirmation.map(a => (
              <div key={a.id} className="flex items-center justify-between rounded-md border px-3 py-2">
                <div>
                  <p className="font-mono text-xs text-primary">{a.policyNumber}</p>
                  <p className="text-xs text-muted-foreground">{classNameFor(a)} · {treatyDisplayFor(a)}</p>
                </div>
                <span className="text-xs tabular-nums text-muted-foreground">₦{a.cededAmount.toLocaleString()} ceded</span>
              </div>
            ))}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirmAllOpen(false)} disabled={confirmBatch.isPending}>Cancel</Button>
            <Button
              disabled={confirmBatch.isPending || pendingConfirmation.length === 0}
              onClick={() => confirmBatch.mutate(pendingConfirmation.map(a => a.id))}
            >
              {confirmBatch.isPending
                ? `Confirming ${pendingConfirmation.length}…`
                : `Confirm ${pendingConfirmation.length} Allocation${pendingConfirmation.length !== 1 ? 's' : ''}`}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
