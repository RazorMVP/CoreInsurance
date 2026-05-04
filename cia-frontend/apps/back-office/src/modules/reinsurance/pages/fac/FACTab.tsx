import { useEffect, useState } from 'react';
import {
  Badge, Button,
  DataTable, DataTableRowActions,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  EmptyState, Label, PageSection, Skeleton, Tabs, TabsContent, TabsList, TabsTrigger,
  Textarea, toast,
} from '@cia/ui';
import { type ColumnDef, type Row } from '@tanstack/react-table';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import {
  apiClient, validatedGet, FacCoverDtoSchema,
  type ApiError, type ApiResponse,
  type FacCoverDto, type FacCoverStatus,
} from '@cia/api-client';
import CreateFACOfferSheet  from './CreateFACOfferSheet';
import AddInwardFACSheet    from './AddInwardFACSheet';
import FACCreditNoteDialog  from './FACCreditNoteDialog';
import FACOfferSlipDialog   from './FACOfferSlipDialog';
import InwardFACActionSheet, { type InwardFACMode } from './InwardFACActionSheet';

interface ApiHttpError { response?: { data?: ApiResponse<unknown> }; message?: string }

// ── Inward FAC presentation type ───────────────────────────────────────────
//
// Backend has a single RiFacCover entity with no inward/outward direction
// field — what the UI calls "Outward" maps directly; "Inward" has no backend
// equivalent yet. Inward tab continues to render a static mock with the
// allow-mock comment until backend support lands.

interface FacInwardDto {
  id:              string;
  reference:       string;
  cedingCompany:   string;
  classOfBusiness: string;
  sumInsured:      number;
  ourShare:        number;
  ourPremium:      number;
  startDate:       string;
  endDate:         string;
  status:          'ACTIVE' | 'RENEWED' | 'EXPIRED';
}

// allow-mock: inward FAC has no backend equivalent yet (backend RiFacCover models outward only)
const mockInward: FacInwardDto[] = [
  { id: 'fi1', reference: 'FAC-IN-2026-001', cedingCompany: 'Leadway Assurance', classOfBusiness: 'Fire & Burglary', sumInsured: 20_000_000, ourShare: 30, ourPremium: 48_000, startDate: '2026-01-01', endDate: '2027-01-01', status: 'ACTIVE' },
  { id: 'fi2', reference: 'FAC-IN-2026-002', cedingCompany: 'AIICO Insurance',   classOfBusiness: 'Marine Cargo',    sumInsured: 12_000_000, ourShare: 25, ourPremium: 22_500, startDate: '2025-07-01', endDate: '2026-07-01', status: 'EXPIRED' },
];

// ── Badge maps ────────────────────────────────────────────────────────────────

const OUT_STATUS_VARIANT: Record<FacCoverStatus, 'active' | 'pending' | 'rejected'> = {
  PENDING:   'pending',
  CONFIRMED: 'active',
  CANCELLED: 'rejected',
};

const OUT_STATUS_LABEL: Record<FacCoverStatus, string> = {
  PENDING:   'Pending',
  CONFIRMED: 'Confirmed',
  CANCELLED: 'Cancelled',
};

const inSt: Record<FacInwardDto['status'], 'active' | 'pending' | 'cancelled'> = {
  ACTIVE: 'active', RENEWED: 'pending', EXPIRED: 'cancelled',
};

function showServerError(err: unknown, title: string) {
  const ax = err as ApiHttpError;
  const errors: ApiError[] = ax?.response?.data?.errors ?? [];
  const description = errors.length > 0
    ? errors.map(e => e.message).filter(Boolean).join('. ')
    : ax?.message ?? 'An unexpected error occurred. Please try again.';
  toast({ variant: 'destructive', title, description });
}

// ── Component ─────────────────────────────────────────────────────────────────

