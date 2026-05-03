import { useState } from 'react';
import {
  Badge, Button,
  DataTable, DataTableRowActions,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  EmptyState, PageSection, Skeleton, Tabs, TabsContent, TabsList, TabsTrigger,
} from '@cia/ui';
import { type ColumnDef, type Row } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import { apiClient } from '@cia/api-client';
import CreateFACOfferSheet  from './CreateFACOfferSheet';
import AddInwardFACSheet    from './AddInwardFACSheet';
import FACCreditNoteDialog  from './FACCreditNoteDialog';
import FACOfferSlipDialog   from './FACOfferSlipDialog';
import InwardFACActionSheet, { type InwardFACMode } from './InwardFACActionSheet';

// ── DTOs ─────────────────────────────────────────────────────────────────────

interface FacOutwardDto {
  id:           string;
  reference:    string;
  policyNumber: string;
  reinsurer:    string;
  sumInsured:   number;
  premiumRate:  number;
  status:       'OFFER_SENT' | 'ACCEPTED' | 'DECLINED' | 'DRAFT';
  offerDate:    string;
}

interface FacInwardDto {
  id:             string;
  reference:      string;
  cedingCompany:  string;
  classOfBusiness:string;
  sumInsured:     number;
  ourShare:       number;
  ourPremium:     number;
  startDate:      string;
  endDate:        string;
  status:         'ACTIVE' | 'RENEWED' | 'EXPIRED';
}

// ── Mock data ─────────────────────────────────────────────────────────────────

// allow-mock: fallback while /reinsurance/fac/outward is in flight
const mockOutward: FacOutwardDto[] = [
  { id: 'fo1', reference: 'FAC-OUT-2026-001', policyNumber: 'POL-2026-00005', reinsurer: 'Munich Re',          sumInsured: 26_000_000, premiumRate: 0.8, status: 'ACCEPTED',   offerDate: '2026-02-15' },
  { id: 'fo2', reference: 'FAC-OUT-2026-002', policyNumber: 'POL-2026-00006', reinsurer: "Lloyd's Syndicate",  sumInsured: 45_000_000, premiumRate: 1.2, status: 'OFFER_SENT', offerDate: '2026-03-10' },
];

// allow-mock: fallback while /reinsurance/fac/inward is in flight
const mockInward: FacInwardDto[] = [
  { id: 'fi1', reference: 'FAC-IN-2026-001', cedingCompany: 'Leadway Assurance', classOfBusiness: 'Fire & Burglary', sumInsured: 20_000_000, ourShare: 30, ourPremium: 48_000, startDate: '2026-01-01', endDate: '2027-01-01', status: 'ACTIVE' },
  { id: 'fi2', reference: 'FAC-IN-2026-002', cedingCompany: 'AIICO Insurance',   classOfBusiness: 'Marine Cargo',    sumInsured: 12_000_000, ourShare: 25, ourPremium: 22_500, startDate: '2025-07-01', endDate: '2026-07-01', status: 'EXPIRED' },
];

// ── Badge maps ────────────────────────────────────────────────────────────────

const outSt: Record<FacOutwardDto['status'], 'active'|'pending'|'rejected'|'draft'> = {
  ACCEPTED: 'active', OFFER_SENT: 'pending', DECLINED: 'rejected', DRAFT: 'draft',
};
const inSt: Record<FacInwardDto['status'], 'active'|'pending'|'cancelled'> = {
  ACTIVE: 'active', RENEWED: 'pending', EXPIRED: 'cancelled',
};

// ── Component ─────────────────────────────────────────────────────────────────

