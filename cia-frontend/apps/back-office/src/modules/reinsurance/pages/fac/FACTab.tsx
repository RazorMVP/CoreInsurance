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
import { formatNaira } from '@/lib/format';
import CreateFACOfferSheet  from './CreateFACOfferSheet';
import FACCreditNoteDialog  from './FACCreditNoteDialog';
import FACOfferSlipDialog   from './FACOfferSlipDialog';

interface ApiHttpError { response?: { data?: ApiResponse<unknown> }; message?: string }

// ── Inward FAC — not yet available ─────────────────────────────────────────
//
// The backend has a single RiFacCover entity that models OUTWARD facultative
// only (risks we cede to reinsurers). There is no inward-FAC concept in the
// data model, service, or API yet — so the Inward tab is an honest "coming
// soon" placeholder rather than a table over fabricated rows. It previously
// rendered a static mock plus Add / Renew / Extend / Cancel actions that
// POSTed to `/api/v1/reinsurance/fac/inward*` (all 404) — removed here so no
// one can act on data that does not exist. Building the inward-FAC backend
// (entity + migration + service + controller + DTOs) is the tracked follow-up;
// this view is where that feature will surface once it lands.

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

  const outwardQuery = useQuery<FacCoverDto[]>({
    queryKey: ['ri', 'fac-covers'],
    queryFn: () => validatedGet('/api/v1/ri/fac-covers', z.array(FacCoverDtoSchema)),
  });
  const outward = outwardQuery.data ?? [];

  // Outward action targets
  const [creditNoteTarget, setCreditNoteTarget] = useState<FacCoverDto | null>(null);
  const [offerSlipTarget,  setOfferSlipTarget]  = useState<FacCoverDto | null>(null);
  const [cancelTarget,     setCancelTarget]     = useState<FacCoverDto | null>(null);
  const [cancelReason,     setCancelReason]     = useState('');
  const [cancelReasonErr,  setCancelReasonErr]  = useState<string | null>(null);

  useEffect(() => {
    if (cancelTarget === null) { setCancelReason(''); setCancelReasonErr(null); }
  }, [cancelTarget]);

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
      cell: ({ getValue }) => <span className="text-sm tabular-nums">{formatNaira(getValue() as number | null | undefined)}</span>,
    },
    {
      accessorKey: 'premiumRate',
      header: 'Rate %',
      cell: ({ getValue }) => <span className="text-sm">{getValue() as number}%</span>,
    },
    {
      accessorKey: 'netPremium',
      header: 'Net Premium',
      cell: ({ getValue }) => <span className="text-sm tabular-nums text-primary">{formatNaira(getValue() as number | null | undefined)}</span>,
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

  return (
    <>
      <Tabs defaultValue="outward">
        <TabsList>
          <TabsTrigger value="outward">Outward FAC ({outward.length})</TabsTrigger>
          <TabsTrigger value="inward">Inward FAC</TabsTrigger>
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
            description="Facultative risks accepted from other ceding companies."
          >
            <EmptyState
              title="Inward FAC — coming soon"
              description="Inward facultative acceptances (accept a share of another insurer's risk, then renew, extend, or cancel the cover) will be managed here once backend support ships. This view is intentionally empty until then — no illustrative data is shown."
            />
          </PageSection>
        </TabsContent>
      </Tabs>

      {/* ── New offer sheet ─────────────────────────────────────────────────── */}
      <CreateFACOfferSheet
        open={facOfferOpen}
        onOpenChange={setFacOfferOpen}
        onSuccess={() => setFacOfferOpen(false)}
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
    </>
  );
}