export default function FACTab() {
  const queryClient = useQueryClient();

  // Form sheets
  const [facOfferOpen,  setFacOfferOpen]  = useState(false);
  const [inwardFACOpen, setInwardFACOpen] = useState(false);

  const outwardQuery = useQuery<FacCoverDto[]>({
    queryKey: ['ri', 'fac-covers'],
    queryFn: () => validatedGet('/api/v1/ri/fac-covers', z.array(FacCoverDtoSchema)),
  });
  const outward = outwardQuery.data ?? [];
  const inward  = mockInward;

  // Outward action targets
  const [creditNoteTarget, setCreditNoteTarget] = useState<FacCoverDto | null>(null);
  const [offerSlipTarget,  setOfferSlipTarget]  = useState<FacCoverDto | null>(null);
  const [cancelTarget,     setCancelTarget]     = useState<FacCoverDto | null>(null);
  const [cancelReason,     setCancelReason]     = useState('');
  const [cancelReasonErr,  setCancelReasonErr]  = useState<string | null>(null);

  useEffect(() => {
    if (cancelTarget === null) { setCancelReason(''); setCancelReasonErr(null); }
  }, [cancelTarget]);

  // Inward action targets (no backend support yet — kept for UI parity)
  const [inwardActionTarget, setInwardActionTarget] = useState<FacInwardDto | null>(null);
  const [inwardActionMode,   setInwardActionMode]   = useState<InwardFACMode>('RENEW');
  const [cancelInwardTarget, setCancelInwardTarget] = useState<FacInwardDto | null>(null);

  function openInwardAction(fac: FacInwardDto, mode: InwardFACMode) {
    setInwardActionTarget(fac);
    setInwardActionMode(mode);
  }

  // Backend cancel endpoint takes { reason } and the same path serves both
  // the Outward UI's "Cancel FAC" and (when wired in future) the Inward UI's
  // cancel — closes G3 TODOs 5 + 6.
  const cancelFac = useMutation({
    mutationFn: async ({ id, reason }: { id: string; reason: string }) => {
      await apiClient.post(`/api/v1/ri/fac-covers/${id}/cancel`, { reason });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ri', 'fac-covers'] });
      toast({ title: 'FAC cover cancelled' });
      setCancelTarget(null);
    },
    onError: (err) => showServerError(err, 'Could not cancel FAC cover'),
  });

  function handleCancelConfirm() {
    if (!cancelTarget) return;
    if (!cancelReason.trim()) {
      setCancelReasonErr('Reason is required.');
      return;
    }
    cancelFac.mutate({ id: cancelTarget.id, reason: cancelReason });
  }

  // ── Outward columns ──────────────────────────────────────────────────────
  const outColumns: ColumnDef<FacCoverDto>[] = [
    {
      accessorKey: 'facReference',
      header: 'Reference',
      cell: ({ getValue }) => <span className="font-mono text-xs text-primary">{getValue() as string}</span>,
    },
    {
      accessorKey: 'policyNumber',
      header: 'Policy',
      cell: ({ getValue }) => <span className="font-mono text-xs text-muted-foreground">{getValue() as string}</span>,
    },
    {
      accessorKey: 'reinsuranceCompanyName',
      header: 'Reinsurer',
      cell: ({ getValue }) => <span className="text-sm">{getValue() as string}</span>,
    },
    {
      accessorKey: 'sumInsuredCeded',
      header: 'Sum Insured (Ceded)',
      cell: ({ getValue }) => <span className="text-sm tabular-nums">₦{(getValue() as number).toLocaleString()}</span>,
    },
    {
      accessorKey: 'premiumRate',
      header: 'Rate %',
      cell: ({ getValue }) => <span className="text-sm">{getValue() as number}%</span>,
    },
    {
      accessorKey: 'netPremium',
      header: 'Net Premium',
      cell: ({ getValue }) => <span className="text-sm tabular-nums text-primary">₦{(getValue() as number).toLocaleString()}</span>,
    },
    {
      accessorKey: 'status',
      header: 'Status',
      cell: ({ getValue }) => {
        const s = getValue() as FacCoverStatus;
        return <Badge variant={OUT_STATUS_VARIANT[s]} className="text-[10px]">{OUT_STATUS_LABEL[s]}</Badge>;
      },
    },
    {
      id: 'period',
      header: 'Period',
      cell: ({ row }) => (
        <span className="text-xs text-muted-foreground whitespace-nowrap">
          {row.original.coverFrom} → {row.original.coverTo}
        </span>
      ),
    },
    {
      id: 'actions',
      cell: ({ row }) => (
        <DataTableRowActions
          row={row as Row<FacCoverDto>}
          actions={[
            ...(row.original.status === 'CONFIRMED' ? [{
              label: 'Generate credit note',
              onClick: (r: Row<FacCoverDto>) => setCreditNoteTarget(r.original),
            }] : []),
            {
              label: 'Download offer slip',
              onClick: (r: Row<FacCoverDto>) => setOfferSlipTarget(r.original),
            },
            ...(row.original.status !== 'CANCELLED' ? [{
              label:     'Cancel FAC',
              onClick:   (r: Row<FacCoverDto>) => setCancelTarget(r.original),
              separator: true,
              className: 'text-destructive',
            }] : []),
          ]}
        />
      ),
    },
  ];

  // ── Inward columns (backend-less; mock-driven) ───────────────────────────
  const inColumns: ColumnDef<FacInwardDto>[] = [
    {
      accessorKey: 'reference',
      header: 'Reference',
      cell: ({ getValue }) => <span className="font-mono text-xs text-primary">{getValue() as string}</span>,
    },
    {
      accessorKey: 'cedingCompany',
      header: 'Ceding Company',
      cell: ({ getValue }) => <span className="text-sm">{getValue() as string}</span>,
    },
    {
      accessorKey: 'classOfBusiness',
      header: 'Class',
      cell: ({ getValue }) => <span className="text-sm text-muted-foreground">{getValue() as string}</span>,
    },
    {
      accessorKey: 'sumInsured',
      header: 'Sum Insured',
      cell: ({ getValue }) => <span className="text-sm tabular-nums">₦{(getValue() as number).toLocaleString()}</span>,
    },
    {
      accessorKey: 'ourShare',
      header: 'Our Share',
      cell: ({ getValue }) => <span className="text-sm font-medium">{getValue() as number}%</span>,
    },
    {
      accessorKey: 'ourPremium',
      header: 'Our Premium',
      cell: ({ getValue }) => <span className="text-sm tabular-nums text-primary">₦{(getValue() as number).toLocaleString()}</span>,
    },
    {
      accessorKey: 'status',
      header: 'Status',
      cell: ({ getValue }) => {
        const s = getValue() as FacInwardDto['status'];
        return <Badge variant={inSt[s]} className="text-[10px]">{s.toLowerCase()}</Badge>;
      },
    },
    {
      id: 'actions',
      cell: ({ row }) => (
        <DataTableRowActions
          row={row as Row<FacInwardDto>}
          actions={[
            ...(row.original.status === 'ACTIVE' ? [
              { label: 'Renew',         onClick: (r: Row<FacInwardDto>) => openInwardAction(r.original, 'RENEW') },
              { label: 'Extend period', onClick: (r: Row<FacInwardDto>) => openInwardAction(r.original, 'EXTEND') },
            ] : []),
            {
              label: row.original.status === 'EXPIRED' ? 'View expired' : 'Cancel',
              onClick: (r: Row<FacInwardDto>) => {
                if (r.original.status !== 'EXPIRED') setCancelInwardTarget(r.original);
              },
              ...(row.original.status !== 'EXPIRED' ? { className: 'text-destructive' } : {}),
            },
          ]}
        />
      ),
    },
  ];

  return (
    <>
      <Tabs defaultValue="outward">
        <TabsList>
          <TabsTrigger value="outward">Outward FAC ({outward.length})</TabsTrigger>
          <TabsTrigger value="inward">Inward FAC ({inward.length})</TabsTrigger>
        </TabsList>

        <TabsContent value="outward" className="mt-4">
          <PageSection
            title="Outward Facultative"
            description="Risks exceeding treaty capacity placed with reinsurers on a facultative basis."
            actions={<Button size="sm" onClick={() => setFacOfferOpen(true)}>Create FAC Offer</Button>}
          >
            {outwardQuery.isLoading
              ? <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
              : outward.length === 0
              ? <EmptyState title="No outward FAC covers" description="Create when a risk exceeds treaty gross capacity." />
              : <DataTable columns={outColumns} data={outward} toolbar={{ searchColumn: 'policyNumber', searchPlaceholder: 'Search…' }} />
            }
          </PageSection>
        </TabsContent>

        <TabsContent value="inward" className="mt-4">
          <PageSection
            title="Inward Facultative"
            description="Facultative risks accepted from other ceding companies. (Backend support pending — current view is illustrative.)"
            actions={<Button size="sm" onClick={() => setInwardFACOpen(true)}>Add Inward FAC</Button>}
          >
            {inward.length === 0
              ? <EmptyState title="No inward FAC policies" />
              : <DataTable columns={inColumns} data={inward} toolbar={{ searchColumn: 'cedingCompany', searchPlaceholder: 'Search…' }} />
            }
          </PageSection>
        </TabsContent>
      </Tabs>

      {/* ── New offer / inward form sheets ──────────────────────────────────── */}
      <CreateFACOfferSheet
        open={facOfferOpen}
        onOpenChange={setFacOfferOpen}
        onSuccess={() => setFacOfferOpen(false)}
      />
      <AddInwardFACSheet
        open={inwardFACOpen}
        onOpenChange={setInwardFACOpen}
        onSuccess={() => setInwardFACOpen(false)}
      />

      {/* ── Outward FAC dialogs ─────────────────────────────────────────────── */}
      <FACCreditNoteDialog
        open={creditNoteTarget !== null}
        onOpenChange={(v) => { if (!v) setCreditNoteTarget(null); }}
        fac={creditNoteTarget}
      />
      <FACOfferSlipDialog
        open={offerSlipTarget !== null}
        onOpenChange={(v) => { if (!v) setOfferSlipTarget(null); }}
        fac={offerSlipTarget}
      />

      {/* Cancel outward FAC confirmation — wires backend cancel with reason */}
      <Dialog open={cancelTarget !== null} onOpenChange={(v) => { if (!v) setCancelTarget(null); }}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>Cancel FAC Cover</DialogTitle>
            <DialogDescription>
              {cancelTarget && (
                <>Cancel <span className="font-medium text-foreground">{cancelTarget.facReference}</span> placed with <span className="font-medium text-foreground">{cancelTarget.reinsuranceCompanyName}</span>? This cannot be undone.</>
              )}
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-1.5">
            <Label htmlFor="fac-cancel-reason">Reason for cancellation</Label>
            <Textarea
              id="fac-cancel-reason"
              placeholder="e.g. risk lapsed / cover declined by reinsurer / replaced by treaty allocation"
              rows={3}
              value={cancelReason}
              onChange={e => { setCancelReason(e.target.value); if (cancelReasonErr) setCancelReasonErr(null); }}
              disabled={cancelFac.isPending}
            />
            {cancelReasonErr && <p className="text-xs text-destructive">{cancelReasonErr}</p>}
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setCancelTarget(null)} disabled={cancelFac.isPending}>
              Keep FAC
            </Button>
            <Button variant="destructive" disabled={cancelFac.isPending} onClick={handleCancelConfirm}>
              {cancelFac.isPending ? 'Cancelling…' : 'Cancel FAC'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ── Inward FAC renew / extend sheet ────────────────────────────────── */}
      <InwardFACActionSheet
        open={inwardActionTarget !== null}
        onOpenChange={(v) => { if (!v) setInwardActionTarget(null); }}
        fac={inwardActionTarget}
        mode={inwardActionMode}
        onSuccess={() => setInwardActionTarget(null)}
      />

      {/* Cancel inward FAC confirmation — backend lacks an inward FAC concept,
          so this stays a UI confirmation that doesn't dispatch yet. */}
      <Dialog open={cancelInwardTarget !== null} onOpenChange={(v) => { if (!v) setCancelInwardTarget(null); }}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>Cancel Inward FAC</DialogTitle>
            <DialogDescription>
              {cancelInwardTarget && (
                <>Cancel <span className="font-medium text-foreground">{cancelInwardTarget.reference}</span> from <span className="font-medium text-foreground">{cancelInwardTarget.cedingCompany}</span>? Inward FAC backend support is pending.</>
              )}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCancelInwardTarget(null)}>Keep Cover</Button>
            <Button variant="destructive" onClick={() => setCancelInwardTarget(null)}>
              Close
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