export default function FACTab() {
  // New offer / inward forms
  const [facOfferOpen,  setFacOfferOpen]  = useState(false);
  const [inwardFACOpen, setInwardFACOpen] = useState(false);

  const outwardQuery = useQuery<FacOutwardDto[]>({
    queryKey: ['reinsurance', 'fac', 'outward'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: FacOutwardDto[] }>('/api/v1/reinsurance/fac/outward');
      return res.data.data;
    },
  });
  const outward = outwardQuery.data ?? mockOutward;

  const inwardQuery = useQuery<FacInwardDto[]>({
    queryKey: ['reinsurance', 'fac', 'inward'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: FacInwardDto[] }>('/api/v1/reinsurance/fac/inward');
      return res.data.data;
    },
  });
  const inward = inwardQuery.data ?? mockInward;

  // Outward actions
  const [creditNoteTarget, setCreditNoteTarget] = useState<FacOutwardDto | null>(null);
  const [offerSlipTarget,  setOfferSlipTarget]  = useState<FacOutwardDto | null>(null);
  const [cancelFACTarget,  setCancelFACTarget]  = useState<FacOutwardDto | null>(null);

  // Inward actions
  const [inwardActionTarget, setInwardActionTarget] = useState<FacInwardDto | null>(null);
  const [inwardActionMode,   setInwardActionMode]   = useState<InwardFACMode>('RENEW');
  const [cancelInwardTarget, setCancelInwardTarget] = useState<FacInwardDto | null>(null);

  function openInwardAction(fac: FacInwardDto, mode: InwardFACMode) {
    setInwardActionTarget(fac);
    setInwardActionMode(mode);
  }

  // ── Outward columns ──────────────────────────────────────────────────────
  const outColumns: ColumnDef<FacOutwardDto>[] = [
    {
      accessorKey: 'reference',
      header: 'Reference',
      cell: ({ getValue }) => <span className="font-mono text-xs text-primary">{getValue() as string}</span>,
    },
    {
      accessorKey: 'policyNumber',
      header: 'Policy',
      cell: ({ getValue }) => <span className="font-mono text-xs text-muted-foreground">{getValue() as string}</span>,
    },
    {
      accessorKey: 'reinsurer',
      header: 'Reinsurer',
      cell: ({ getValue }) => <span className="text-sm">{getValue() as string}</span>,
    },
    {
      accessorKey: 'sumInsured',
      header: 'Sum Insured',
      cell: ({ getValue }) => <span className="text-sm tabular-nums">₦{(getValue() as number).toLocaleString()}</span>,
    },
    {
      accessorKey: 'premiumRate',
      header: 'Rate %',
      cell: ({ getValue }) => <span className="text-sm">{getValue() as number}%</span>,
    },
    {
      accessorKey: 'status',
      header: 'Status',
      cell: ({ getValue }) => {
        const s = getValue() as FacOutwardDto['status'];
        return <Badge variant={outSt[s]} className="text-[10px]">{s.replace('_', ' ').toLowerCase()}</Badge>;
      },
    },
    {
      accessorKey: 'offerDate',
      header: 'Offer Date',
      cell: ({ getValue }) => <span className="text-sm text-muted-foreground">{getValue() as string}</span>,
    },
    {
      id: 'actions',
      cell: ({ row }) => (
        <DataTableRowActions
          row={row as Row<FacOutwardDto>}
          actions={[
            ...(row.original.status === 'ACCEPTED' ? [{
              label: 'Generate credit note',
              onClick: (r: Row<FacOutwardDto>) => setCreditNoteTarget(r.original),
            }] : []),
            {
              label: 'Download offer slip',
              onClick: (r: Row<FacOutwardDto>) => setOfferSlipTarget(r.original),
            },
            {
              label: 'Cancel FAC',
              onClick: (r: Row<FacOutwardDto>) => setCancelFACTarget(r.original),
              separator: true,
              className: 'text-destructive',
            },
          ]}
        />
      ),
    },
  ];

  // ── Inward columns ───────────────────────────────────────────────────────
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
              {
                label: 'Renew',
                onClick: (r: Row<FacInwardDto>) => openInwardAction(r.original, 'RENEW'),
              },
              {
                label: 'Extend period',
                onClick: (r: Row<FacInwardDto>) => openInwardAction(r.original, 'EXTEND'),
              },
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
            description="Facultative risks accepted from other ceding companies."
            actions={<Button size="sm" onClick={() => setInwardFACOpen(true)}>Add Inward FAC</Button>}
          >
            {inwardQuery.isLoading
              ? <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
              : inward.length === 0
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

      {/* Cancel outward FAC confirmation */}
      <Dialog open={cancelFACTarget !== null} onOpenChange={(v) => { if (!v) setCancelFACTarget(null); }}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>Cancel FAC Cover</DialogTitle>
            <DialogDescription>
              {cancelFACTarget && (
                <>Cancel <span className="font-medium text-foreground">{cancelFACTarget.reference}</span> placed with <span className="font-medium text-foreground">{cancelFACTarget.reinsurer}</span>? This cannot be undone.</>
              )}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCancelFACTarget(null)}>Keep FAC</Button>
            <Button variant="destructive" onClick={() => {
              // TODO: DELETE /api/v1/reinsurance/fac/outward/{id}
              setCancelFACTarget(null);
            }}>
              Cancel FAC
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

      {/* Cancel inward FAC confirmation */}
      <Dialog open={cancelInwardTarget !== null} onOpenChange={(v) => { if (!v) setCancelInwardTarget(null); }}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>Cancel Inward FAC</DialogTitle>
            <DialogDescription>
              {cancelInwardTarget && (
                <>Cancel <span className="font-medium text-foreground">{cancelInwardTarget.reference}</span> from <span className="font-medium text-foreground">{cancelInwardTarget.cedingCompany}</span>? This cannot be undone.</>
              )}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCancelInwardTarget(null)}>Keep Cover</Button>
            <Button variant="destructive" onClick={() => {
              // TODO: DELETE /api/v1/reinsurance/fac/inward/{id}
              setCancelInwardTarget(null);
            }}>
              Cancel Cover
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
